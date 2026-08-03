# LOD Store — implementation plan (v2, 2026-07-30, post 3-Opus review)

Compiles: the serve-path brainstorm (`serve-path-efficiency-brainstorm.md`), its reviews,
the Distant Horizons review (§7 there), and the `before-loop` archaeology (§8 there).
v1 of this plan was reviewed by three Opus agents (integration-correctness,
perf-methodology/DB-engineering, ops/product/risk); v2 incorporates all accepted
findings — §9 records the headline corrections so v1's mistakes aren't re-made.

## 0. Goal, common path, and the prime directive

**The common path (user-defined, what every gate measures):** a player joins a server
whose store is already populated for everything they will ask for. Their ~800-want
closest-first declarations serve from store hits — no chunk NBT parse, no region read,
no serialization — at materially lower CPU per column and latency, with gameplay
unharmed.

**Prime directive (the `before-loop` lesson):** every piece of complexity must buy a
measured improvement on the common path or it does not ship — and every phase gate must
be FALSIFIABLE (a gate that cannot fail is accretion with paperwork). Two structural
safeties: the store is DERIVED data (deleting the DB is always safe — corruption,
version bumps, and mask changes are all "drop and rebuild", never migrate), and every
phase lands behind the `lodStore` kill switch so gates run as same-session A/B.

**Success metrics — the three-part gate** (v1's single "≥60% whole-JVM CPU cut" was
arithmetically unreachable: LSS-attributable CPU is ~44% of the JVM on the vanilla arm
and serving is bandwidth-capped, so vanilla tick is a fixed floor; round 1 of the
profile work already showed band-level −23–30% collapsing to −5–7% whole-JVM):

1. **Work elimination (primary, noise-immune):** on the warm-join scenario,
   `store.hits/(hits+misses) ≥ 0.95`, `disk.submitted ≈ 0`, and the JFR NBT-parse +
   serialization bands ≈ 0 samples. This is the proof a store hit does no chunk work.
2. **Quantitative:** LSS-band-attributed CPU per column (exec-sample share of
   `dev.vox.lss.*` + `net.minecraft.nbt.*` + inflate + `org.sqlite.*` frames ×
   idle-corrected JVM CPU ÷ columns) — target **≥ 70% reduction** vs the NBT path.
3. **Non-regression:** whole-JVM CPU-s/1k (expected band ~−35–50%, stated so a −40%
   isn't misread as failure), `time_to_convergence` (bandwidth-capped — informational,
   not a gate), p95 serve latency ≥ 5× better on hits, MSPT within noise as measured by
   the A/B pairing (not a fixed ±5% — sub-noise thresholds are unfalsifiable).

Plus a **cold-path non-regression gate** v1 lacked: deposits add deflate/hash work to
every NBT serve; a `no-cache` run with deposits ON vs OFF must regress CPU/1k ≤ 10%.

## 1. Architecture

New package `common/store/`. All store access from LSS-owned threads only (reader pool,
processing thread enqueues, batcher thread, backfill thread) — never the server thread
or a Folia region thread.

### The rung contract (corrected)

The store rung lives inside the disk reader's submitted operation, BUT v1's "counters
and `ReadOperation` stay untouched" was wrong on four counts, so the real contract is:

- `ReadOperation` widens to return `(bytes, columnTimestamp, source)` — today it
  returns bare `byte[]` and `readAndDeliver` stamps `epochSeconds()` itself; a hit must
  serve its STORED timestamp (never fabricate a newer stamp — delivery-honesty), so the
  envelope must carry it. `ChunkReadResult` gains the source tag.
- **Store hits get their own counters** (`store.hits`, `store.read_latency_*`) and are
  EXCLUDED from `disk.submitted/successful/avg_read_ms` and — critically — from
  `AdaptiveReadThrottle.recordLatency` (sub-100 µs hits would collapse the AIMD EWMA
  and blow the limit wide open on exactly the C2ME-latched servers where the throttle
  is the only gameplay protection; the base class comment already bans no-IO samples).
- **All-air normalization:** the NBT path returns `byte[0]` for all-air, the live/gen
  paths return `null`, and the read side treats `null` as authoritative-not-found
  (which seeds the miss memo!). The store boundary speaks `byte[0]`-means-all-air,
  NEVER null; deposits from live/gen sources normalize before enqueue. All-air columns
  ARE stored (a row with `usize=0`) — otherwise every warm all-air column re-reads NBT.
- The store holds **section bytes only** (never a pre-built payload) — this is the
  invariant that keeps the v16 shim's source-byte strip and the VSS pair checks safe.
- Throughput honesty: the rung sits behind the duplicate/timestamp/probe rungs and
  still takes a SYNC slot + reader-pool thread; loaded chunks never reach it (the probe
  wins). Per-column CPU and latency improve; aggregate convergence rate does not
  (bandwidth-capped) — stated so nobody gates on it.

### Deposits ride the delivery path, not the reader (corrected — stale-poisoning fix)

v1 deposited inside the read operation; the review showed an edit-overtaking-an-in-
flight-read would then poison the store: the stale guard
(`consumeInvalidatedInFlight`) suppresses the tscache stamp at DELIVERY, after the
reader already deposited pre-edit bytes — next declaration store-hits them, stamps
them, sealed. Therefore: **deposits happen on the processing thread's drain, after the
stale-guard check passes**, as an enqueue to the write batcher (the actual SQLite write
stays on the batcher thread). Same choke point for disk, generation, and (Phase 3)
save-hook deposits. Batcher dedup is latest-wins **by stored timestamp**, not arrival
order (a slow serve-time deposit must not overwrite a newer save-hook deposit).

### Invalidation fan-out (complete list — v1 missed two)

Store delete/invalidate must hook every place the timestamp economy invalidates:
`applyInvalidations` (dirty/edit path — also taints in-flight), the **not-found ghost
guard** (`invalidateStamps` when disk says a previously-served chunk no longer exists —
the store row must be DELETED or the store re-serves deleted terrain forever), the
shutdown/exit flushes, and (Phase 4+) vanished region files. Ordering: the tscache
invalidation applies synchronously on the processing thread; the store delete is an
async batcher op — the window where tscache is empty but the row still exists is closed
by the freshness check on the hit path (a SUSPECT/stale row falls through), not by
synchronous deletes.

### Memory tier = disposable integration spike (reframed)

Phase 1 builds the bounded in-memory tier primarily to prove the rung/counters/parity
plumbing before SQLite exists. Honesty from the review: the warm-join scenario has NO
repeat serves within one run (each position serves once; `DedupTracker` collapses
concurrent repeats), plain LRU is scan-pathological for closest-first re-scans over a
1.2 GB disc with a 64 MB cap (hit rate ≈ 0 by construction), and on Paper the tier
carries the same unfired-event staleness class as the disk store (bounded only by
uptime). So: entries are stored COMPRESSED, eviction is scan-resistant (SLRU/2Q or
random — measured), its gate is a dedicated second-join scenario, and **Phase 2 runs a
memory-vs-SQLite-alone A/B with "delete the tier" as an explicitly permitted outcome**
(SQLite through OS page cache + a warm point read may make the tier redundant).

### Compression: UNDECIDED until Phase 0 (v1 pre-committed wrongly)

v1 picked deflate-1 and claimed 10–30 µs inflate; inflate scales with OUTPUT (~33 KB,
~70–200 µs at Inflater's 200–500 MB/s) — a material bite when the whole point is CPU.
Phase 0 measures four arms on the real 1.22 GB benchmark corpus: **uncompressed**
(zero decompress CPU, ~3–4× disk), **deflate-1** (no dep), **zstd-1**, **LZ4** (both
need packaging spikes) — the deciding metric is DECOMPRESS THROUGHPUT and bytes/col,
not ratio alone. Note `before-loop` served its blobs verbatim (zero decompress);
verbatim-serve here is the deferred v19 item, which strengthens the
cheap-decompress/uncompressed arms.

### Population

1. **Save-hook write-through (Fabric, Phase 3) — the AUTHORITATIVE Fabric path.**
   `DirtyContentFilter.contentChanged` already serializes full wire bytes on every
   save; refactor hands bytes to a callback, deposit enqueued OUTSIDE the synchronized
   monitor (hook may run off-main under C2ME/Moonrise — the batcher queue is the
   thread boundary). Caveat recorded: hook bytes are the LIVE chunk at copyOf-time
   (can be fresher than the region file until the flush lands) — acceptable, they are
   valid current bytes and carry the save's own timestamp.
2. **Serve-time write-through (both platforms, Phase 2):** every successful NBT read
   and generation serve deposits at the delivery choke point. Paper's primary path;
   also captures generated terrain (warm for every later session).
3. **Background backfill (Phase 4, default OFF — opt-in via command/config):**
   single MIN_PRIORITY thread, submits through the same `hasHeadroom` self-restraint
   as everything else + an MSPT ceiling — NOT v1's "pause while any player backlog is
   nonempty" (under v17's 1 Hz re-declaration that predicate is near-always true on a
   live server and the backfill would never finish; nominal math: 10k cols ≈ 3.3 min,
   100k ≈ 33 min, 1M ≈ 5.6 h at 50 col/s — publish observed-under-load numbers, not
   these). Traversal: regions ordered by Chebyshev distance from spawn (players
   cluster spawn-side), sequential within a region. Resumable via a per-region
   progress table (created in Phase 4, not before). On Fabric it re-enters the A7
   IOWorker mechanism — the gate pins `disk.errors == 0` on a constrained box.

### Freshness (v2 — per-column, platform-split; v1's per-region roll-up was broken)

The review killed the per-region watermark: clearing SUSPECT on any sibling deposit
masks real edits; never clearing means permanently-SUSPECT regions (the store becomes
a no-op exactly where players are); the Fabric save hook records mtime BEFORE the
IOWorker flush (permanent false-SUSPECT treadmill); backup restores move mtime
BACKWARD. v2:

- **Freshness state is per-COLUMN, on the `lods` row**: `src_stamp` = the `.mca`
  header's per-chunk timestamp (epoch seconds, maintained by vanilla `RegionFile`)
  where the deposit came from disk; save-hook/gen/serve deposits assert only their own
  column (the save/serve event IS the freshness evidence).
- **Startup sweep (both platforms):** stat region files, compare against a per-region
  `seen_mtime` using **`!=`, not `>`** (backup restores go backward). A changed region
  triggers one 4 KiB header read; per-column `src_stamp` comparison marks only
  actually-changed columns stale. **A vanished region file drops all its rows**
  (otherwise the store hit intercepts the miss that would have triggered regeneration
  of deliberately-deleted chunks — found by review, nasty).
- **Runtime (Paper's stale bound — the platform that needs it):** a periodic re-sweep
  (default every autosave interval, config) re-runs the mtime/header pass off-thread.
  This makes Paper's unfired-event stale window ≈ one autosave + one sweep cycle,
  honestly stated. Fabric is save-hook-authoritative at runtime; the sweep matters
  there only at startup (offline edits).
- **Escalation honesty:** the header-timestamp mechanism is a SPATIAL granularity fix
  (per chunk), not temporal (1 s epoch, same as mtime); and rewritten chunk IO (C2ME)
  is not verified to maintain the header table — Phase 0 verifies per-platform, and an
  unverifiable writer degrades that world to startup-sweep-only + serve/save deposits
  (fail-safe: more NBT reads, never stale serves).
- Deliberately NOT built: per-hit NBT `LastUpdate` reads, TTLs, any re-read
  verification pipeline (the `DirtyHashChecker` failure mode).

### Masking, versioning, containment

- Mask fingerprint is **per-dimension** (masking is per-world/dimension —
  `XrayMaskManager.byDimension`; v1's single per-DB row was wrong on Fabric where one
  save spans three dimensions). Mismatch → drop that dimension's rows.
- `meta`: `schema_version`, `wire_format_version`, `mc_version`. Mismatch → drop and
  rebuild. Never migrate. Deleting a corrupt DB deletes `-wal`/`-shm` too.
- Containment catches **`Throwable`**, not Exception: `sqlite-jdbc` throws
  `UnsatisfiedLinkError` on `noexec /tmp` (set `org.sqlite.tmpdir` to the world
  folder); NFS/CIFS world dirs can fail WAL shm (`SQLITE_IOERR_SHMOPEN`) → clean latch
  to store-off (documented), not a boot stack trace. `SQLITE_FULL` → bounded batcher
  queue sheds oldest (counted `store.deposit_drops`), latch if persistent. Log budget:
  warn-once latches + throttled `+N more` aggregation; per-hit/per-deposit logging
  banned.
- Config: `lodStore`: `"off" | "memory" | "full"`, default `off`; unknown values
  normalize to **`off`** (the safe value — pinned, like `xrayObfuscation`'s normalize
  but safe-biased). `lodStoreMaxSizeMB` default **nonzero** (4096) once eviction
  exists — v1's 0=unlimited default meant the eviction code would never run in
  production (unexercised complexity, and §4's disk numbers make unlimited a real
  operational surprise). **[SUPERSEDED 2026-07-31, user decision: the default is now
  0 = uncapped after live evidence that a capped default treadmills a pregenerated
  world — see store-cap-behavior-plan.md; the nonzero-default rationale above is
  historical. The eviction code stays exercised by the opt-in cap + its unit pins.]**

## 2. Schema (v2 — rowid tables; v1's WITHOUT ROWID on `lods` was the anti-pattern)

WITHOUT ROWID tables use INDEX payload limits (~2 KB inline at 8 KB pages) — an
8–12 KB blob always spills to overflow chains and fattens interior cells. Instead:

```sql
PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;   -- NORMAL+WAL: power loss may
  -- lose recent commits, can never corrupt; fine for derived data. Do not "harden".
-- page_size: Phase 0 experiment (8k vs 16k), decided by dbstat page-reads/lookup
-- and bytes-on-disk per column. mmap_size: Phase 0 A/B (mmap SIGBUS on IO error is
-- uncatchable — plain pread through page cache is the safe default unless mmap
-- measurably wins).

CREATE TABLE lods_<dimId> (           -- one table per dimension:
  pos   INTEGER PRIMARY KEY,          -- packed pos IS the rowid: true clustering,
  ts    INTEGER NOT NULL,             -- no secondary index, 2-level B-tree at 1M rows
  chash INTEGER NOT NULL,
  usize INTEGER NOT NULL,             -- 0 = all-air row
  src_stamp INTEGER NOT NULL,         -- freshness evidence (see §1)
  blob  BLOB NOT NULL
);
CREATE TABLE dims (id INTEGER PRIMARY KEY, name TEXT UNIQUE, mask_fingerprint TEXT);
CREATE TABLE regions (dim INTEGER, rpos INTEGER, seen_mtime INTEGER,
  PRIMARY KEY (dim, rpos)) WITHOUT ROWID;   -- small rows: correct WITHOUT ROWID use
CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
-- backfill progress table: created in Phase 4 when backfill lands, not before.
```

Access: **thread-confined read-only connections** (one per accessing thread — reader
pool threads, backfill, sweep — `SQLITE_OPEN_READONLY`, `query_only=1`,
`wal_autocheckpoint=0` so readers never checkpoint), one writer on the batcher thread,
prepared statements cached per connection. No connection pool (v1's pool was overhead:
WAL readers don't block, and the caller set is fixed). Writes in ~64-row transactions;
the batcher never holds a write txn across a blocking call (WAL growth). A WAL watchdog
issues `wal_checkpoint(TRUNCATE)` above a size threshold (PASSIVE checkpoints can't
reset the WAL under continuous readers — the documented unbounded-WAL failure);
`store.wal_bytes`/`store.db_bytes`/`store.checkpoint_ms_max` counters expose it.

Point reads stay per-column (the rung has no cohort). Batched `IN (...)` reads would
require hoisting the rung into the router drain — rejected unless Phase 2 measures
per-point cost > ~10% of hit cost (>25–50 µs): fixed-arity statements only if ever.

## 3. Packaging & support lines

Phase 0 spike: sqlite-jdbc natives (~5–6 MB stripped to linux/win/mac × x64/arm64),
Fabric jar-in-jar (test knot behavior against a deliberate duplicate sqlite-jdbc from
another mod), Paper shadowJar WITHOUT relocating `org.sqlite` (relocation breaks the
native loader; per-plugin classloaders make collisions acceptable). `release_check.py`:
native-matrix check + nested-jar updates; the VSS pair checks are unaffected (verified:
wire-identity digests `dev/vox/lss/**` and the `common-*.jar` regex — `org/sqlite/**`
is invisible to both). **Fallback engine if the spike fails: region-style flat files**
behind the same `LodStoreService` interface — phases and gates are engine-agnostic.

**Support-line decision (recorded): the store is 26.2-line only.** No backport to
1.21.8/26.1/1.21.11 (support-line budget is "correct, not perfect"; this is a
new-feature surface with a native dep). Shared config files stay harmless (GSON
ignores unknown keys on old lines). The next tri-release ships the store on main only.

## 4. Disk-space estimate (v2 — v1's region-dir comparison was 10–20× off)

Measured: 33.0 KB/column uncompressed wire bytes (37k cols / 1.22 GB benchmark world);
region data on the SAME world measures **10.6 KB/chunk** (473.6 MB / 45,589 chunks) —
v1 compared against 100–300 KB/chunk folklore numbers. Estimated stored size at
deflate-1 (~3–4×): **8–12 KB/col + ~10% engine overhead** (Phase 0 replaces the 10%
guess with `dbstat` measurements).

| World scale | Columns | Store (deflate) | Store (uncompressed) | vs region dir |
|---|---|---|---|---|
| Fresh SMP | 10k | 90–140 MB | ~350 MB | **~80–130%** of region data |
| Established SMP | 100k | 0.9–1.4 GB | ~3.4 GB | **~80–130%** |
| Large/old server | 1M | 9–14 GB | ~34 GB | **~80–130%** |

**Honest framing for release notes: the store roughly doubles the world folder** (not
"adds 5–10%" — v1's error). This drives: the nonzero default size cap, compressed-only
as the likely codec outcome unless decompress CPU measures painful, backup guidance
(`lss-lod/` is derived — safe and recommended to EXCLUDE from backups; the `!=` mtime
compare in §1 is what makes restores safe), and Phase 0's real-world measurement across
≥3 donated worlds (min/median/max — one spawn disc is not a distribution; nether/
amplified/built-up worlds are the upside risk).

## 5. Phases (all gates same-session A/B via the `lodStore` switch)

Gate discipline: every gate is an alternating same-session (or same-night, interleaved
reps) A/B against the kill switch — never against numbers from a phase measured days
earlier (v1 violated its own same-night rule). Sub-noise thresholds are banned; MSPT
and CPU gates are paired-difference tests against the A/B partner run.

- **Phase 0 — gate infrastructure + de-risk (~4–5 d).** This phase is bigger than v1's
  because the review moved four things from "polish" to "gate prerequisite":
  (a) `warm-join` benchmark scenario incl. **second-client-run support** (benchmark.sh
  is single-run today) and a **cold-page-cache variant** (drop caches between arms —
  every existing number rode a 488 MB world fully in page cache; ~800 random point
  reads into a multi-GB file on cold cache is the store's worst case and could invert
  the latency gate on spinning/network disks);
  (b) **JFR band attribution** in `analyze_profile_jfr.py` (stack-prefix buckets:
  `common.store`, `Inflater`, `net.minecraft.nbt`, `org.sqlite` — thread-group
  bucketing can't separate store hits from NBT reads on the same pool);
  (c) **Paper/Folia CPU sampling** (attach `proc_sampler` to the soak harness —
  benchmark.sh is Fabric-only, and Paper is where the store matters most);
  (d) counters plumbing end-to-end with store OFF (diag token on the DiskReader line —
  a new formatter LINE breaks the golden-order tests; exporter twins; benchmark
  exporter's second site; `check_soak.py` `KNOWN_SERVER_KEYS` + required fields +
  `_srv()` literal + selftest cases; `soak_report.py` concerning/mechanism dicts —
  `store.errors` concerning, hits/misses/deposits mechanism; batcher queue depth is a
  GAUGE → `SERVER_DRAINS`, not monotonic).
  Plus: packaging spike (§3), codec arms (§1), schema shape experiment (rowid vs
  16k pages via `dbstat`), real-world bytes/col, `.mca` header-timestamp maintenance
  verification per platform (vanilla/Moonrise/C2ME). **Gate: baselines recorded;
  codec + page-size + mmap decisions made on data.**
- **Phase 1 — memory-tier integration spike (~2 d).** The rung contract (widened
  `ReadOperation`, own counters, throttle exclusion, all-air normalization), the
  delivery-path deposit choke point, invalidation fan-out (all sites incl. the ghost
  guard), parity harness. **Gate: a second-join scenario shows the tier serving with
  zero byte diffs and honest counters; scan-resistance measured (hit-rate curve vs cap
  — `store.mem_hits/evictions/bytes`). Explicit permitted outcomes: ship it, or record
  the numbers and delete it in Phase 2.**
- **Phase 2 — SQLite + freshness + serve-time deposits (~5 d, one phase deliberately:
  v1 shipped the store two phases before freshness — reachable silent-stale).**
  `LodStoreService`, thread-confined readers, batcher, containment latches, per-column
  `src_stamp`, startup sweep (incl. vanished-region row drops, `!=` compare), the
  Paper periodic re-sweep, parity pins (Tier 1 round-trip fuzz + chash; Tier 2:
  store-served `serializedSections` byte-identical to NBT-served — SECTION bytes, the
  payload differs legitimately in source byte). Memory-vs-SQLite-alone A/B (Phase 1's
  fate). **Gate: cross-restart warm-join meets §0's three-part metric; cold-path
  deposit regression ≤10%; store read p95 stable under concurrent batcher load;
  `store-offline-edit` soak green on both platforms; `store-paper-unfired-event` soak
  shows the documented ≤ autosave+sweep staleness bound — measured on an ACTIVE
  multi-player Paper run (the quiet-single-player version of this gate is
  unfalsifiable).**
- **Phase 3 — Fabric save-hook deposits (~2 d).** The `DirtyContentFilter` callback
  refactor (deposit enqueue outside the monitor). **Gate: autosave-storm soak — paired
  MSPT delta within noise, `store.deposit_drops` under a stated ceiling, post-edit
  serves are the new bytes; SUSPECT/stale-demotion rate on ACTIVE regions stays low
  (the save-hook path must keep pace with saves — this is where v1's mtime treadmill
  would have shown up).**
- **Phase 4 — backfill (~3 d, ships default-OFF).** As §1. `/lsslod store backfill
  start|stop`, progress table, distance-from-spawn ordering. **Gate: rate-under-load
  (effective col/s with an active player, not idle wall-clock), `disk.errors == 0` on
  a constrained box, resumability via mid-run kill, DB growth curve published
  (`store.db_bytes` over the run).**
- **Phase 5 — eviction, ops, burn-in (~3 d + soak time).** Size cap (nonzero default)
  + oldest-`ts` batch eviction + `incremental_vacuum`; `/lsslod store` verbs:
  `status | invalidate <all|dim|radius> | rebuild | backfill start|stop` (the admin
  remediation lever for "LODs look stale" — v1 had none); store size in diag/exporter;
  release notes (disk table §4, backup guidance, plus the standing backlog: #70
  Moonrise retarget, #73 `enableIngestBackpressure`, #74 transcode +
  `sendQueueLimitPerPlayer` 4000→1024, #75 Moonrise reads — and note the stale local
  `v0.8.2` tag predates #73–#75: delete/re-cut it, never push it). Full
  `soak.sh all` on all three platforms with `lodStore=full`. **Gate: all scenarios
  green; two-player warm variant measured (the store's honest multi-player value is
  time-separated repeats — concurrent overlap was already free via `DedupTracker`);
  then the default flips to `full` in a MINOR release.**

Deferred, unchanged from v1: B+A-full (cold path/backfill throughput), blob-verbatim
wire + batched frames (v19), section dedup (D), detail tiers. Total ≈ 4 wk — double
the brainstorm's C estimate, priced by review findings, named honestly.

## 6. Risk register (v2)

| Risk | Mitigation |
|---|---|
| Silent stale LOD | Per-column freshness + `!=` sweeps + Paper periodic re-sweep + vanished-region drops; two dedicated soaks incl. active-Paper; residual bound stated in docs (Paper ≈ autosave+sweep) |
| Store blocks chunk-regeneration workflows | Vanished-region row drops + `store invalidate <radius>` |
| Edit-overtaken deposit poisons store | Deposits on the delivery path AFTER the stale guard; ghost-guard row deletes |
| Throttle EWMA poisoning | Store hits excluded from latency sampling (unit-pinned) |
| All-air → memoized authoritative miss | `byte[0]` normalization at the store boundary (unit-pinned) |
| SQLite native/packaging fragility | Phase 0 spike; `Throwable` containment; `org.sqlite.tmpdir`; NFS latch; flat-file fallback engine |
| Disk-space surprise | §4 honest doubling numbers; nonzero cap default; backfill default-off; backup-exclusion guidance |
| WAL unbounded growth / checkpoint stalls | TRUNCATE watchdog + `store.wal_bytes` + gate under concurrent load |
| Backfill starves gameplay / A7 re-entry | `hasHeadroom` + MSPT ceiling (not backlog-emptiness); constrained-box gate `disk.errors == 0` |
| Complexity accretion | Falsifiable gates; delete-the-tier as a permitted outcome; schema elements created in the phase that uses them |
| Support-line drift | Recorded: 26.2-only |

## 7. Review round (2026-07-30, 3× Opus)

Headline corrections v2 absorbed — kept here so they don't regress: the §0 metric was
arithmetically unreachable (whole-JVM target above the LSS-attributable ceiling) →
three-part work-elimination gate; `ReadOperation`/counters/throttle contract was wrong
(no ts channel; hits polluted disk counters and the AIMD EWMA); reader-side deposits
could store edit-overtaken bytes (move to delivery path, after the stale guard);
invalidation fan-out missed the not-found ghost guard and shutdown flushes; all-air
sentinel split (`byte[0]` vs `null`) would memoize authoritative misses; per-region
mtime watermarks were structurally broken (sibling-deposit masking, save-hook
pre-flush treadmill, backup-restore `>` bug, vanished regions blocking regen) →
per-column `src_stamp` + platform split; `WITHOUT ROWID` on 8–12 KB blobs is the
documented anti-pattern → per-dimension rowid tables; the read pool was overhead →
thread-confined connections; inflate estimate was output-scaled ~70–200 µs not
10–30 µs → codec undecided, uncompressed arm added; §4's region-dir comparison was
10–20× off (measured 10.6 KB/chunk) → "roughly doubles the world folder" framing +
nonzero cap default; backfill's pause predicate never converged on live servers →
`hasHeadroom`/MSPT duty cycle, default-off; mask fingerprint per-dimension; memory
tier reframed as a disposable spike with scan-resistant eviction; support-line
decision recorded; containment widened to `Throwable`; gates made falsifiable
(same-session A/B, active-Paper freshness gate, paired-difference MSPT); benchmark
harness gaps (second client run, Paper sampling, JFR store band, cold page cache)
moved into Phase 0 as gate prerequisites.
