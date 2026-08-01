# Store review fixes — sweep rework, fingerprint hash, ops honesty, invariant pins

**Status: PLAN (2026-08-01).** Source: the 4-agent Opus full-branch review of
`feat/lod-store` vs main (store-engine / serve-path / backfill-ops / tests-docs
lenses). Every item below was verified against the code by the reviewing agent and
cross-checked against the progress doc's recorded decisions; none re-litigates a
pinned tradeoff. Phases are ordered by ship-risk: **A** = production MAJORs, **B** =
durability/observability MINORs, **C** = the test MAJORs pinning it all + docs/gates.
Hold further Modrinth deploys until Phase A lands (the A1 sweep scan is exactly what
an uncapped, Chunky-pregenerated store hits at every boot as it grows).

Review-round shorthand used below: E=store-engine findings, S=serve-path, O=ops,
T=tests/docs, numbered as reported.

## Phase A — production MAJORs

### A1 (E1) — stat-driven freshness sweep (no more full-DB scans)

`sweepDimension` (SqliteLodStore.java:1166) currently opens with
`SELECT pos, src_stamp FROM lods_<dim>` — a full scan of every row (blob pages
included, ~3 rows/page at 16 KiB) on every boot (gating `serving`) and every Paper
300 s resweep, on the batcher with no IO restraint; it also boxes every position
into per-region `ArrayList<Long>`s and then re-`SELECT`s `src_stamp` per position it
already had. Rework to the implementation plan §1's original file-driven design:

1. **Enumerate candidate regions from the FILESYSTEM**, not from rows:
   `Files.list(regionDir)` for `r.<rx>.<rz>.mca` (reuse StoreBackfill's parse), plus
   the `regions` table for this dim. Per file: stat mtime; `seen_mtime` equal →
   **skip with zero row IO**. Changed/new → judge (step 2). In `regions` but file
   gone → vanished (step 3).
2. **Judge a changed region via indexed range reads**: read the 8 KiB header
   (existing `readHeaderTimestamps`), then fetch that region's rows with 32 range
   queries on the `pos` PRIMARY KEY — for each `cx` in `rx*32..rx*32+31`:
   `SELECT pos, src_stamp FROM lods_<dim> WHERE pos BETWEEN pack(cx, rz*32) AND
   pack(cx, rz*32+31)` (pos is the rowid; each range is an index seek returning
   ≤32 rows, and it returns `src_stamp` so the per-position re-query dies too).
   Stale rows (`headerStamps[idx] >= srcStamp`, unchanged rule) → `deleteRows`.
   The seen_mtime record keeps its existing raced-stat guards verbatim.
3. **Vanished-region detection via an index-only key scan**: `SELECT pos FROM
   lods_<dim> INDEXED BY <ts index from A2>` reads only index pages (~9 B/row —
   ~9 MB per 1M rows vs GBs of blob pages), grouped into a fastutil
   `LongOpenHashSet` of region keys (no boxed Longs). Region has rows but no file →
   range-delete via step-2 queries + drop its `regions` entry. This preserves the
   exact current fail-safe semantics (unsaved-generation rows still drop).
4. **Bound**: on an unchanged world the whole sweep is N stats + one index key
   scan; changed regions cost one 8 KiB header + ≤32 index seeks each. That removes
   the "maintenance IO outside every restraint gate" objection structurally — no
   hasHeadroom plumbing needed into the batcher.

Tests: rewrite/extend the `SqliteLodStoreTest` sweep table to drive the new
enumeration (all existing decision-table cases must pass unchanged — they pin
semantics, not mechanism); add a pin that an UNCHANGED region's rows are never read
(fake-`ColumnReader`-style probe is impossible here — pin via a row-count-of-pages
proxy: judge-count in the sweep log line, already exposed as `checkedRegions`).
The A/B evidence gate: time `awaitSweep` on a synthetic multi-GB store (script or
manual, recorded in the progress doc) before/after.

### A2 (E2) — `ts` index; eviction stops full-scanning

Eviction picks victims with `ORDER BY ts ASC LIMIT 512` (SqliteLodStore.java:1032)
with no index on `ts` — a full-table sort every 5 s gauge tick for as long as a
capped store sits at its cap. Fix: `CREATE INDEX IF NOT EXISTS lods_<dim>_ts ON
lods_<dim>(ts)` in `ensureDimTable` AND at open for pre-existing dims.
**Deliberately NOT a SCHEMA_VERSION bump** — `IF NOT EXISTS` is additive and
idempotent, so existing warm stores keep their rows and pay a one-time index build
(single pass, log one line) instead of a drop-and-rebuild. A1's vanished scan uses
this index (`INDEXED BY`); the eviction query picks it up automatically (verify
with `EXPLAIN QUERY PLAN` in a test — pin "no SCAN of lods_" in the plan output).

### A3 (E3) — registry fingerprint: hash the block half

`storeRegistryFingerprint` (RequestProcessingService.java:778, Paper twin :373)
fingerprints blocks as `BLOCK_STATE_REGISTRY.size()` — a COUNT. An id-permuting
registry change of identical total size (mod swap landing on the same state count)
passes the guard and serves every warm column as the wrong blocks, permanently,
no self-heal. Fix: FNV-hash the block-state identity strings in global-id order,
exactly the shape the biome half already uses (~30k strings, ms at boot), on BOTH
platforms (textual twins). Format: keep a recognisable `bs:<hex>/bio:<hex>` shape —
C1 pins it.

**Consequence to release-note**: the fingerprint STRING changes, so every existing
store drop-and-rebuilds ONCE on first boot with this fix (meta mismatch — the
mechanism working as designed). One cold rejoin per server, including the live
Modrinth deploy.

## Phase B — durability + operator honesty MINORs

### B1 (E8/O1, O6, O7) — a dead store must LOOK dead
- `formatToken`/status lines: consult `isHealthy()` + sweep state — render
  `store=latched` (with the latch reason) and `store=sweeping` distinctly; both
  platforms' `/lsslod store status` and the diag formatter token.
- The startup-sweep latch path (SqliteLodStore.java:673) gains `diag.recordError()`
  — currently the ONLY latch that never counts an error.
- Memory-mode status: show `mem=<MB> evicted=<memEvictions>` instead of the
  SQL-only `db=0MB wal=0MB evicted=0`.
- Degrade-to-memory `invalidate all` answer becomes honest: "persistent store
  degraded to memory this session (SQLite unavailable)" — not "requires
  lodStore=full" at an admin whose config says full.

### B2 (E4) — stop stamping tombstones into a latched store
`invalidate()`/`delete()` skip tombstone stamping when `!isHealthy() || shutdown` —
the batcher that would sweep them is gone and the store serves nothing, so they
protect nothing and accumulate forever (dirty fan-out adds thousands/minute).
MemoryLodStore post-shutdown twin same guard.

### B3 (E5) — commit the applyDeposit tombstone re-check DELETE immediately
The post-write re-check delete (SqliteLodStore.java:861) rides the shared 64-row
txn; for tombstones stamped by `DropAll`/eviction it is the ONLY delete, so a later
op's rollback resurrects a row the admin explicitly dropped (10 s later the
tombstone expires). Commit right after the re-check delete — the R1-M1 rule, applied
to its last uncovered path. (C3 pins it.)

### B4 (E10) — eviction un-marks backfill regions BEFORE deleting rows
Crash between `deleteRows` and the `DELETE FROM backfill` leaves a done-marked
region with its rows gone — a permanent warm hole on a capped store. Reorder;
un-mark-first makes the crash window harmless (an unmarked region re-walks
cheaply).

### B5 (E9) — shutdown-abort of a sweep is not an error
`runSweep`'s `InterruptedException` shutdown signal lands in the batcher's generic
`catch (Throwable)` → `recordError()` + a writer-failure strike. Catch it
distinctly: clean exit, no counter, no strike (soak `store.errors == 0` checks are
latently false-positive on shutdown timing today).

### B6 (E11) — close the backfill worker's reader connection on exit
Each `start()` thread registers a ThreadLocal reader conn freed only at store
shutdown; start/stop cycles leak connections. Add a package-visible
`closeReaderConnForCurrentThread()` and call it in the backfill `run()` finally.

### B7 (O2) — real spawn anchor for the walk
26.2 has a live replacement for the removed accessor:
`ServerLevel.getRespawnData().pos()` (verified present on the 26.2 merged jar).
Wire it into `RequestProcessingService`'s spawn resolver seam (per-dim; fall back
to origin on any throw — the seam's contract already tolerates that). The cap-stop
made "nearest-spawn first" load-bearing; the promise must be true on far-spawn
worlds.

### B8 (O3) — scope the done-mark shed guard to the BACKFILL's own deposits
`LodStoreService.deposit(...)` returns `boolean` (queued vs shed); StoreBackfill
counts its own sheds per region and drops the global `getDepositDrops()` snapshot
(any serve-path shed anywhere currently vetoes every region's done-mark — a busy
server re-walks the whole world every restart). Ripple: both stores + test doubles
(mechanical; the doubles already implement the interface).

### B9 (O5) — `invalidate all` vs in-flight region done-mark race
`DropAll` bumps a `dropGeneration` counter (batcher-written, volatile read);
StoreBackfill snapshots it at region start and skips `markBackfillRegionDone` when
it changed — a region judged before the drop must not be marked after it (permanent
warm hole today, since only cap-eviction un-marks).

### B10 (O4) — backfill status during the sweep wait
`run()` sets `statusLine = "starting: awaiting store sweep"` before `awaitSweep`;
`start()` resets the previous run's terminal status. (Today an auto-started
backfill reads `idle`/stale-`complete:` for up to 5 minutes and invites a
confused `backfill start` → "already running".)

### B11 (S1) — never snapshot a NON-TERMINAL mask probe into the store (Fabric)
The Environment's per-dim mask fingerprint can capture the R2-7 transient
`config:` fallback when AntiXray's controller registers late; two transient boots
in a row KEEP engine-masked rows under a config label (the R2-M1 leak class,
re-entered). Fix: `XrayMaskManager` exposes probe terminality; the snapshot uses a
per-boot nonce (`transient:<bootNonce>`) for non-terminal outcomes — never equal
across boots, so affected dims drop-and-re-warm (churn, the safe direction) and the
keep branch is closed. Document the AntiXray-warmth caveat; a terminal-follow-up
(update the dim fingerprint once the probe resolves) is recorded as future work,
not built now.

### B12 (E7) — make the cap firm
One 512-row/dim batch per 5 s (~780 KB/s) is out-runnable by deposits (backfill
default alone is ~760 KB/s). With A2's index, loop eviction batches within one
gauge tick until under cap (bounded: ≤8 batches/tick as a runaway stop), so the
cap actually binds.

### B13 (E6) — a deposit re-opens its region's sweep judgement
`applyDeposit` also clears `regions.seen_mtime` for the deposited row's region
(one indexed WITHOUT-ROWID delete; memo per txn to skip repeats). A deposit that
was queued while the sweep judged its region is then re-examined next sweep —
closes the rare stale-forever window without judgement-instant bookkeeping.

### B14 — small honesty batch (one commit)
- plugin.yml `usage:` gains the `store` verb (O8/S5).
- soak.sh `trap`s the CPU sampler so a mid-run exit can't orphan it onto the next
  scenario's JVM (O9).
- Backfill's `catch (Exception)` around `columnReader.read` handles
  `InterruptedException` as shutdown (re-interrupt, exit) instead of a phantom
  `store.errors` + cleared flag (O-NIT).
- `start()` clears `stopRequested` before the `running` CAS publishes (O-NIT).
- `StoreBackfillTest.regionWithReadErrorsIsNotMarkedDone` awaits
  `awaitDepositQueueEmpty` instead of `Thread.sleep(300)` (T6 — the sleep can
  false-PASS on a loaded box); `awaitDone` budget 10 s → 20 s (T7).

## Phase C — test MAJORs + docs/gates

### C1 (T1) — pin the service store wiring
Extract each platform's Environment assembly into a package-visible static
(`buildStoreEnvironment(...)`) + extract `storeRegistryFingerprint` similarly.
Pins: the registry fingerprint is non-empty and matches the A3 `bs:<hex>/bio:<hex>`
shape (a bare count fails), the mask fingerprint for an active manager is never
"off" (the R2-M1 regression shape), region dirs resolve per dim, and the
production call passes the FULL 8-arg Environment (the 6/7-arg conveniences are
test-only — pin by asserting the built Environment's registryFingerprint equals
the extracted fingerprint, which the convenience defaults `""` would fail).

### C2 (T2) — pin acquisition-time src_stamp
- `OffThreadProcessorStoreTest`'s double records the 5th arg; assert the disk
  deposit's `acquiredSeconds` <= a clock captured before the read completed
  (inject the reader seam's timing) and the gen deposit carries
  `entry.columnTimestamp()`.
- `SqliteLodStoreTest`: 5-arg deposits — acq older than a later header stamp →
  swept; acq newer → kept. (Today every sweep test uses the 4-arg legacy shape =
  the pre-fix semantics.)
- `StoreBackfillTest`: the reader seam asserts the deposit's acq predates the
  read's completion (the R1-M2 property, currently unpinned anywhere).

### C3 (T3) — pin the txn/latch invariants (minimal fault seams)
Package-visible test seams on SqliteLodStore: `failNextWriterOp()` (throws inside
the next `apply`, exercising rollback + re-queue) and reuse the existing latch
counter. Pins:
- applied `DeleteRows` survives a subsequent op's rollback (B3's re-check commit
  included: DropAll-tombstoned deposit re-check survives rollback);
- a failed delete is re-queued and applies on retry;
- 20 consecutive writer failures latch; post-latch: writes no-op, reads miss,
  `isHealthy()` false, status renders `latched` (B1), backfill aborts (the
  `isHealthy` abort rungs — currently unpinned);
- a real applied op resets the streak.

### C4 (T4) — Fabric `/lsslod store` tree coverage
`CommandGameTests` gains dispatch cases for `store status` / `store backfill
status` / `store invalidate all` against the Tier-2 server (store off → the
documented "off/unavailable" / "unavailable — requires lodStore=full" answers) —
pins the Brigadier literals + permission gating exist; the store-on answers stay
covered by `PaperCommandsTest`'s twin + burn-in.

### C5 (T5) — pin eviction's backfill un-mark (R3-M1's second half)
Small-cap store, done-marked region, force eviction of its rows → region unmarked
(post-B4 ordering), rows gone, re-walk re-warms. Complements the existing
invalidate-all half.

### C6 (T8) — release_check gates THIRD-PARTY-NOTICES
`check_third_party_notices`: the file present + non-empty in all four release jars
(LSS/VSS × fabric/paper), and `sqlite-jdbc-slim.jar` still carries its in-jar
Apache-2.0 text (the slim task must not strip it). Selftest cases both directions.

### C7 — docs
- CLAUDE.md: test counts (Tier 1 ~999 / Tier 2 59+1 / Paper 328 / selftest 191),
  the store test classes in the Tier-1 inventory, "~7 s" → current, store bullet
  wording from S3 (the backfill is the second DEPOSIT producer; the save hook is
  delete-only — "delivery choke ONLY" is stale), B7's spawn anchor note replaces
  the world-origin comment's premise, A1/A2/A3 blurbs.
- `store-backfill-tuning-plan.md` status → IMPLEMENTED (it still says PLAN; its
  sibling was flipped, T10).
- `soak-test-design.md` store snapshot contract: 15 → 19 keys (T11).
- `LodStoreService` threading javadoc names the backfill as the second deposit
  producer (S3).
- Release-notes draft: the A3 one-time rebuild note; keep the "~96%" claim scoped
  to the addressable band (T's caution).

## Verification gate (after each phase)

Tier 1 + Tier 2 + `:paper:test` + the four script selftests; after Phase A
additionally: `store-second-join` + `store-save-storm` (Fabric) and
`SOAK_PLATFORM=paper paper-store-unfired-event` (the sweep rework's live gate:
resweep semantics), and the synthetic big-store `awaitSweep` timing A/B recorded in
the progress doc. Then the Modrinth redeploy (expect the one A3 rebuild).

## Explicitly out of scope (recorded, deliberate)

- E-NITs: double zstd probe, ctor writer-handle leak on the degrade path,
  MemoryLodStore residentBytes underestimate (~40% on tiny entries), `backfill`
  table keyed by dim TEXT, eviction not feeding `notifySweepDrops` (no front cache
  exists to notify).
- O10: permanently-corrupt chunk pins its region never-done — recorded
  conservatism, bounded cost.
- O-NITs: legacy test-server config gets inert store keys; WAL-dominated
  `capped:` message at the 64 MB floor; `describePlan`'s one-time 10k stats.
- `store.sql_evictions` stays out of the exporters (recorded schema-churn
  decision; revisit with a cap-scenario soak).
- T13's deferrals stay open: no backfill-enabled soak scenario, no
  `lodStore=memory` burn-in leg.
- S1's terminal-follow-up (live fingerprint update once a late probe resolves).
