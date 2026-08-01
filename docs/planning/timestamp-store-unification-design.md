# Timestamp persistence unification — SQLite `stamps` table replaces the bin file

**Status: DESIGN + implementation plan** (see
`timestamp-store-implementation-plan.md` for the phased plan). Depends on
`feat/lod-store` merging first; must ride its own branch with a full soak gauntlet
(this touches protocol-load-bearing state on EVERY server, store-enabled or not).
Written 2026-07-31 out of the post-review brainstorm: "do we still need the separate
timestamp cache now that SQLite ships?"

**Sequencing decision (user, 2026-07-31): TEST-FIRST.** Before any persistence code
moves, a dedicated Phase 0 builds out client-resync-path regression coverage (the
thinnest-covered area this design later touches) — including a persistence CONTRACT
suite that runs against the bin implementations now and must pass unchanged against
the sqlite implementations later. The swap is only allowed to begin once Phase 0's
tests are green against CURRENT code.

## 0. The one-paragraph answer

The in-memory `ColumnTimestampCache` stays exactly what it is — the RAM-authoritative
up-to-date rung, consulted at ~50 ns on the processing thread; SQLite must never enter
that path. What this design replaces is its **durability layer**: the hand-rolled
`lss-timestamps.bin` snapshot file and the `TimestampSaveScheduler` die, and stamps
persist as tiny rows in `store.db` written through the LOD store's existing batcher.
The payoff is not elegance — it is (a) closing a real, currently-open crash-staleness
hole by giving stamps the same boot-time region-header verification the store's blob
rows already get, and (b) structurally deleting the whole-file-snapshot save model that
produced issue #62 (deltas cannot OOM; there is no snapshot).

## 1. The hole this closes (the motivating correctness bug)

Today's crash window, never yet seen live but structurally present:

1. Player edits a column at T1; the chunk save flushes to the region file (header
   stamp T1) and the dirty pipeline invalidates the RAM stamp.
2. The tscache save is debounced (`INVALIDATE_SAVE_MAX_CYCLES` ≈ 2 s cadence); the
   server crashes before `lss-timestamps.bin` rewrites.
3. Reboot loads the PRE-edit bin file: the column's stamp is back to T0 < T1.
4. A client that received the pre-edit column re-declares `ts = T0`; the up-to-date
   rung answers `T0 >= T0` → `UP_TO_DATE` → **stale seal**. Nothing consults the
   region header on this path; the client keeps pre-edit LOD until the NEXT edit of
   that same column or a cache-clearing rejoin.

The bin file is trusted as-loaded — it has no self-verification. The store, by
contrast, re-validates every row against region-header timestamps at each boot
(`src_stamp` sweep). Stamps deserve the same treatment, and once they live in the same
DB they get it from the same sweep pass at near-zero marginal cost.

Both sides of the seal close (this is the elegant core):

- **Crash BEFORE the stamp-delete persists:** the edit's chunk save moved the region
  mtime → the boot sweep re-examines that region → header T1 > stamp ts T0 → the
  stamp row is dropped → the position re-resolves honestly.
- **No crash:** the edit's invalidation fan-out (which already carries the store-blob
  `Op.DeleteRows`, immediately committed since the 4-agent round R1-M1 fix) ALSO
  deletes the stamp rows in the same op — as durable as the blob delete.

## 2. Current-state inventory (what exists and what it costs)

| Piece | Role | Fate under this design |
|---|---|---|
| `ColumnTimestampCache` (RAM maps, per-dim) | The up-to-date rung's authority; hosts the miss memo | **KEPT** — RAM authority unchanged; gains a dirty-delta set |
| `ColumnTimestampCache.save()/load()` + bin format + atomic-save + 64 KB buffered IO | Durability via whole-file snapshots of `world/data/lss-timestamps.bin` | **DELETED** |
| `TimestampSaveScheduler` (+ test) | Issue #62: latest-wins slot, one-drain bound, shutdown rejection — exists ONLY because snapshot saves could stack into ~42 MB closures | **DELETED** — the failure class is structural-deleted (no snapshots exist) |
| `OffThreadProcessor.invalidationDirty` / `invalidationCountdown` debounce | Triggers the snapshot save ~2 s after invalidations | **REPURPOSED** — same debounce now triggers the delta FLUSH |
| `dataDir` plumbing (both platforms → `OffThreadProcessor`) | Locates the bin file | **DELETED** (one ctor param, both services) |
| `perDimensionTimestampCacheSizeMB` + age eviction | Bounds RAM | **KEPT** — RAM cap still needed; eviction no longer costs durability (row stays in DB; a RAM-evicted stamp is a rung miss → one re-resolve — a store hit only where `lodStore=full`, a full NBT read on the DEFAULT server; R1) |
| Miss memo (`missMemoTtlSeconds` sibling map) | TTL'd authoritative-miss cache | **KEPT, RAM-ONLY** — persisting memoized misses across restart is semantically wrong; explicit non-goal |
| `invalidate()` vs `invalidateStamps()` split | Full clear vs stamps-only (memo preservation on the not-found drain) | **KEPT** — the op shapes below mirror it exactly |

## 3. Design

### 3.1 Schema

Per-dimension stamp tables beside the blob tables, reusing the existing `dims` mapping:

```sql
CREATE TABLE stamps_<dimId> (pos INTEGER PRIMARY KEY, ts INTEGER NOT NULL) WITHOUT ROWID;
```

~16-byte rows. `WITHOUT ROWID` is correct here (the plan's warning against it applied
to BLOB tables — index payload limits; stamps have no payload). No `src_stamp` column:
for a stamp, `ts` IS the content-time being compared, so the sweep rule is simply
`header > ts → drop` (see §3.4 for the boundary decision).

### 3.2 Write path — RAM first, deltas behind

- `put()` writes RAM exactly as today, and adds the position to a per-dim **dirty
  set** (a small `LongOpenHashSet` under the same lock discipline as the map).
- The existing ~2 s invalidation debounce (repurposed) drains the dirty set into ONE
  batched `Op.StampPut(dim, long[] positions, long[] ts)` on the store's **control
  queue** (unbounded, never shed). Batching means a fresh-backfill wave is a handful
  of array-carrying ops per flush, not thousands of rows of queue traffic.
- `invalidate()` (edits): enqueues an INDEPENDENT `Op.StampDelete` on the STAMPS
  handle, unconditionally in all three modes (R1 M-B: the original "extend the blob
  DeleteRows op" wording is unimplementable on off/memory servers — they have no
  blob store, and `applyInvalidations` guards on `store != null`; the edit-path
  delete must also NOT be gated on the RAM `removed` count — a RAM-evicted stamp can
  still sit stale in the DB). Same immediate-commit discipline as blob deletes. RAM
  clear stays synchronous, unchanged.
- `invalidateStamps()` (the not-found drain, memo-preserving): `Op.StampDelete`,
  GATED on `removed > 0` (R1 MINOR-1: it fires per authoritative miss at backfill
  rates and most misses have no stamp — ungated, that is one immediate commit per
  miss; the vanished-chunk sweep rung covers the RAM-evicted residue).
- Flush cadence (R1's definitive answer to old open decision 1): the invalidation
  debounce is NOT sufficient — it arms only on invalidations, and the periodic timer
  is ~5 min; a pure-serve session would persist nothing until shutdown. A DEDICATED
  stamps cadence replaces both triggers for stamps: flush when the dirty set is
  nonempty and ~2 s elapsed (keep INVALIDATE_SAVE_MAX_CYCLES' value as the
  interval), one array-carrying op per dimension per flush — NEVER one op per
  position (the #62 unbounded-growth class relocates to the unbounded control queue
  otherwise; pin this shape).
- Shutdown: one final dirty-set flush enqueued BEFORE `store.shutdown()`, which
  already drains the control queue and commits on the graceful path (the existing
  exit-path invalidation flush semantics — its three lifecycle pins in
  OffThreadProcessorLifecycleTest must be RE-EXPRESSED against the new machinery,
  never deleted).

Nothing on the write path blocks the processing thread beyond today's map/set writes.

### 3.3 Load path

`SELECT pos, ts FROM stamps_<dimId>` per known dim into the RAM maps, with the
existing RAM cap applied. NO `ORDER BY ts DESC LIMIT` — plan-review R3 (MINOR):
`stamps_*` has no ts index, so an ordered-limited load is a full scan-and-sort every
boot, O(n log n) not O(cap); the table is instead bounded by its own recency
EVICTION (batcher-side, same cadence as the RAM cap's eviction) and loaded whole.

**3.3a Load/sweep ordering (R3 BLOCKER B1, THREADING CORRECTED by R1 BLOCKER B-2).**
The startup sweep runs asynchronously on the batcher; a load at service construction
would seed RAM with stamps the sweep then deletes ONLY from the DB — the RAM
authority would keep serving the stale stamp. AND: `ColumnTimestampCache` is
thread-CONFINED (processing thread only — plain HashMap of fastutil maps, NO lock),
so neither the sweep fan-out nor the adoption may touch it directly; R3's original
fix direction was a data race. Binding decisions:
1. **Stamps adopt POST-sweep, via the processing-thread mailbox.** RAM starts empty;
   after `awaitSweep`, a new `adoptStamps(dim, pos[], ts[])` event rides the
   existing cross-thread mailbox (the `invalidateTimestamps` pattern) and applies on
   the processing thread with never-clobber-fresher semantics — and it must NOT mark
   the dirty set (or the next flush rewrites the table it just read). Until adoption
   the rung misses (benign re-resolves). `cold-restart-resync`'s premise changes —
   recalibrate from a recording; pin the adoption-merge semantics.
2. **Sweep drops fan out through a DEDICATED mailbox event** (`stampsSweptAway`) that
   applies `invalidateStamps` ONLY — never `invalidateTimestamps`, whose
   `applyInvalidations` also fires `store.invalidate`, clears the miss memo, and
   taints in-flight reads; a freshness drop must not amplify into a blob delete +
   memo wipe. Registered on `sweepDropListener` (batcher-side producer, processing-
   thread consumer). Registration-order note: the startup sweep's drops can fire
   before any consumer registers — safe ONLY because adoption is post-sweep; that
   dependency is load-bearing, state it in code.
Construction-order reality: the processor (tscache owner) is built BEFORE the store
in both services — load/adoption hangs off `attachStore`, not the constructor.

### 3.4 Boot-sweep extension — REJECTED AS SPECIFIED (R1 BLOCKER B-1, measured)

The original design here — drop stamp rows with `header > ts` per examined region —
was MEASURED by plan-review R1 (a read-only replay of the rule over three real
`lss-timestamps.bin` files against their worlds' `.mca` headers) and fails:

| world | stamps | DROPPED by `header > ts` |
|---|---|---|
| soak-worlds/base (Fabric) | 2171 | **68.1%** (median save-lag 3 s) |
| soak-worlds/base-paper | 2161 | **95.7%** (median save-lag 175 s) |
| benchmark-worlds/base | 45225 | **19.5%** |

Mechanism: probe serves stamp serve-time and generation serves stamp
serialization-time — the chunk's region write lands LATER on both paths, so the rule
drops every probe-/gen-served stamp, i.e. most of the cache, every restart. And the
"store hits absorb it" mitigation is void BY CONSTRUCTION: `src_stamp <= ts` on every
path while the blob rule is `header >= src_stamp`, so drop(stamps) ⊆ drop(blobs) —
measured: 0 of 1479 dropped stamps had a surviving store row. Every drop is a full
NBT read + column re-send. The same measurement kills R3's cheap fallback (the same
rule over bin-loaded stamps) as originally specified.

**What ships instead:**
- **The two measured-zero-cost rungs only:** vanished-region and `loc == 0` drops
  (a stamp for a chunk that no longer exists is unconditionally wrong — 0 drops on
  all three real worlds, closes the ghost-terrain class).
- **The full seal, IF deemed worth buying, is bought with content identity, not save
  time (R1 option 3):** carry `chash` on the stamp row (both platforms already hash
  served bytes); `header > ts` marks a stamp SUSPECT instead of dropping it — the
  next declaration forces one NBT read, and an equal hash answers `up_to_date` and
  re-stamps. Cost: one disk read per suspect position, ZERO wire traffic. This is a
  measurement-gated, separately-scheduled decision — not part of the initial swap.
- Context that shrinks the seal's residual value (R1): on Fabric an offline edit
  already self-heals — `DirtyContentFilter` treats the first observed save of a
  position as changed, marking dirty and invalidating the stamp; and the
  immediate-commit stamp-delete op (§3.2) shrinks the crash window from ~2 s to
  milliseconds. The remaining exposure is narrow; the suspect-verify rung is its
  honest price tag.
Sweep bookkeeping if/when any stamp rung lands (R1 M-A): the examined-region set
must be the UNION of blob rows and stamp rows (stamps mode has no blob tables — a
blob-derived set silently no-ops the whole pass), both judged inside the same
per-region block, `seen_mtime` recorded only after both.

### 3.5 Store-off servers — the DB becomes unconditional, blobs stay optional

Decision (option analysis in §6): the stamps machinery runs on EVERY server. When
`lodStore=off`, `store.db` opens in **stamps mode**: `meta`/`dims`/`stamps_*` tables
(+ `regions`, which the stamps sweep needs) — no blob tables, no deposits, no serving
rung, no backfill, no blob-tier size-cap eviction (stamp recency eviction only, §3.3).
`lodStore` becomes precisely "the blob tier switch", not "the database switch". The
sqlite native already ships in every release jar regardless of config.

**Amendments from plan-review R3 (all binding):**

- **Meta-guard PARTITION (R3 BLOCKER B2, boundary corrected by R1 M-C).** The
  store's drop-and-rebuild fires on schema/wire/mc/codec/registry drift — correct
  for encoded blobs, WRONG for most of it applied to stamps. The honest partition is
  by CONSEQUENCE, not by table: **stamps drop on drift that changes the BYTES a
  serve would produce** (x-ray mask fingerprint — an admin who widens masking must
  not have returning clients sealed onto unmasked ore locations; registry; wire; mc)
  **and survive drift that only changes the DB's internal encoding** (schema_version,
  codec — those get a separate `stamps_schema_version`). Note the mask/registry
  stamp-staleness hole PRE-EXISTS in the bin file (not a regression either way);
  this partition closes it for free rather than foreclosing it. Consequence stated
  plainly: under stamps mode the DB is NO LONGER purely derived data — "deleting the
  DB is always safe" weakens to "costs cold resyncs". Say so everywhere the
  derived-data mantra is repeated. Implementation note (R1 M-E): every
  `lods_<dimId>`-assuming arm (dropDimensionRows, sweep, eviction, DropAll,
  get/hasRow) must tolerate stamps mode — a throw on the batcher latches the store
  off and every subsequent stamp flush silently vanishes; enumerate + test each.
- **Unplaceable dimensions must not lose stamps (R1 M-D).** The sweep fail-safe-drops
  a whole dimension when the region resolver can't place it — right for blob rows
  (could serve wrong bytes), WRONG for stamps (worst case is one wrong `up_to_date`,
  and the resolver maps are frozen at construction, so a world created after enable
  — routine on Paper — would lose cross-restart warmth PERMANENTLY, a regression vs
  the bin file). Stamps for unverifiable dimensions are retained-unverified;
  recorded deliberately.
- **Mode matrix (M2).** `lodStore=memory` returns a `MemoryLodStore` into the ONE
  blob attach slot — stamps cannot ride it. Resolution: stamps get their OWN handle
  (`StampsStore`), a distinct narrow interface owned by the tscache flush path,
  SEPARATE from the blob `LodStoreService` attach slot. FULL mode: both handles are
  the same `SqliteLodStore`. memory/off: the stamps handle is a stamps-mode
  `SqliteLodStore`, the blob slot gets `MemoryLodStore`/null. No composite store, no
  re-introduced tiering.
- **Gauge isolation (M1).** Stamp ops must NOT feed `store.queue` — that gauge is in
  the soak checker's `SERVER_DRAINS` quiescence predicate, and a ~2 s flush cadence
  on EVERY server in EVERY scenario is precisely the off-serve-producer shape that
  red-ded the store burn-in twice. Separate `store.stamp_queue` gauge, OUTSIDE
  `SERVER_DRAINS`, pinned in the checker.
- **Degrade honesty (M7).** Stamps must not sit behind the zstd codec probe (they
  compress nothing — a zstd failure must not cost stamps); the degraded RAM-only
  state gets a persistent `/lsslod diag` token, not just one boot warning.
- **Folia (M3).** Adopting stamps-mode-everywhere DECIDES the still-open user
  question "hard store-off gate on Folia?" as **no gate, ever** (a gate would force
  the bin file back = the rejected two-paths outcome). This must be explicitly
  acknowledged by the user before Phase 1 starts, and stamps-mode inherits the
  store's zero-Folia-validation status the day `folia-supported` returns.
- **Support surface.** Every world folder gains `lss-lod/store.db(-wal/-shm)` + the
  extracted native, and the JVM gains the `org.sqlite.tmpdir` property,
  unconditionally — a user-visible change requiring a release-notes item ("why is
  there a database in my world folder?").

### 3.6 Migration

First boot on the new code: if `world/data/lss-timestamps.bin` exists, import it into
the stamps tables, then delete the file AND its orphan tmp siblings
(`lss-timestamps.bin.tmp.*` — their sweeper dies with the old code and would strand
multi-MB orphans on any server ever kill-9'd; R1). **Ordering is load-bearing (R1
BLOCKER B-3):** the import must run BEFORE the batcher's startup sweep — the
processor is constructed before the store, so an `attachStore`-time import lands
AFTER the sweep, and in FULL mode that boot's `seen_mtime` records then make the
imported rows sweep-invisible forever. Concretely: pass the legacy bin path through
the `Environment` and import synchronously on the constructing thread next to
`openOrRecreateWriter()`. The bin READER is retained for at least a full minor line
(R3: weekly cadence means "one release" orphans anyone who skips a version), reusing
its v4 gate / count guard / oversize discard unchanged.

## 4. Dead code deleted / net simplification

Deleted outright: `TimestampSaveScheduler` + `TimestampSaveSchedulerTest` (~2 files),
`ColumnTimestampCache.save()/load()` + bin-format constants + atomic-save-rename +
buffered-stream code + their dedicated tests (save atomicity, buffered-IO boundary
round-trip), the `dataDir` ctor plumbing through both `OffThreadProcessor` subclasses
and both service wirings, and the `lss-timestamps.bin` path handling. The #62 pin
("processor wiring reds a raw-execute revert") retires WITH the scheduler — the bug
class it pinned cannot be re-expressed when no snapshot save exists.

Kept-but-simplified: the invalidation debounce (fires a delta flush instead of a
snapshot schedule); `ColumnTimestampCache` itself shrinks to maps + dirty-set + memo.

New code: two small `Op` variants + the stamps-table DDL/apply/load/sweep arms inside
`SqliteLodStore` (all following existing patterns), and the stamps-mode gate in the
factory. Net line count goes DOWN; more importantly, the number of independently-aging
persistence mechanisms for column truth goes from two to one.

## 5. Explicit non-goals

- The miss memo never persists (unchanged rule).
- The up-to-date rung never reads SQLite — no on-miss DB fallback; a RAM-evicted
  stamp costs a re-resolve, same as today.
- No stamps for the CLIENT cache (`ColumnCacheStore` is client-side and out of scope).
- No change to the wire, the want-set model, or any law's terms (A3's store-hit
  inequality absorbs the restart re-resolve wave; verify, don't assume).
- v16 shim untouched (stamps are server-internal).

## 6. Decision log — options weighed

| Option | Verdict | Why |
|---|---|---|
| Full swap: rung reads SQLite | REJECTED | ~50 ns map read → 1-10 µs JNI point read on the processing thread's hottest rung; no read-your-writes from an async batcher |
| Stamps table only when `lodStore != off` | REJECTED | Store-off servers (the DEFAULT) would lose cross-restart up-to-date — a regression |
| Keep bin file as store-off fallback | REJECTED | Two durability paths forever; complexity strictly increases — the opposite of the goal |
| **Stamps-mode DB on every server (chosen)** | — | One path; native already ships; degrade = RAM-only session (benign); the sweep verification applies everywhere |
| Targeted fix only (boot-time suspicion of loaded stamps, keep bin) | VIABLE FALLBACK | A fraction of the work for most of the seal value; loses the #62-class deletion and the two-systems unification. If this design slips, do this instead |
| Sweep boundary `>=` instead of `>` | REJECTED | Would drop every same-second serve+save stamp — the whole gen cohort — for marginal extra conservatism |

## 7. Test plan

- **Unit (new):** stamp round-trip through the batcher (put-flush-load); the fan-out
  DeleteRows also kills stamp rows (and commits immediately — extend the R1-M1 pin);
  `invalidateStamps` op is stamps-only (memo survives — extend the existing memo
  twins); sweep drops `header > ts` and keeps `header <= ts` (boundary pin, both
  sides); vanished-region stamp drop; stamps-mode factory (lodStore=off opens no blob
  tables, accepts no deposits); bin import-then-delete; degrade to RAM-only.
- **Unit (retired):** the scheduler suite; the save/load bin suite.
- **Soak gates:** `cold-restart-resync` (the primary — recalibrate its zero-read
  expectation per §3.4), `warm-rejoin`, `dirty-while-offline` (offline edits now
  heal through the stamp sweep too — this scenario should get STRONGER), the kill -9
  leg (stamps must recover to a consistent-as-of-last-commit state), and one full
  `soak.sh all` on both platforms before merge.
- **Checker:** `tscache.*` snapshot fields keep their meaning (RAM-side counters);
  add a `store.stamp_*` group only if diagnosis demands it — resist schema growth.

## 8. Phase 2 (follow-on): the CLIENT cache is the same shape, simpler

`ColumnCacheStore` (client) is per-server-per-dimension stamp files under
`config/lss/cache/` — whole-map snapshot saves (temp + atomic rename), hand-rolled
corruption/plausibility/oversize guards, a 2M-entry cap, and PATH-SANITIZED filenames
derived from server addresses. It should become the second consumer of this design's
stamps machinery, as a follow-on phase once the server side proves the pattern:

- One `lss-client-cache.db` with rows keyed `(server, dim, pos) → ts`; the meta
  version row replaces the v4-header discard dance; WAL replaces the corruption
  guards; keyed rows DELETE the path-sanitization surface outright; recency eviction
  replaces the per-file cap AND prunes dead servers' stamps (today: unbounded file
  accumulation across visited servers).
- Strictly simpler than the server side: **no sweep** (the client cannot verify
  freshness locally — that is the server's job via re-declaration), no fan-out, no
  blob tier. RAM stays authoritative at runtime exactly as today (loaded at join).
- The win is durability for the crash-prone population: a client crash today loses
  everything since the last snapshot and re-declares `-1` — a full re-download of
  terrain it already had (expensive at 256 distance). Deltas shrink that to ~one
  flush interval. NOTE the one thing this does NOT fix: cache-vs-consumer divergence
  (LSS stamps claiming data Voxy's own store lost) is `reportIngestFailure`'s
  domain, identical in any container.
- Risk profile: the client resync path has THIN automated coverage (one Tier 3 e2e +
  the soak client) — the refactor risk lives there, not in sqlite. Extract the
  minimal stamps-writer as a shared `common/store` piece during Phase 1 so the
  client phase reuses proven code rather than re-implementing it. The sqlite native
  already ships in the (client+server) Fabric jar; load it lazily on first LSS-server
  join. `/lss clearcache` covers the lost delete-one-server's-file affordance.

## 9. Risks (amended by the plan-review round)

- The batcher becomes load-bearing for protocol state on ALL servers. Its failure
  latch must degrade stamps to RAM-only, never wedge serving — pinned in stamps-mode
  tests, AND the degraded state carries a persistent diag token (R1's answer to old
  open decision 2: keep `store=off` untouched and add an independent `stamps=sqlite`
  / `stamps=ram` / `stamps=ram(latched)` token on the same diag line — a golden
  VALUE update, not a line-structure change, on both platforms + both exporters).
- Boot load: the table is loaded WHOLE (no ORDER BY/LIMIT — no ts index; R3+R1
  M-F). Its size is bounded by content-age trimming folded into the sweep pass that
  already scans the table (NOT called "recency" — `ts` is content time, and access
  recency is deliberately never persisted).
- `Op.DropAll` (the `/lsslod store invalidate all` lever) deliberately does NOT
  touch stamps (R1 MINOR-2): both services document "the tscache is deliberately
  untouched — stamps describe REGION truth, not store contents", and silently
  converting a store re-warm lever into a force-every-client-full-resync hammer
  contradicts the command's advertised contract. If an admin stamps-wipe lever is
  ever wanted, it is a NEW verb.
- Soak-checker collateral (R1 MINOR-4): `store.sweep_drops`/`store.errors` are
  documented all-zero-while-off (the kill-switch A/B arm shape); stamps activity
  needs its own counters or that contract comment rewritten — and `store_gate.sh`'s
  off arm stops being a no-SQLite control for future A/Bs.
- Paper `/reload` exposes every server to two SQLite writers on one file (the hazard
  the bin file's unique-tmp naming existed for) — the disable/enable ordering must
  be verified in the stamps-mode lifecycle tests.
- WAL churn from stamp deltas is negligible (tiny rows, batched, ~2 s cadence), but
  checkpoint/size-cap accounting must EXCLUDE stamps-mode DBs from blob-tier
  eviction logic entirely.
- Schedule risk: re-opens soak-validated ground for a hole never yet observed live —
  why this is a doc, not a branch, and why the write path ships separately from any
  sweep rung (§3.4).

## 10. Plan-review round record (3× Opus, 2026-08-01)

Three reviewers over this doc + the implementation plan; all findings verified
against code, the decisive one against real DATA. Dispositions folded above:
- **R3 (architecture):** B1 load/sweep divergence (→ §3.3a), B2 meta partition
  (→ §3.5), M1 gauge isolation, M2 StampsStore handle split, M3 Folia decision
  forcing, M7 degrade honesty, structural recommendation (Phase 0 standalone,
  client phase split, bake gate) — adopted in the implementation plan v2.
- **R1 (server semantics):** B-1 MEASURED the `header > ts` sweep at 68%/96%/20%
  stamp loss per restart on three real worlds and proved the store-hit mitigation
  void by construction (→ §3.4 rejected-as-specified; zero-cost rungs + the
  chash-suspect option remain); B-2 the tscache is thread-confined — R3's fix
  directions were data races (→ §3.3a mailbox routing); B-3 migration-before-sweep
  ordering (→ §3.6); M-A..M-F + minors folded throughout. Verdict: the WRITE path
  is sound and closes ~99% of the seal at near-zero cost; the sweep half is where
  the danger lived.
- **R2 (client/testability):** B1 the planned contract suite encoded eviction
  semantics the client code does not have (distance-from-center, not
  newest-retained; `ts` is content time); B2 delta-flush inverts crash HONESTY
  (over-claim: a stamp flushed before its consumer rejection + crash = permanent
  hole) → flush-eligibility rule required; B3 the seam is statics-with-external-
  callers (holder + defaulted setter, NOT ctor args; the soak client's pre-halt
  flush must ride the holder); plus the FIFO-ordering contract group, migration
  direction (lazy import feasible, scan-all impossible — sanitization is lossy;
  mark-imported instead of delete), and per-file-vs-global eviction honesty — all
  folded into the implementation plan's Phase 0/2 sections.
