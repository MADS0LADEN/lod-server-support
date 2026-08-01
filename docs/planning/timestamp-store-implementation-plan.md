# Timestamp persistence unification — implementation plan (v2, post-review)

v1 was reviewed by 3 Opus agents (2026-08-01; round record in the design doc §10) and
RESTRUCTURED: the review measured the original centerpiece (the `header > ts` boot
sweep) at 68–96% stamp loss per restart on real worlds and re-shaped the work into
independent deliverables. Companion: `timestamp-store-unification-design.md` (all
semantics; §3.4 carries the measurement). Every step below cites the review finding
that shaped it.

**The restructure (R3, confirmed by R1/R2):**

| Deliverable | Ships as | Blocked by |
|---|---|---|
| **PR-A** — client resync regression net (Phase 0, corrected) | its own PR to main | nothing |
| **PR-B** — zero-cost stamp-ghost rungs, bin-side (optional, small, backportable) | its own PR | nothing |
| **BAKE GATE** | — | store merged + RELEASED + live for weeks with no native/DB reports |
| **Phase 1** — server stamps table, WRITE PATH ONLY | `feat/stamps-unification` | the bake gate |
| **Phase 2** — client cache swap | its OWN plan doc when scheduled | PR-A (the contract suite) |
| chash-suspect seal rung (design §3.4) | separately-gated decision | Phase 1 + a measurement rerun |

---

## PR-A — client resync regression net (Phase 0, corrected per R2)

**Honest scope (R2 m3): NOT test-only.** It includes: the `ClientStampsPersistence`
seam interface + a bin adapter delegating to the existing statics, a static
`ClientStampsPersistence.active()` holder (default = the bin adapter), a DEFAULTED
no-arg path + package-private setter on `LodRequestManager` (NOT a required ctor arg
— 12 construction sites; R2 B3), retargeting `LSSClientCommands.clearAll` and
`BenchmarkHook.flushPendingIo` onto the holder (the soak client flushes immediately
before `Runtime.halt(0)` — miss it and every two-run soak scenario silently reads a
stale cache), and one accessor (`LodRequestManager.getServerAddress()`) for the Tier
3 pin. All behavior-neutral; reviewed as what it is.

**A.1 The contract suite (`ClientStampsPersistenceContractTest`)** — runs against the
bin adapter now, the sqlite impl in Phase 2, unchanged. Seam signature carries
`removals` and the eviction CENTER (R2 B1). Cases, corrected + extended:
- merge-save unions file history, memory wins per position, and applies REMOVALS
  before the memory overlay (a re-received position survives its own removal);
- eviction keeps the `cap` entries NEAREST the supplied center (Chebyshev), farthest
  evicted first — the REAL semantics (never "newest-retained"; `ts` is server
  content time, not recency — R2 B1);
- ORDERING group (R2 M2 — the invariant a sqlite swap is most likely to break): a
  queued write does not survive a subsequently-submitted clear; a remove submitted
  before a mergeSave for the same (server,dim) is never resurrected by it; flush()
  makes every previously-submitted op durable; synchronous ops block BOUNDEDLY
  (render-thread callers);
- `defaultReturnValue == -1` on ALL load return paths (populated / missing /
  wrong-version / bad-count / IO error — fastutil's default 0 breaks
  `markDirtyIfKnown`);
- `ts == 0` rows round-trip verbatim and stay purgeable — never fabricated for
  absent keys, never normalized away (the v16 legacy-0-stamp contract); values
  < -1 pass through unnormalized (the state map owns the clamp);
- mergeSave of an empty map + empty removals is a no-op; with removals it runs and
  may delete the store entry; clearForServer during an in-flight mergeSave ends
  cleared; cross-dimension isolation for the same packed position; distinct raw
  server strings are ISOLATED (pins the sqlite direction; the bin's
  sanitize-collision merge becomes an impl-specific test + a release-notes line —
  R2 m1);
- holder coherence: clearAll/flush observed through `active()` affect the store the
  manager writes to.
The bin's 32 MB oversize case stays bin-only (R2 N5 — don't run it twice).

**A.2 `ClientResyncRoundTripTest`** — scoped to the three genuinely-uncovered races
(R2 M1/M3; the plan's v1 list was part-covered, part-WRONG):
- load-after-session-stamps is an UNCONDITIONAL OVERWRITE today (`loadFrom` puts
  disk values over fresher session stamps) — pinned AS today's behavior; changing it
  is a separate decision;
- removeAsync-then-mergeSaveAsync same (server,dim) submit-order preservation (the
  FIFO race, end-to-end through the manager);
- two dimension changes inside one load window (the saveCache-drains-before-
  startAsyncCacheLoad ordering contract); load completing after disconnect/teardown
  is discarded.
The v1 items already pinned elsewhere (wiring pin, cross-dim unstamp, abandoned-load
window, ingest-failure-during-load, flush-drops-stale-load) are NOT re-implemented —
§0.1's coverage inventory is rewritten from the real test list (R2 M1).

**A.3 Bin crash shapes** (kept until the bin dies): leftover `.tmp` + intact main
loads main; truncated main discards cleanly; merge onto corrupt replaces.

**A.4 Tier 3 persistence pin** (~25-line step, not "one assertion" — R2 M4):
pre-clear this server's cache at test start; capture address + received positions
INSIDE the world block; after close: flush, reload via the seam, assert stamps
present for non-rejected positions (the rejector/thrower positions excluded).

**Exit gate:** all green against CURRENT code; any red is triaged as test-bug vs
live-find before merge; 2-subagent review of suite blind spots.

## PR-B — zero-cost stamp-ghost rungs (optional, backportable)

The measured-safe fraction of the old fallback: at bin load, drop stamps whose
region file is ABSENT or whose chunk slot is `loc == 0` (headers of only the
regions that own stamps; 0 false drops measured on three real worlds). Closes the
ghost-terrain stamp class on ALL FOUR support lines (the full plan never reaches
the older three — R3). Explicitly NOT the `header > ts` rule (design §3.4). Small
enough to ride any release; skip if the user prefers zero pre-bake motion.

## BAKE GATE (R3 M4)

Phase 1 starts only after: `feat/lod-store` merged, RELEASED, and running on the
live population (test server + any real adopters) for several weeks with no
native-load or store.db incident. Rationale: Phase 1 makes the once-opt-in SQLite
subsystem load-bearing on every server; the jar-in-jar native path currently has
one manual boot of live evidence.

## Phase 1 — server stamps table, WRITE PATH ONLY (amended per R1)

What ships: the delta write path + migration + dead-code deletion + the two
zero-cost sweep rungs. What does NOT ship: any `header > ts` drop (design §3.4 —
measured 68–96% loss; the chash-suspect rung is a separate gated decision).

- **1a. Stamps machinery in `SqliteLodStore`.** `stamps_<dimId>` DDL;
  `Op.StampPut(dim, pos[], ts[])` / `Op.StampDelete(dim, pos[])` on the control
  queue, immediate-commit deletes; whole-table `loadStamps(dim)` (NO ORDER BY/LIMIT
  — no ts index; bound = content-age trimming folded into the pass that already
  scans the table); stamps arms audited into EVERY `lods_`-assuming path
  (dropDimensionRows/sweep/eviction/DropAll/get/hasRow — a throw latches the store
  off and stamp flushes silently vanish; R1 M-E). `Op.DropAll` does NOT touch
  stamps (R1 MINOR-2 — the invalidate-all lever's documented contract; a stamps
  wipe would be a new verb). Meta partition per design §3.5 (consequence-based:
  mask/registry/wire/mc drop stamps, schema/codec don't).
- **1b. Cache-side write path.** Dirty set inside `put()` (thread-confined — the
  processing thread owns it, NO lock claims); DEDICATED ~2 s flush cadence (R1's
  open-decision-1 answer: the invalidation debounce never arms on pure-serve
  sessions and the periodic timer is ~5 min) emitting ONE array op per dimension
  per flush — pinned ("never one op per position": the #62 class relocates to the
  unbounded control queue otherwise); edit-path `StampDelete` INDEPENDENT and
  unconditional on the stamps handle in all three modes, never gated on the RAM
  removed-count (R1 M-B); not-found-path `StampDelete` GATED on removed > 0 (R1
  MINOR-1); shutdown drain before store.shutdown; the three
  OffThreadProcessorLifecycleTest pins RE-EXPRESSED (warm-resolve-after-restart,
  exit-path flush, debounce-fires) — never deleted.
- **1c. StampsStore handle + stamps-mode factory.** Separate narrow handle (design
  §3.5 M2 — never through the blob attach slot); stamps-mode open for off/memory;
  construction NOT behind the zstd codec probe (R1 M-E tail); degrade = RAM-only +
  persistent `stamps=ram` diag token (R1's open-decision-2 answer: `store=off`
  stays; independent `stamps=sqlite|ram|ram(latched)` token — golden VALUE updates
  on both platforms + both exporters); own gauge/counters OUTSIDE `SERVER_DRAINS`
  and outside the all-zero-while-off contract comment (R1 MINOR-4).
- **1d. Sweep: the two zero-cost rungs only** (vanished region, `loc == 0`), over
  the UNION of blob + stamp rows inside the same per-region block, `seen_mtime`
  recorded after both (R1 M-A); drops fan out via the DEDICATED mailbox event
  applying invalidateStamps only; adoption post-sweep via `adoptStamps` mailbox
  event, never-clobber-fresher, never marks dirty (design §3.3a as corrected —
  everything crosses to the processing thread through the mailbox, nothing touches
  the maps off-thread; R1 B-2). Unplaceable dimensions retain stamps unverified
  (R1 M-D).
- **1e. Migration.** Import SYNCHRONOUSLY on the constructing thread beside
  `openOrRecreateWriter()` (legacy path via Environment) — BEFORE the startup sweep
  (R1 B-3: attachStore-time import lands after the sweep and `seen_mtime` hides the
  rows forever); delete the bin + orphan `.tmp.*` files; reader retained a full
  minor line. Test: an imported ghost stamp (vanished region) drops the SAME boot.
- **1f. Deletion.** v1's ledger + R3's additions (13 test-rig dataDir params, the
  cold-restart-resync docstring AND violation message, soak.sh comments, diag
  goldens, exporter contracts, 3 CLAUDE.md sites) + R1's additions (saveExecutor +
  thread factory + test seams, snapshotForSave, sweepOrphanedTempFiles + constant +
  tests, the save-half of ColumnTimestampCacheTest — memo/eviction half stays,
  timestamp-save-backlog-fix.md marked historical). Release-notes item for the
  world-folder DB + the migration (R3 M7 tail).
- **1g. Live gates.** `cold-restart-resync` re-derived from a recording under the
  MINIMAL sweep (expect near-zero stamp loss now — the 1479-drop wave belonged to
  the rejected rule); dirty-while-offline; kill -9 leg extended to stamps
  consistency; full `soak.sh all` both platforms + one `lodStore=off` stamps-mode
  fresh-backfill/warm-rejoin pair; Paper /reload two-writer check (design §9).

**User decisions — RESOLVED (2026-08-01):**
(a) **Folia: no hard gate — release-note wording only** ("untested, leave it off").
The user accepts that stamps-mode-everywhere forecloses a code-level Folia store
gate permanently; when `folia-supported` returns, the Folia validation pass must
include a stamps-mode leg, and the release notes carry the untested warning until
then. This also closes the store round's open item (b) the same way.
(b) **No chash-suspect seal rung — declined.** The write-path plan is the accepted
tradeoff: immediate-commit deletes (milliseconds crash window) + Fabric's
first-save self-heal + the zero-cost ghost rungs are the shipped seal. The rung
stays recorded in design §3.4 as the designed remedy, to be revisited ONLY if a
live stale-seal is ever reported — no reserved column, no partial machinery.

## Phase 2 — client cache swap (EXTRACTED to its own plan when scheduled)

Carried here only as corrected direction (full plan to be written against the
landed PR-A suite): swap = one holder assignment (R2 B3); flush-eligibility rule —
a stamp flushes only after its column LEFT the decode queue, removals commit
with-or-before stamps (R2 B2: periodic flush otherwise inverts crash honesty into
over-claiming — a flushed stamp whose consumer rejection died with the crash is a
permanent hole); eviction keeps distance-from-center semantics or moves to an
explicit `last_seen` column — NEVER oldest-`ts` (R2 B1); cap stays per-(server,dim)
unless the multi-server budget change is deliberately accepted (R2 Q4); migration =
LAZY per-(server,dim) forward-map import (scan-all is impossible — sanitization is
lossy), bin marked-imported rather than deleted, whole tree removed one release
later (R2 Q3); `ClientStampsDb` lives in the CLIENT package (common/ ships to the
Paper jar and is Java 21 — R2 D3), sharing exactly one extracted helper (the native
probe + `org.sqlite.tmpdir` guard); write-coalescing pin (one flush in flight, N
stamps/interval = 1 flush — the #62 twin, R2 M7); retarget the four highest-value
existing pins (three LodRequestManagerTest persistence tests +
ClientColumnProcessorTest's disconnect-save) onto the seam in the swap PR (R2 M5).

## Resolved decisions (were "open" in v1)

1. Flush cadence → dedicated ~2 s stamps cadence (R1, definitive).
2. Diag → independent `stamps=` token, `store=` untouched (R1).
3. Client DB placement → client package + one shared helper (R2).
4. Client flush → eligibility-gated + bounded synchronous waits + holder-routed
   soak flush (R2).
5. Phase 0 on main → yes, as its own honestly-scoped PR (R2 + R3).
