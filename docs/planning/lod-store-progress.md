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

### Learnings

(recorded as they happen)
