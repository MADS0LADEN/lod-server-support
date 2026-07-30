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
- **.mca header-timestamp verification**: `scripts/mca_timestamps.py` (scan + compare
  modes). Vanilla: PASS (55,678/55,678 nonzero on the base world). C2ME/Moonrise:
  evidence runs in flight (compare base vs post-run region headers after each arm's
  no-cache run — vanilla's ~10 s metadata re-saves guarantee rewrites near spawn).

### Phase 0 remaining / deferred

- C2ME + Moonrise header-stamp evidence (runs in flight) + a warm-join smoke run
  (store off) to validate the new scenario mechanics.
- Deferred to Phase 2 (live-load validation, where sqlite actually loads): the Knot
  duplicate-sqlite-jdbc collision test (needs a second live mod jar), `org.sqlite.tmpdir`
  handling, and a real-jar test-server boot check.

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
