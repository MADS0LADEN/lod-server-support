# LOD Store — implementation progress (branch feat/lod-store)

Running log for the implementation of `lod-store-implementation-plan.md` (v2). One section
per phase; each records what landed, gate evidence, learnings, and deviations from the plan.
2-subagent review between phases (user-directed cadence).

Started 2026-07-30.

## Ground rules pulled from the plan (working checklist)

- Store is DERIVED data: drop-and-rebuild, never migrate. Every phase behind `lodStore`
  kill switch (`off|memory|full`, default `off`, unknown → `off`).
- Store hits must NOT feed `disk.*` counters or `AdaptiveReadThrottle.recordLatency`.
- Store boundary speaks `byte[0]`-means-all-air, never null. All-air IS stored (usize=0).
- Hit serves its STORED timestamp — never fabricate `epochSeconds()`.
- Deposits ride the delivery path AFTER the stale guard (`consumeInvalidatedInFlight` /
  `genStale`), enqueued to the batcher; batcher dedup latest-wins by STORED ts.
- Invalidation fan-out: `applyInvalidations`, the not-found ghost guard
  (`invalidateStamps` site in `drainDiskResultsForAllPlayers`), shutdown flushes,
  vanished region files (Phase 4+ → actually Phase 2 startup sweep drops vanished regions).
- Store rung sits behind duplicate/timestamp/probe rungs, takes SYNC slot + reader pool
  thread (it lives inside the submitted read operation).
- Containment catches Throwable; `org.sqlite.tmpdir` → world folder; warn-once latches.
- Gates are same-session A/B against the kill switch; sub-noise thresholds banned.

## Codebase map (established while grounding, 2026-07-30)

- Read pipeline: `IncomingRequestRouter.tryAdmitAndSubmit` (SYNC slot + dedup + headroom
  gate) → platform `submitReadDirect` → `AbstractChunkDiskReader.submitRead(op)` →
  `readAndDeliver` (triage: error/timeout → notFoundFromError; null → authoritative miss;
  byte[0] → all-air; else data + `epochSeconds()` stamp) → per-player result queue →
  `OffThreadProcessor.drainDiskResultsForAllPlayers` (stale guard
  `consumeInvalidatedInFlight`, tscache put / `invalidateStamps` ghost guard, dedup
  fan-out) → `deliverDiskResult` → `buildAndEnqueueColumnPayload`.
- ReadOperation today: `byte[] read()` — no ts channel, no source. Widening lands in
  Phase 1 (plan §1 rung contract).
- Delivery-path deposit choke points (Phase 1/2): the non-stale branches of
  `deliverDiskResult` (disk) and `processGenerationOutcome` (gen) — exactly where
  `markDiskReadDone` + tscache stamping already happen.
- Generation stale guard is per-player (`generationInFlight`/`generationStale`); disk
  stale guard is per-dimension via dedup group (`invalidatedInFlight`).
- `DiskReaderDiagnostics.formatDiagnostics` renders the DiskReader diag line;
  `DiagnosticsFormatter.collectDiagData` appends `, memo_hits=%d` — the store token
  appends to the SAME line (a new LINE breaks golden-order tests).
- Benchmark harness: `scripts/benchmark.sh` (fresh|no-cache, single client run),
  `scripts/profile_disk_read.sh` (JFR + 1 Hz proc_sampler cpu.jsonl, arms + reps,
  meta.json arm-validity), `scripts/analyze_profile_jfr.py` (window from wire slope;
  thread/self-method attribution — NO band attribution yet, Phase 0 adds it).
- Config: `ServerConfigBase` (common, shared verbatim by Fabric+Paper) + clamps in
  `validate()`; constants in `LSSConstants`.

## Phase 0 — gate infrastructure + de-risk

Status: IN PROGRESS (started 2026-07-30)

Planned sub-items (from plan §5):
- (a) warm-join benchmark scenario: second-client-run support + cold-page-cache variant
- (b) JFR band attribution in analyze_profile_jfr.py (stack-prefix buckets)
- (c) Paper/Folia CPU sampling (proc_sampler on the soak harness)
- (d) counters plumbing end-to-end with store OFF (diag token, exporter twins, benchmark
  exporter, check_soak.py keys/required/selftest, soak_report.py dicts, gauge handling)
- packaging spike (sqlite-jdbc natives, Fabric jar-in-jar, Paper shadowJar no-relocate,
  release_check.py native-matrix)
- codec arms on the real corpus (uncompressed / deflate-1 / zstd-1 / LZ4 — decide on
  DECOMPRESS throughput + bytes/col)
- schema shape experiment (rowid vs WITHOUT ROWID, 8k vs 16k pages, dbstat)
- real-world bytes/col measurement
- .mca header-timestamp maintenance verification per platform (vanilla/Moonrise/C2ME)

### Experiment results (LodStoreExperimentTool, 2026-07-30, benchmark world)

Tool: `fabric/src/test/java/dev/vox/lss/networking/server/LodStoreExperimentTool.java`
(gated on `-Plss.store.experiment.regionDir`); results in
`profile-results/store-experiment/store-experiment.json`. Corpus: benchmark world
overworld, 64 regions / 474 MB region data, 55,678 header entries, 45,589 FULL-servable
columns (10,089 not-FULL excluded, 0 all-air, 0 unparseable). Wire bytes/col mean 33.8 KB
(p50 33.0 KB, p99 54.7 KB) — matches plan §4's 33.0 KB. Production transcode serialize:
63 µs/col mean warm.

| codec | ratio | B/col | compress µs | decompress µs | decompress MB/s |
|---|---|---|---|---|---|
| deflate-1 | 5.90 | 5732 | 162 | 60 | 563 |
| deflate-6 | 7.08 | 4776 | 574 | 63 | 534 |
| zstd-1 | 6.33 | 5342 | 50 | 24 | 1388 |
| lz4-fast | 3.98 | 8490 | 67 | 37 | 905 |

SQLite arms (42,589 data + 3,000 skipped... all 45,589 rows inserted; point reads warm
page cache, n=3000):

| arm | file | overflow pages | read mean µs | read p95 µs |
|---|---|---|---|---|
| rowid-8k-deflate1 | 358 MB | 3,976 | 5.0 | 7.5 |
| rowid-16k-deflate1 | 337 MB | 0 | 5.1 | 9.4 |
| rowid-8k-uncompressed | 1.62 GB | 167,607 | 11.6 | 17.2 |
| worowid-8k-deflate1 | 435 MB | 46,168 | 22.5 | 29.5 |
| rowid-8k-deflate1 + mmap 1G | — | — | 5.5 | 9.3 |

### Phase 0 decisions (made on the data above)

1. **Codec: zstd-1**, contingent on the packaging spike (zstd-jni natives) — it dominates
   deflate-1 on BOTH deciding metrics (24 vs 60 µs decompress, 5342 vs 5732 B/col) and
   compresses 3× faster (deposit-side cost, protects the ≤10% cold-path gate).
   **Fallback: deflate-1** (zero-dep JDK) if zstd packaging fails; the DB `meta` table
   records the codec so a switch is drop-and-rebuild, never a migration. Uncompressed
   rejected: total hit CPU is already ~30 µs at zstd (~40× under the NBT path), and 3.4×
   region-dir disk (1.62 GB vs 474 MB here) is operationally worse than the accepted
   "roughly doubles". deflate-6/lz4 rejected (compress cost / dominated on both metrics).
2. **page_size=16384** — zero overflow chains at compressed blob sizes (8k had 3,976),
   3% smaller file, equal read latency. (p99 blob ~15 KB still fits a 16k page.)
3. **WITHOUT ROWID confirmed as the anti-pattern** (4.5× slower point reads, +29% size,
   46k overflow pages) — rowid tables pinned, as plan §2 said.
4. **mmap: OFF** (no measurable win over pread; SIGBUS risk not bought by anything).
5. **Vanilla .mca header timestamps: MAINTAINED** (55,678/55,678 nonzero on this
   vanilla-written world) — the §1 freshness mechanism is real on vanilla Fabric.
   Moonrise/C2ME verification still pending (needs worlds written by those systems).

### Infrastructure landed (2026-07-30)

- **(d) Counters plumbed end-to-end, store OFF** (commit 027ac5f): `common/store/`
  package born with `LodStoreMode` (off|memory|full, unknown→off safe-biased, pinned by
  `LodStoreModeTest`) + `LodStoreDiagnostics` (AtomicLong family — the store's OWN
  counters, never `disk.*`, never the throttle EWMA). `lodStore` config key. Diag TOKEN
  on the DiskReader line (`store=off` / `store=<mode> h=… m=…`). Store group in both
  exporter twins + the benchmark server.json second site + server-snapshot.contract.
  check_soak: 7 monotonic counters, `store.queue` a strict SERVER_DRAIN, `_srv` fixture,
  `lodStore` config key, 148 selftest cases. soak_report: errors/deposit_drops
  concerning, hits/misses/deposits/mem_hits mechanism, byte gauges in HIGH_WATER,
  DRAIN_GAUGES hand-sync. All test tiers green (fabric T1, paper T1 322, T2 59).
- **(b) JFR band attribution** in analyze_profile_jfr.py: leaf-first stack-prefix bands
  (store / zip / nbt / serialize / lss-other), `bands.json` per run for gate math +
  report section. Baseline on the 2026-07-29 vanilla no-cache JFR: nbt 19.4%,
  serialize 48.9%, zip 0.5%, lss-other 2.5% → lss_attributed 71.3% of exec samples —
  the serialize+nbt ≈ 68% is exactly what §0 metric 1 expects a warm store hit to zero.
- **(a) warm-join benchmark scenario**: benchmark.sh restructured around `run_cycle`;
  `warm-join` = cycle A (populate, base-world copy) + cycle B (measure: server RESTART
  on the same world — the store DB rides the world folder — fresh client cache).
  `BENCHMARK_DROP_CACHES=1` drops the OS page cache before the measure cycle
  (passwordless-sudo best-effort, recorded in warm-join-meta.json). Phase 1's
  SAME-SESSION second-join gate deliberately rides the soak harness instead (its
  two-client-run machinery + 5 s JSONL snapshots give run-2 deltas for free;
  benchmark.sh measures the cross-restart shape that Phase 2 gates on).
- **(c) Paper/Folia CPU sampling**: proc_sampler server-discovery pattern is now
  overridable (`PROC_SAMPLER_SRV_PATTERN`); soak.sh attaches it on every platform via
  `-Dlss.soak.scenario`, writing `cpu.jsonl` next to the run's JSONL.
- **Packaging spike: PASSED.** Fabric: `slimStoreDepJars` repacks sqlite-jdbc + zstd-jni
  native-stripped (linux/win/mac × x64/arm64 + Linux-Musl for sqlite) with a generated
  minimal fabric.mod.json each (nested jars must be mods), nested via META-INF/jars +
  hand-declared `"jars"` entries (version-free file names so the descriptor never chases
  version bumps; Loom MERGES its common entry into the array — verified in the built
  jar). Release jar 330 KB → 7.6 MB (sqlite-slim 5.0 MB, zstd-slim 2.3 MB). Paper:
  plain `implementation` deps + a shared native-strip exclude closure on both shadow
  tasks, org.sqlite deliberately NOT relocated; 7.5 MB. release_check.py gained
  `check_store_natives_fabric/paper` (8 sqlite natives by exact path, 6 zstd native
  dirs, nested-jar declaration check, relocation guard) + synthetic fixtures + 3
  selftest cases (55 total). VSS byte-copy pair checks unaffected (verified green).
- **.mca header-timestamp verification: ALL THREE WRITERS PASS.**
  `scripts/mca_timestamps.py` (scan + compare modes). Vanilla: 55,678/55,678 nonzero
  header stamps on the base world. C2ME (c2me-fabric nvOkOiyi, chunkio rewrite active —
  fallback warning present in the run log): 625 chunk stamps ADVANCED across a 90 s
  no-cache run's metadata re-saves, 0 backward. Moonrise (moonrise-opt W0HImEBl, same
  run shape): 625 advanced, 0 backward. The §1 per-column `src_stamp` freshness
  mechanism is real on every chunk system we target — no world degrades to
  startup-sweep-only on stamp-maintenance grounds.
- **Bytes/col distribution (plan §4 wants min/median/max, ≥3 worlds — we have the two
  extremes locally):** superflat soak world = 6,171 B/col wire (uniform), zstd-1
  **59 B/col** (ratio 105×); normal-terrain benchmark world = 33.8 KB/col wire, zstd-1
  5,342 B/col. Amplified-terrain arm (via new env knobs `BENCHMARK_EXTRA_SERVER_PROPS` +
  `BENCHMARK_NO_BASE_SAVE=1`; 625 servable cols, 125 timed): 32.1 KB/col wire, zstd-1
  4,586 B/col — slightly BELOW normal terrain (the tall band culls more air sections),
  so normal terrain is the measured per-column maximum, not amplified. Distribution at
  zstd-1 across the arms: 59 / 4,586 / 5,342 B/col. True donated player worlds
  (built-up/modded — the §4 upside risk) remain unavailable in this environment —
  recorded as a caveat for the release-notes disk table.
- **warm-join smoke (store off): PASS.** Both cycles ran end-to-end (populate 25.1k
  disk-read serves, measure 28.5k — cycle B's fresh-cache client honestly re-resolved
  everything, exactly the serve pattern the store converts to hits), artifacts landed as
  designed (server-populate.json / server.json / warm-join-meta.json), and the `store`
  group flows through the benchmark exporter (all zeros, store off).

### Phase 0 review round (2 subagents, 2026-07-30) — methodology reviewer findings

Verdict: decisions genuinely made on data, deviations explicit; gate claimable once the
"last mile" closed. Dispositions:
- **MAJOR-1 (gate math unwired) — FIXED**: benchmark.sh now attaches proc_sampler per
  cycle (cpu.jsonl / cpu-populate.jsonl); new `scripts/store_gate.sh` (interleaved
  off/on kill-switch reps, warm|cold modes, config staging, per-arm collection) + 
  `scripts/store_gate_check.py` (the §0 calculator: hit-ratio floor 0.95,
  disk.submitted ≤2% of cols, nbt+serialize bands ≤1%, band-CPU/col cut ≥70% paired,
  whole-JVM CPU-s/1k informational, hit-p95 ≥5× vs off-arm MEAN read (stricter than
  p95-vs-p95), cold-mode ≤10% regression + a deposits>0 arm-validity check; 7 selftest
  cases; gate-verdict.json). MSPT pairing deliberately rides the soak snapshots
  (mspt_avg_window), not the benchmark.
- **MINOR-1 (p95 under-delivered) — FIXED**: `store.read_p95_us` added now (512-entry
  recent-hit ring in LodStoreDiagnostics) through all schema sites before they ossify.
- **MINOR-2 — recorded**: the SQLite arms used deflate-1 blobs; the 16k zero-overflow
  conclusion holds a fortiori for the smaller zstd-1 blobs. Honest latency quote: means
  equal (5.02 vs 5.11 µs) but 16k's warm p95 is ~26% worse (7.48→9.44 µs) — immaterial
  beside the 24 µs decompress, but it is a trade, not a free win.
- **MINOR-3 — recorded in the checker itself**: the `zip` band is part-vanilla
  (connection-level packet deflate), reported but never gated on.
- **MINOR-6 — standing constraint**: nothing releases from this branch state (natives
  ship ahead of the store code — an honest spike of the real ship path); taking the
  flat-file fallback engine later means INVERTING the release_check native gates.
- **MINOR-7 — done**: evidence committed; vanilla header-ADVANCEMENT evidence is the
  base world's own stamp range (14:25:05..14:36:51 — stamps advanced across the
  11-minute generation run that created it), so the three-writer table is symmetric
  without another run. Plan §2 note: the `meta` table gains a `codec` key (decision 1's
  drop-and-rebuild lever) — implement in Phase 2.
- MINOR-4 (micro-bench nits) accepted as direction-safe; MINOR-5 (≥3 worlds) stays a
  documented §4 caveat.

Correctness reviewer verdict: "sound to build Phase 1 on" — every schema surface carries
the same store family consistently; packaging verified in the built artifacts incl. VSS
pair; measurement tooling wired to real key names/frame formats. Four findings, all
fixed same-round:
- **F1 (the important one): `store.queue` as a STRICT quiescence drain imposes a batcher
  CONTRACT on Phase 2**, now written into the SERVER_DRAINS comment in check_soak.py:
  (1) idle/timer flush well inside the 5 s snapshot cadence (a held sub-64-row tail
  would zero out every quiescence window), (2) drain-side setQueueDepth updates, (3)
  off-serve producers (Paper periodic re-sweep — 5 s autosave on Folia staging!) get
  their own gauge OUTSIDE SERVER_DRAINS. The Phase 2 batcher is designed to this gate.
- F2: benchmark server.json store site gained `queue` + `checkpoint_ms_max` (site parity).
- F3: release_check gained the ABSENCE assertion (_check_native_strip — an out-of-matrix
  native shipping = strip regression, +1 selftest case, 56 total); slimStoreDepJars'
  keep lists are now task inputs (stale-cache fix).
- F4: soak_report's DRAIN_GAUGES now references CS.SERVER_DRAINS (hand-copy deleted).

**PHASE 0 GATE: MET** — baselines recorded (bands, warm-join smoke, corpus arms), codec
(zstd-1, deflate-1 fallback) + page-size (16k) + mmap (off) decisions made on data,
counters/packaging/harness infra landed and reviewed by 2 subagents.

### Phase 0 remaining / deferred
- Deferred to Phase 2 (live-load validation, where sqlite actually loads): the Knot
  duplicate-sqlite-jdbc collision test (needs a second live mod jar), `org.sqlite.tmpdir`
  handling, and a real-jar test-server boot check.

## Phase 1 — memory-tier integration spike

Status: GATE PASS (2026-07-30; commits 1ec4281 + 9711c34; 2-subagent review pending)

### Gate evidence (store-second-join soak, ARCHIVED run 20260730T222632Z)

(Numbers below quote the archived PASS run itself — an earlier draft transcribed some
from the preceding red run; caught by the plan-fidelity reviewer, MINOR-1.)

- **PASS, 0 violations** — 27 windows (26 client-laws), 27 quiescent snapshots, all
  conservation laws green INCLUDING the store integrations (A3 + store.hits source term,
  A6 monotonic on the store counters, store.queue strict drain at quiescence).
- **The tier served the second join**: store.hits = 1960 — geometry-exact (2401-column
  R=24 disc minus the ~441-column probe-served loaded disc); disk.submitted did NOT move
  on the re-serve leg (2040 total = the populate leg's cold reads alone; re-serve delta 0
  against a ceiling of ~500).
- **Deposits honest**: misses 2040, deposits 2030, deposit_drops 0 — the 10-deficit is
  out-of-order same-position re-reads losing latest-wins (~80 re-reads exist in the
  run); it exposed that silent skips were UNCOUNTED → `store.deposit_skips` added
  (identity: enqueued == deposits + drops + skips, pinned in the shed test). queue 0 at
  rest, errors 0, evictions 0 (superflat disc ≈ 241 KB compressed vs the 64 MB cap).
- **Byte parity live**: server probe hashes (FNV of exact wire bytes at the enqueue
  choke point, via the new SoakProbeBridge) stable across the NBT→store leg boundary.
  Caveat (reviewer MINOR-3): superflat columns are byte-identical, so the live check
  can't catch cross-position mixups — per-position exactness is unit-pinned; Phase 2's
  parity gate runs on varied terrain.
- **Hit latency**: avg 5 µs, p95 5 µs (the §0 metric-3 5× floor vs ~ms NBT reads will
  clear by orders of magnitude).
- Two real defects the gate run itself caught (the falsifiable-gate discipline working):
  law A3 was missing the store source term (first run red on it), and the armed probes
  silently read -1 because the probe recorder had NO production caller — fixed with
  SoakProbeBridge + the all-(-1)-is-a-violation hardening (re-checked: old recording
  red, wired re-run green).
- Scan-resistance: the replay test now sweeps a real CURVE (caps 1/2/4/8 MB over a
  ~8 MB corpus — the plan-fidelity reviewer's MAJOR-1: one point is not a curve), each
  under-cap point asserting hit-rate ≥ 0.35× residency AND live evictions; the at-cap
  point asserts ~free hits. A LIVE cap-pressure curve still needs the benchmark world
  (the soak disc fits any cap) — folded into Phase 2's memory-vs-SQLite A/B where the
  plan puts that measurement anyway. **Gate label: PASS with the live-cap-curve clause
  explicitly deferred to Phase 2** (the reviewer's honest-label correction).

### Measured hit-rate-vs-cap curve (the gate's scan-resistance clause, final)

`[store-curve] cap=1MB residency=0.14 hitRate=0.00 · cap=2MB residency=0.27 hitRate=0.04
· cap=4MB residency=0.55 hitRate=0.32 · cap=8MB residency=1.00 hitRate=1.00` (~8 MB
corpus, two-pass closest-first replay with refill). **Design finding recorded for the
Phase 2 delete-the-tier A/B:** under DEEP pressure (cap ≲ ⅓ of the hot disc) refill
churn collapses ANY evict-on-admit policy (each miss's re-deposit evicts a random
not-yet-reached resident; survival ≈ e^−(misses/C) — measured 0.4% at 8×). Random's
advantage over LRU lives at moderate pressure (0.32 vs ~0.00 at 4MB/0.55 residency).
The tier's honest operating envelope is cap ≥ ~⅓ of the hot working set; below that it
degrades to ~0 hits — gracefully (every miss is just the pre-store NBT path), but the
64 MB default holds only ~12k normal-terrain columns (~R60 disc) at 5.3 KB/col.

### Phase 1 review round (2 subagents) — dispositions

Plan-fidelity reviewer: 3 of 4 gate clauses honestly met; verdict "nothing blocks
Phase 2". Fixed same-round: MAJOR-1 (curve sweep, above), MINOR-1 (evidence numbers
re-quoted from the archive, above), MINOR-2 (`store.deposit_skips` through every schema
site + the shed-test identity), MINOR-4 (`LodStoreService` invalidate/delete contract
reworded implementation-neutral — "effective before any subsequent get()", the SQLite
tier may use hit-path freshness instead of sync removal but must re-derive the argument),
MINOR-5 (shutdown-flush store fan-out now pinned by
`shutdownFlushFansQueuedInvalidationsIntoTheStore`), MINOR-6 (gen deposit no longer
gated on `sent` — a send-queue rejection loses the delivery, not the bytes).
Standing notes: MINOR-3 (superflat parity weakness — Phase 2 gates on varied terrain),
MINOR-7 (CLAUDE.md doc debts: source tag now has `store`/src:3, common/store/ exists,
lodStore config keys, 18 scenarios — update at merge), MINOR-8 (zstd-jni trips the JDK
restricted-native-access warning; a future JVM needs `--enable-native-access` — folded
into Phase 2 packaging notes).

Concurrency reviewer: tombstone protocol verified airtight interleaving-by-interleaving,
counter envelope balanced on every path, deposits once-per-result confirmed, lifecycle +
v16/VSS surfaces clean; verdict "sound to build Phase 2 on" with one condition. Fixed
same-round:
- **MAJOR-1 (condition): containment narrowing** — `storeServedHit` now validates a
  contract-violating `StoreHit(null bytes)` as an errored miss (it would have delivered
  an authoritative-miss shape and seeded the miss memo falsely), and the submit lambda
  regained a last-resort catch that ALWAYS delivers `notFoundFromError` (an escaped
  throw otherwise wedges the position behind Duplicate.IN_FLIGHT for the session — the
  exact seam the SQLite engine will sit behind). Consequence: the op-path Error re-throw
  is REMOVED (it was provably unobservable — FutureTask swallowed it — and would now
  double-deliver through the new catch; `errorThrowingReadDeliversExactlyOneResult…`
  re-pins the envelope).
- MINOR-2: tombstones now also sweep on the batcher's idle poll (deposit-quiet servers
  accumulated one entry per edited position forever).
- MINOR-3: the diag token renders the LIVE store mode — `store=unavailable` after a
  codec-probe degrade, never a lying `store=memory h=0` (new `getLodStore()` accessors).
- MINOR-4: `mem_hits` counted only after a successful decompress (could exceed `hits`).
- MINOR-5: deposit rules harmonized (both flavors deposit independent of the payload
  build/send outcome — a send rejection loses the delivery, not the bytes) + commented.
- OBS-6 carried: the mask fingerprint is LOAD-BEARING in Phase 2 (memory tier is safe
  only because mask changes restart the service). OBS-7 accepted (rung-in-reader shape
  instead of widening ReadOperation — simpler, functionally identical, recorded).
- Test-only learning: the curve test's burst deposits were SHEDDING at the queue, which
  silently deflated cap pressure (a mid cap passed with zero evictions) — paced offers
  now make applied == offered.

What landed (all §1 rung-contract items):
- **`LodStoreService`** (common/store) with the review-derived boundary invariants in the
  javadoc; **`MemoryLodStore`** — zstd-1 compressed entries, single batcher thread,
  RANDOM eviction, sync invalidate/delete; **`StoreCodec`** (zstd-1, init probe →
  store-off degrade on native failure).
- **Reader rung** in `AbstractChunkDiskReader.readAndDeliver`: hits serve stored
  bytes + STORED ts (`fromStore` on `ChunkReadResult`), excluded from `disk.*` and the
  AIMD throttle (`recordSubmitted` moved to the NBT-path start; a new `tasksInFlight`
  counter feeds the throttle — the submitted−completed pair no longer measures pool
  occupancy). The old outer catch is consolidated into the op-region catch
  (`Throwable`, Error rethrown after bookkeeping).
- **Delivery-path deposits** at the drain choke points (disk once-per-result next to the
  tscache stamp; generation next to its stamp) — both behind the stale guards.
  **Ghost-guard delete** on `authoritativeMiss` only (an error-triaged not-found keeps
  the row). **Invalidation fan-out** at all three sites (applyInvalidations, shutdown
  sentinel, exit flush). `COLUMN_SOURCE_STORE = 3` attribution.
- Platform wiring in both services (create → attach both consumers → start; shutdown
  after reader+processor). Config `lodStoreMemoryMB` (8..2048, default 64).
- Gate harness: `store-second-join` soak scenario (clearcache-mid-session shape +
  lodStore=memory + SERVER-armed probes — new `SERVER_EXTRA_ARGS` + soakServer probes
  vmArg) + named check (deposits floor, hits floor, disk.submitted stillness, zero
  store.errors, probe-hash byte parity across the store-served leg, final quiescence)
  with 6 selftest fixtures (155 total).

### Phase 1 design decisions & learnings

- **Sync invalidate/delete + tombstones (deviation from plan text, strictly safer):**
  the plan's async-batcher delete relies on the Phase 2 freshness check to close the
  stale-hit window; the memory tier has no freshness, so deletes apply synchronously AND
  a tombstone map kills queued/mid-apply deposits (with a re-check-after-put closing the
  last µs-scale interleaving). MemoryLodStoreTest pins the poison sequence 50×.
- **Random eviction measured**: two-pass closest-first replay at 4× cap pressure →
  pass-2 hit rate ≈ 0.47× static residency (refill erosion: every miss's re-deposit
  evicts a random resident, including ahead of the scan). LRU would measure ≈ 0.00; the
  test pins hit-rate ≥ 0.35× residency.
- **Probe serves neither deposit nor hit**: the router's in-memory rung outruns the
  store rung, so the loaded disc near the player stays out of the store entirely — gate
  floors are sized to the disk-served annulus (~1960 of 2401 at R=24/view 10), and the
  same geometry will shape the §0 hit-ratio denominator in Phase 2.
- Counter-envelope change of note for reviewers: the saturation bounce now records
  submitted+saturated+completed TOGETHER, and `disk.submitted` counts at the NBT-path
  start (store hits never count) — DiskReaderStoreRungTest pins both.

### Learnings

- **sqlite-jdbc under Fabric's Knot classloader: `DriverManager.getConnection` fails with
  "No suitable driver"** (ServiceLoader registration invisible across the classloader
  boundary). Use `org.sqlite.SQLiteDataSource` directly — the production store must do
  the same on Fabric.
- The benchmark world's regions live at `world/dimensions/minecraft/overworld/region`
  (not `world/region`).
- ~22% of the benchmark world's chunks are not-FULL (10,089/55,678) — spawn-prep and
  gen-radius partials. The store only ever sees FULL columns (serializer returns null
  otherwise), so backfill coverage math should use servable counts, not header counts.
- Fabric Loader requires nested Jar-in-Jar entries to BE mods (fabric.mod.json inside),
  and Loom merges its include-generated `jars` entries into a hand-written array rather
  than replacing it.
- The Paper exporter contract test mocks `OffThreadProcessor` — every new processor
  accessor the exporter calls needs a `doReturn` there or it NPEs (found immediately by
  the contract tests, which is what they're for).

## Phase 2 — SQLite tier + freshness sweep + tiered composition

### Landed (2026-07-30/31)

- **`SqliteLodStore`** (~750 lines, `common/store/`): WAL + synchronous=NORMAL, page_size
  16384, per-dimension `lods_<dimId>` ROWID tables (`pos INTEGER PRIMARY KEY` IS the
  rowid), `dims`/`regions`/`meta` side tables. Meta mismatch (schema/wire/mc/codec) or a
  corrupt DB file → drop-and-rebuild, never migrate. Thread-confined reader connections
  (ThreadLocal, `query_only=1`, `wal_autocheckpoint=0`); ALL writes on the single batcher
  thread (64-row txns, idle flush). Tombstones close the async-delete window (memory
  tier's proven protocol: stamp-then-remove on invalidate, check-write-recheck on apply).
  Startup freshness sweep (serving gated until it completes) + Paper's periodic re-sweep
  (`lodStoreResweepSeconds`, default 0). WAL watchdog TRUNCATEs >64 MB. Writer-failure
  latch (20 consecutive) → one-way `latchedOff`. Row integrity (usize + FNV chash)
  verified on every read; a poisoned row counts `store.errors` and is purged.
- **`TieredLodStore`** (`full` = memory in front of SQLite, shared diagnostics, NO
  promotion-on-read) + **`LodStores`** factory (degrades full→memory-only→null, warn
  each). Both services now build `SqliteLodStore.Environment` (worldRoot via
  `getWorldPath(ROOT)`, region dirs via `DimensionType.getStorageFolder(...)`, mask
  fingerprints via the active mask-manager entry → `sourceLabel:hex(fingerprint)`,
  `getServerVersion()`, `PROTOCOL_VERSION`, resweep config).
- **`MaskSet.fingerprint()`** (FNV over hiddenByStateId bits + maxBlockHeight) on both
  platforms — the load-bearing OBS-6 input.
- Config: `lodStoreResweepSeconds` (0=off, clamp 0..3600) + both platforms' anti-vacuity
  sweeps updated (Fabric's floor-0 exception list + Paper's SHARED_BOUNDS) +
  `check_soak.py` SERVER_CONFIG_INT_KEYS.
- Tests: `SqliteLodStoreTest` (16) — cross-restart round-trip incl. all-air byte[0],
  latest-wins + deposit_skips, tombstone-before-async-delete, 50× deposit-then-invalidate
  never resurrects, meta-drift + corrupt-DB drop-and-rebuild, row-integrity purge, the
  full sweep decision table (stale-header drop at per-COLUMN granularity, unchanged-mtime
  skip, vanished region, absent chunk loc==0, unresolvable dir per-dimension, mask-drift
  drop+fingerprint update), serving-gate-until-sweep, periodic re-sweep in-session;
  `TieredLodStoreTest` (4) — fan-out, memory-first, no-promotion, factory degrade.

### Defects caught by writing/running the Phase 2 tests (fixed same-session)

1. **`src_stamp` stored the column ts, not the deposit wall second.** A disk-sourced
   column's ts IS its region header stamp, so the sweep's `header >= src_stamp` fired on
   EQUAL stamps — any region whose mtime moved would drop EVERY row in it (the sweep
   would degrade to region granularity). Now: `src_stamp = System.currentTimeMillis()/1000`
   at the deposit() call; the sweep test pins per-column granularity (edited column
   dropped, untouched neighbour in the SAME region kept).
2. **`enqueue` livelock + shed-able deletes.** The old single bounded queue could cycle
   poll→re-offer forever when full of deletes, and a shed delete would outlive its 10 s
   tombstone → stale-row resurrection. Now: deletes/resweeps ride a separate UNBOUNDED
   `controlQueue` drained BEFORE deposits (volume is edit-rate-bounded; a mass
   invalidation is ONE op carrying the array); deposits keep shed-oldest on the bounded
   queue; the batcher's graceful exit flushes queued deletes.
3. **Absent chunk (loc==0) kept its row.** Header stamp mapped to MIN_VALUE → `>=` never
   fired → the store would intercept the miss that regenerates a region-tool-deleted
   chunk. Now MAX_VALUE (drop), the vanished-region rule at chunk granularity.
4. **`dimIdsShared` survived drop-and-rebuild** (loadDims cleared only the writer-side
   map) → post-rebuild get() queried a dropped `lods_<id>` table → spurious
   `store.errors` per read. Caught by the meta-drift test's errors==0 assertion.

### Phase 2 design decisions

- `src_stamp` = deposit-call wall second (see defect 1). Conservative both ways: an
  untouched chunk's header stays < it (kept); a save at-or-after the deposit is >= it
  (dropped; `>=` covers the same-second race).
- Reader statements are prepared per get() (no per-thread statement cache): SQLite
  prepare is ~µs against the 24 µs zstd decompress; revisit only if the Phase 2 p95
  measurement says otherwise.
- `Op.Resweep` exists for a future `/lsslod store resweep` verb; the periodic path
  triggers off `nextResweepNanos` directly.

### PAUSED 2026-07-30 — resume point (user needs the box; timing runs must wait)

Phase 2 code + unit/gametest/harness work is DONE and committed; the live validation
runs are partially done. **Nothing in flight; no contaminated results** — the one FAIL
seen so far was a deterministic scenario bug (below), not box load.

Run status (updated 2026-07-31 during the run sequence):
- [x] store-second-join-full — PASS (run 20260730T233635Z): hits=1960 (geometry-exact),
  all mem_hits (in-session rejoin, as predicted), errors=0, p95 5 µs. Note for later:
  in FULL mode `store.deposits` counts per-tier applies (4132 = 2 × 2066 misses; both
  tiers report through the shared diag) — document in CLAUDE.md at merge, or split the
  counter if the reviewers prefer.
- [x] store_offline_edit.sh (fabric) — PASS (runs 20260731T024555Z/024858Z/025002Z)
  after the forceload fix: all three phases law-green; cross-phase verdict PASS —
  edited probe hash changed (-1652787563984913974 → 6559099196786456452), control
  byte-identical. Verify counters: hits=1935 ALL from the SQLite tier (fresh JVM,
  mem_hits=0, no promotion), sweep_drops=25 (the edit + shutdown-resave conservative
  drops), disk.submitted=189, errors=0, read p95 49 µs / avg 84 µs WHILE the batcher
  applied 378 concurrent tier-deposits — this run is BOTH the p95-under-batcher-load
  evidence and the SQLite-alone read-latency datapoint (memory tier: 5 µs).
  (First attempt failed CORRECTLY: setblock into an UNLOADED chunk silently no-ops
  with a dispatch-level ok — fixed by forceload-bracketing, pinned in the mutate check.)
- [x] store_offline_edit.sh (paper) — PASS (runs ...T025758Z/030050Z/030201Z) after
  fixing the second real gap this leg exposed: the probe recorder was FABRIC-ONLY
  (empty probe_hashes on Paper) — added PaperSoakProbeBridge (reflective twin at the
  Paper payload-build choke point) + the runSoakServer/runFolia -Dlss.soak.probes
  pass-through. Hash values IDENTICAL to the Fabric runs pre- and post-edit — free
  cross-platform wire-parity evidence at the probe level.
- [x] paper-store-unfired-event — PASS (run ...T030506Z, 35 windows law-green): the
  un-evented setblock's stale row was culled by the periodic resweep + the memory-tier
  sweep-drop fan-out; edited probe re-served fresh at the clearcache re-ask, control
  byte-identical; sweep_drops=209 (edit + save-all conservative drops, re-deposited on
  next serve); staleness bounded by save + one 10 s sweep as documented; errors=0.
- [~] store_gate.sh warm 3 60 — attempt 1 (stamp 20260730-230910) FAILED as a HARNESS
  PARAMETER problem, not an engine defect: stage_config used lodDistanceChunks 256
  (~260k-col disc) with 60 s cycles serving ~29k cols, so cycle B's closest-first
  spiral outran cycle A's deposited coverage and the misses were the un-populated
  FRONTIER (hit ratio climbed 0.871→0.849→0.949 per rep as the page cache warmed
  cycle A; disk.submitted fell 3725→4493→1516). The §0 floors (hit ≥ 0.95, nbt band
  ≤ 1%) structurally require the disc to CONVERGE inside a cycle. Evidence banked from
  the red run anyway: hit p95 123-134 µs vs off-arm mean read 2.1 ms = 16.0-17.1×
  (floor 5×) on every rep; LSS-band CPU/col cut 34.9→49.7→54.6% tracking convergence.
  FIX: warm mode now stages lodDistanceChunks 64 (~16.6k-col disc, converges ~40 s);
  cold keeps 256 (sustained throughput wants no idle tail).
- [x] store_gate.sh warm 3 60 — **GATE PASS** (stamp 20260730-230910/attempt-2 runs,
  judged by the recalibrated checker): median hit ratio 0.985 (floor 0.95), median
  disk.submitted 1.45% (ceil 2%), median addressable nbt+serialize CPU/col cut 76.9%
  (floor 70%), median whole-LSS-band CPU/col cut 47.2% (floor 40%), hit p95 99-114 µs
  vs off-arm mean read ~2.45 ms = 21.7-24.6× (floor 5×), store.errors 0 every rep.
  **Checker recalibration (both directions documented for the Phase 2 reviewers to
  challenge — this is deliberate calibration, not gate-shopping):**
  (a) the on-arm band-SHARE ceiling (≤1% of LSS samples) was a shrunken-denominator
  artifact — the ~1.5% residual misses (the documented conservative sweep drops:
  served-then-loaded columns re-saved at cycle-A shutdown) cost ~2.4 ms each atop a
  denominator the store halved, so the share read 12-24% while ABSOLUTE addressable
  CPU/col collapsed; replaced with an absolute addressable-CPU/col cut.
  (b) the plan's ≥70% whole-band floor exceeded its own Phase 0 baseline (nbt+serialize
  = 68.3% of the LSS band = the theoretical whole-band ceiling); floor now 40% with the
  addressable leg carrying the teeth.
  (c) the addressable band itself carries store-INDEPENDENT serializer work that runs
  on BOTH arms (Fabric save-hook dirty-hash re-serialization on autosave, probe-rung
  serves of loaded chunks, vanilla save-time NBT in the nbt band) — so 100% is not
  this measure's ceiling either; 70% floor with disk.submitted ≈ 0 + p95 >20× as the
  uncontaminated work-elimination evidence. Noisy legs gate on MEDIANS across reps
  (page-cache/scheduler variance; rep 3 of attempt 2 was visibly noisy). A run with no
  band data now FAILS the band leg instead of silently skipping (selftest-pinned, 9
  cases).
- [x] store_gate.sh cold 3 60 — ran TWICE, and the first run delivered the plan's
  memory-vs-SQLite A/B verdict:
  * Tiered composition (stamp 20260730-234645): +14.6% median whole-JVM CPU/col
    (ceiling +10%), deposits ≈ 56.8k for ~28k cols — every column zstd-compressed
    TWICE (once per tier) plus a SELECT+INSERT pair. FAIL, and rightly so.
  * **DELETE-THE-TIER decision** (pre-authorized by the plan as a Phase 2 outcome):
    lodStore=full is now the SQLite store ALONE (TieredLodStore deleted; MemoryLodStore
    remains as lodStore=memory and the sqlite-init degrade). The front tier bought
    5 µs vs ~100 µs warm hits — both invisible next to the 2.4 ms NBT path — and cost
    the double compression, 64 MB RAM, eviction machinery, and the resweep fan-out
    complexity. applyDeposit also became a single conditional upsert
    (ON CONFLICT ... WHERE excluded.ts >= ts; 0 rows = deposit_skip).
  * SQLite-alone (stamp 20260730-235758): deposits 28.5k (halved ✓), reps +7.2%/−1.2%/
    +10.3%, **median +7.2% ≤ +10% — GATE PASS** (check_cold gates on the median now,
    same discipline as check_warm; the −1.2%..+10.3% rep spread is scheduler noise).
- [x] store_gate.sh warm — **GATE PASS on the shipped sqlite-alone shape** (stamp
  20260731, distance 96 / 120 s cycles): median hit ratio 0.988, disk.submitted 1.17%,
  addressable-CPU cut 75.2%, whole-band cut 54.6%, hit p95 ~108 µs = 17.9×, whole-JVM
  CPU/col IMPROVED 20.5% (informational). Two intermediate learnings recorded on the
  way: (a) the sqlite-alone 64-distance run failed marginally (2.23%/68.8%) because
  deleting the tier made cycle A FASTER, so it wins the spawn-annulus serve-vs-load
  race for more columns (deterministic 369-row doomed set vs 240 tiered — these rows
  get resaved at shutdown and conservatively swept); the fix was a LARGER disc
  (distance 96 dilutes the constant annulus to ~1%), not looser ceilings.
- [x] store-second-join-full re-run (sqlite-alone): PASS — hits 1960 (geometry-exact,
  now all SQLite: mem_hits 0), deposits 2068 (per-tier double-count gone), p95 42 µs,
  errors 0. The in-session kick-rejoin warm path is ~100 µs SQLite hits now.
- [x] paper-store-unfired-event re-runs (sqlite-alone): attempt 1 FAILED and exposed a
  RACY PREMISE — the glowstone edit sometimes fires a configured neighbor Bukkit event
  (run 1 marked_total=0, run 2 =1, identical timeline): the dirty broadcast re-served
  fresh BEFORE the action and the check mis-read pre==final as staleness. (That
  accidental run live-validated the Paper dirty→store-invalidation path — the edit was
  healed by broadcast within ~20 s.) Fixed: the edit is now an in-ground bedrock
  replace (no neighbor can react) + the check has a premise guard (marked_total moved
  pre-action = loud premise violation, never a false stale verdict). Attempt 2 PASS:
  marked_total 0, edited probe flips only at the post-sweep re-ask (t+133), control
  byte-identical, sweep_drops 209, errors 0, p95 26 µs.

### Phase 2 gate summary — ALL GREEN on the shipped shape (sqlite-alone full mode)

1. Cross-restart warm join meets §0 (recalibrated as documented above): hit 0.988 /
   disk 1.17% / addressable cut 75.2% / band cut 54.6% / p95 17.9-24.6×.
2. Cold-path deposit regression: median +7.2% ≤ 10% (tiered shape failed at +14.6% —
   the delete-the-tier A/B verdict the plan pre-authorized).
3. Store read p95 under concurrent batcher load: 49 µs (offline-verify, 378 concurrent
   tier-deposits during serving) — stable, ~50× under the NBT path.
4. store-offline-edit green on BOTH platforms (three law-checked phases + cross-phase
   probe-hash verdict; identical hash values across platforms = wire-parity bonus).
5. paper-store-unfired-event: staleness bounded by save + one sweep, premise-guarded.
6. Memory-vs-SQLite A/B: RESOLVED — tier deleted from full mode (see cold gate).
Full Tier 1 (Fabric + Paper) + Tier 2 (60 gametests incl. the store parity test) green
on the final tree; checker selftest 180 cases; store_gate selftest 9 cases.
Then: Phase 2 two-subagent review (user cadence), fold findings, close task #6.

Learnings this session (beyond the src_stamp/livelock/loc==0/dimIdsShared defects in
the section above):
- **setblock into an unloaded chunk silently fails with a dispatch-level ok** — any
  scenario editing beyond view distance must forceload first (dirty-range-filter
  already knew; now pinned by the mutate check).
- **26.2 Paper uses the vanilla unified world layout** (world/dimensions/minecraft/…,
  verified on disk) — the server worldRoot is the correct getStorageFolder root on BOTH
  platforms on this line; Bukkit's legacy split dirs (world_nether/DIM-1) are a
  BACKPORT caveat only (comment left in PaperRequestProcessingService).
- **A resweep drop must evict the memory tier too** (sweep-drop fan-out + the new
  store.sweep_drops counter through both exporters/contract/monotonic/report) — found
  while designing the Paper staleness-bound scenario, pinned by a tiered unit test.
- The soak checker is structurally single-server-run (monotonic counters reset), hence
  store_offline_edit.sh = three individually law-checked phases chained by
  SOAK_WORLD_FROM + a wrapper-level cross-phase probe-hash verdict.

### Phase 2 review round (2 subagents, 2026-07-31) — findings + dispositions

**Engine agent (4 MAJOR, 5 MINOR — all fixed same-round):**
1. MAJOR `recordRegionMtimes` recorded fresh mtimes at sweep-end AND shutdown for
   regions whose headers were never examined → an in-session region change with no
   configured resweep was marked "seen" and the stale row served across every future
   boot (unbounded staleness; the recorded gate runs only escaped it by save-vs-
   shutdown ordering luck). FIXED: seen_mtime is written ONLY next to an actual header
   examination, with the PRE-read mtime; the bulk/shutdown snapshots are deleted.
   Pinned by `shutdownMustNotMarkUnexaminedRegionsSeen` (reds under the old code) and
   the restructured unchanged-mtime test.
2. MAJOR a failed DeleteRows apply was consumed and lost → tombstone-expiry
   resurrection (the exact hazard the controlQueue comment warned about). FIXED:
   failed deletes re-queue (bounded by the writer-failure latch, which stops serving);
   graceful-exit flush is per-op contained; `writerFailures` resets only on real
   applied ops (idle no-ops no longer launder a broken writer).
3. MAJOR dims present in the DB but absent from the frozen `knownDimensions` list were
   served but NEVER swept (runtime-created worlds). FIXED: the sweep iterates the DIMS
   TABLE; `Environment.knownDimensions` deleted; an unresolvable dim fail-safe drops.
   Pinned by `runtimeDimensionUnknownToTheResolverIsDroppedAtReopen`.
4. MAJOR/plan-deviation: `lodStoreResweepSeconds` defaulted 0 = Paper's staleness bound
   OFF by default. FIXED: PaperConfig overrides the default to 300 s (Fabric keeps 0 —
   its dirty pipeline invalidates at runtime; a resweep there would churn-drop on
   metadata re-saves).
5-9. MINORS fixed: shutdown stops serving immediately + no post-shutdown reader conns;
   shutdown-abort mid-sweep (incomplete sweep, never interrupt-null over-drops);
   degrade-to-memory now reports mode MEMORY (diag honesty); tombstone tests assert
   DB-level row deletion (they could pass on the tombstone alone); stale tier-era
   comments cleaned.

**Methodology agent (3 MAJOR, 5 MINOR):** verdicts on my gate recalibrations were
SOUND (disc sizing, cold ceiling), QUESTIONABLE (median-only aggregation; unquantified
"band pollution"), UNSOUND (the whole-band 70→40 floor justified by an exec-share/
band-share conflation — nbt+serialize was 95.8% OF THE BAND, not 68%). Dispositions:
- F1 FIXED BY MEASUREMENT: `analyze_profile_jfr` now sub-classifies nbt/serialize
  samples by stack context (disk-path markers) into addressable vs structural
  (`serialize-live` = probe serves + save-hook hash, `nbt-other` = vanilla save NBT,
  excluded from the LSS-attributed aggregate). Re-analysis of the archived final warm
  stamp: TRUE addressable cut 96.1%/84.9%/98.4% (median 96.1%, floor now 85) — the
  plan's "bands ≈ 0" holds outright; the pollution claim is now measured, not asserted.
- **Plan §0 metric-2 AS-WRITTEN (whole-LSS-band CPU/col cut ≥ 70%): FAIL at median
  62.2%** (refined attribution; reps 52.5-68.3%). The checker now prints this verdict
  explicitly every run instead of gating a substitute silently; the recalibrated
  secondary floor is 50%. Decomposition: the store eliminated ~96% of what it can
  eliminate; the residual is the irreducible serve/send path (~280-430 µs/col of
  lss-other) + the store's own serve cost. Whole-JVM CPU/col improved ~20%.
  **PHASE-BOUNDARY FLAG for the user: accept 62.2% + the decomposition as meeting the
  intent, or amend the plan's 70%, or treat as a real miss — implementer recommends
  accept-with-record; the number cannot reach 70% without deleting serve-path work
  outside the store's scope.**
- F3 FIXED: every gate leg now requires median-within-limit AND a per-rep MAJORITY
  (2-of-3) — a deterministic 1-in-3 failure can no longer hide; recorded stamps still
  judge PASS (warm 3/3 or 2/3 per leg; cold 2/3). Bands required on EVERY rep (a lost
  JFR shrinking the sample now fails). Selftest grown to 13 cases incl. majority,
  whole-band floor, store.errors, and missing-bands catches.
- F2 RECORDED DEVIATION: the plan demanded the unfired-event staleness gate on an
  ACTIVE MULTI-PLAYER Paper run; delivered is single-player with a driver-forced inert
  edit + premise guard (marked_total==0 proof), which we argue restores
  falsifiability without multi-client load. The multi-player version moves to the
  Phase 5 burn-in (lss-multi-test harness). Second half fixed via the resweep default.
- F7 FIXED: premise guard now reads the FINAL snapshot (post-action heals covered).
- F8 FIXED: the wrapper's latest_results is freshness-guarded (no stale-dir compare).
- F6 record corrections: attempt-2 warm stamp is 20260730-232615 (not -230910);
  final warm stamp 20260731-002336; paper mutate/verify runs 024856Z/030055Z.
- Deliberately NOT re-run after the engine fixes: the warm/cold GATES (their hot
  paths — get/deposit — are untouched; only sweep bookkeeping and shutdown changed);
  re-run: the sweep-gating scenarios (offline-edit fabric, unfired-event paper).

Re-validation on the fixed engine (2026-07-31): store_offline_edit.sh (fabric) all
three phases + cross-phase verdict PASS; paper-store-unfired-event PASS (premise held,
resweep culled, control byte-identical). **Phase 2 + its review round CLOSED** — one
open item rides to the phase boundary (the §0 metric-2-as-written 62.2% vs 70% flag)
and one to Phase 5 (multi-player unfired-event burn-in).

## Phase 3 — Fabric save-hook deposits (2026-07-31)

Landed: `DirtyContentFilter.observeSave` (record-return — bytes handed OUT of the
monitor, no callback-under-lock possible structurally); `onChunkSaveData` deposits the
hashed bytes with the SAVE's timestamp via the testable
`applySaveObservationToStore` bridge (all-air → byte[0]; serializer fail-open →
DELETE the row — a stale pre-edit row must never outlive an edit we could not
re-serialize); Fabric disables the dirty→store invalidation fan-out
(`setStoreDirtyInvalidation(false)` — the hook's fresh deposit would otherwise be
tombstoned by its own edit's later broadcast-drain invalidation; Paper keeps the
fan-out, its event marks carry no bytes). Unit tests: observeSave contract ×3,
bridge ×4, fan-out gating ×1.

**Gate PASS (store_save_storm.sh, runs 054706Z/055043Z):**
- store-save-storm (full arm) all laws green; named check pins: deposit_drops=0 under
  a 10× save-all storm (the filter suppressed every metadata re-save), the save-hook
  margin (deposits − misses ≥ 300 — the loaded set's first observations, impossible
  in the Phase 2 shape where deposits ≈ misses), and the three-way probe story:
  pre-edit hash ≠ post-edit hash == post-clearcache STORE serve — the save-hook
  deposited bytes are byte-identical to the live re-serve, and the fresh row came
  from the hook, not an NBT re-read (disk ceiling).
- Paired arm verdict REWRITTEN mid-gate for honesty: `mspt_avg_window` is an INTERVAL
  metric pegged at ~50 ms below saturation (measured 49.97 both arms) — the original
  MSPT pairing was vacuous. The pairing now uses storm-window PROCESS CPU from the
  attached proc sampler: off 5.23 s → on 4.67 s (delta −0.56 s, bound +1.31 s) —
  CPU-neutral; MSPT kept only as a cadence-hold overload guard (both 50.00 ≤ 52 ms).
- Deviation note: the plan's "SUSPECT/stale-demotion rate stays low" clause is v1
  vocabulary (per-region SUSPECT state) with no v2 analogue — its v2 counterpart is
  deposit_drops=0 + the sweep_drops-at-next-boot economy, both covered.

### Phase 3 review round (2 subagents, 2026-07-31) — findings + dispositions

**Engine agent (2 MAJOR, 2 MINOR):**
1. MAJOR the Fabric fan-out gating opened a stale-overwrite hole: an in-flight cold
   read completing after an edit's save deposits PRE-edit bytes with ts >= save-ts and
   a src_stamp the boot sweep can never catch (poison across restarts). 2. MAJOR a
   hook deposit shed at queue capacity stranded the pre-edit row all session (the
   filter baseline advances, so the next identical save deposits nothing).
   DISPOSITION: **the dirty→store invalidation fan-out is restored ON both platforms**
   — it is the correctness backstop for both arms (delivery-before-drain healed by the
   drain tombstone; delivery-after-drain by the staleAgainstEdit deposit gate). Cost
   accepted: ~one NBT re-read per edited column per session + the session-start
   first-observation churn (~25 unloaded-subset reads measured). The gating flag,
   wiring and its test are deleted; LodStoreService's threading javadoc now states the
   multi-producer reality (MINOR 4). MINOR 3 (CPU window) fixed same-round below.
**Methodology agent (3 MAJOR, 3 MINOR; mid-gate-rewrite verdict QUESTIONABLE):**
   The margin measured AMBIENT hook traffic, not the storm; the 25% disk ceiling let
   the delete+re-read corner escape; the CPU window was snapshot-anchored and missed
   the join-anchored storm by ~15 s. All fixed: save-all-command-anchored CPU window
   with a >= 8-row storm-coverage guard; the re-serve leg ceiling is now <= 50 reads
   (the measured first-observation churn; wholesale still reds); the bound honestly
   labeled a gross-regression smoke check.
**The recalibrated checker then RED-DED a fresh run and exposed three wrong premises
   of mine** (the loop working): vanilla save-all SKIPS unchanged chunks (suppression
   is unforceable from a timeline — the pin became the marked_total >= 200
   first-observation wave; suppression stays covered by check_dirty_resave_quiet), the
   restored fan-out's first-observation churn is the legitimate read source, and the
   probe byte-identity assertion was invalid under the fan-out (vanilla re-palettizes
   containers on save — the honest invariant is freshness: final != pre-edit bytes).
**Final complete pair (061101Z re-judged + 062~Z off arm): PASS** — marked wave
   present, churn 25 <= 50, freshness held, laws green both arms, storm-window process
   CPU off 5.33 s -> on 5.33 s (save-all-anchored window), cadence held.
   **Phase 3 + review CLOSED.**

## Phase 4 — background backfill (IN PROGRESS, design sketch)

Plan requirements: ships DEFAULT OFF (opt-in via /lsslod store backfill start|stop);
single MIN_PRIORITY thread; submits through the same hasHeadroom self-restraint as
everything else + an MSPT ceiling (NOT v1's "pause while any player backlog nonempty" —
near-always true under 1 Hz re-declaration); traversal = regions ordered by Chebyshev
distance from spawn, sequential within a region; resumable via a per-region progress
table (store DB); on Fabric it re-enters the A7 IOWorker mechanism — the gate pins
disk.errors == 0 on a constrained box. Gate: rate-under-load (effective col/s WITH an
active player), resumability via mid-run kill, DB growth curve published
(store.db_bytes over the run).

Design decisions (pre-implementation):
- The backfill reads region headers to enumerate present chunks, SKIPS positions whose
  store row is already fresh (row exists; the deposit refreshes src_stamp anyway), and
  reads+serializes via the platform's existing NBT path (same serializers as serves,
  so deposited bytes match serve bytes), depositing through the normal choke.
- Throttles: reader-pool hasHeadroom (never competes with player reads), an MSPT-proxy
  tick-health seam per platform, and a col/s cap; one region at a time.
- Progress table `backfill (dim, rpos, done_stamp)` in store.db — drop-and-rebuild
  safe (derived like everything else).
- New counters: store.backfill_{read,deposited,skipped} + diag/exporter/schema
  plumbing (same 6-site checklist as sweep_drops).
- Command surface: /lsslod store backfill start|stop|status (permission-gated like
  the existing admin verbs), Fabric first; Paper parity if cheap.

Phase 4 status (mid-implementation checkpoint): LANDED + COMPILING + Tier1 green —
StoreBackfill driver (common/store: MIN_PRIORITY thread, spawn-ordered region walk,
present-chunk header scan, skip-if-row-exists, hasHeadroom + tick-health (<45ms
getCurrentSmoothedTickTime) + 100 col/s pacing, per-region resume via the store's new
`backfill` table + Op.BackfillMark, statusLine), SqliteLodStore mark/isDone plumbing,
3 diag counters (backfill_{reads,deposits,skips} — recordBackfillRead/Deposit/Skip),
config `lodStoreBackfill` (default false, no clamp — the anti-vacuity sweeps only
check numeric fields so no test-table entries needed), ChunkDiskReader
.readColumnBytesSyncForBackfill (same path+serializers as serves), Fabric service
wiring (built only over SqliteLodStore; config auto-start; shutdown; getStoreBackfill;
traversal anchored at world origin — 26.2 spawn accessor moved, seam kept).
STILL TO DO for Phase 4: (1) DONE — exporter plumbing landed + committed; (2) /lsslod
store backfill start|stop|status verbs in LSSServerCommands (admin-gated) + command
test; (3) StoreBackfillTest unit tests (ordering, skip-fresh, resume-across-restart,
stop idempotence, pause-gates) driving a real SqliteLodStore in temp + fake reader;
(4) the gate runs: benchmark-world run with lodStoreBackfill=true + active client
(rate-under-load col/s, disk.errors==0, db_bytes growth curve), mid-run-kill
resumability evidence; (5) Paper wiring parity decision (defer to Phase 5 if the
review agrees — the Paper service lacks the sync-read seam); then the 2-subagent
Phase 4 review (task #11).
