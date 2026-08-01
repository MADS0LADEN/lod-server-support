# Compressed columns (protocol 19) — implementation progress

Plan: `compressed-columns-implementation-plan.md` (2-agent review folded, §9 there).
Design: `compressed-columns-design.md` (wire-size premise corrected 2026-08-01).
Branch: `feat/compressed-columns` (off `feat/lod-store`).

## Status

| Phase | State | Notes |
|-------|-------|-------|
| 0 — premise measurement | **DONE 2026-08-01** | all premises confirmed; numbers below |
| 1 — protocol v19 + capability + live-path compression | **DONE 2026-08-01** | T1 green both platforms (Fabric ~1040+, Paper ~330+), T2 60 gametests green |
| 2 — store frame serving (fhash, getFrame, deposit reuse) | **DONE 2026-08-01** | schema v3 (drop-and-rebuild), T1+T2 green |
| 3 — observability (wire_bytes, exporters, soak schema) | **DONE 2026-08-01** | contracts + goldens + checker selftests green |
| 4 — validation (tests, soak, compress_gate.sh) | **DONE 2026-08-01** | warm+cold gates PASS 3/3; Fabric+Paper soaks all green; release_check OK |

## Phase 0 — premise measurement

Tool: `fabric/src/test/java/dev/vox/lss/networking/server/CompressedColumnsExperimentTool.java`
(style/gating of `LodStoreExperimentTool` — skips itself unless
`-Plss.store.experiment.regionDir` is given; reuses the existing build.gradle
property pass-through, zero gradle changes). Corpus: `benchmark-worlds/base`
overworld regions through the production `NbtSectionSerializer` path.

Invocation:
```
./gradlew :fabric:test --tests "*CompressedColumnsExperimentTool*" \
  -Plss.store.experiment.regionDir=$PWD/benchmark-worlds/base/world/dimensions/minecraft/overworld/region \
  -Plss.store.experiment.out=$PWD/profile-results/compressed-columns-experiment
```

Measures (plan §1): deflate-6(raw) [OFF arm netty cost + wire size], zstd-1(raw) +
deflate-6(frame) [ON arm live-serve cost + wire size], zstd decompress [store-hit /
client], inflate at both sizes [client OFF/ON], per-size-bucket frame-vs-raw ratios
[threshold pick], `Zstd.getFrameContentSize` == raw length on every frame [bomb-guard
premise], and the derived G1 margin / G3 ceiling inputs.

Results (2026-08-01, 45,589 columns / 42,589 timed after warmup, 64 regions,
`profile-results/compressed-columns-experiment/compressed-columns-experiment.json`):

| Metric (per column, mean) | OFF (today) | ON (v19) | Delta |
|---|---|---|---|
| Server, store-hit serve (zstd get-decompress + deflate6(raw) → deflate6(frame)) | 573.9 µs | 54.1 µs | **−519.8 µs (−91%)** |
| Server, live/disk/gen serve (deflate6(raw) → zstd1 + deflate6(frame)) | 551.0 µs | 101.0 µs | **−450.0 µs (−82%)** |
| Client (inflate(raw wrap) → inflate(frame wrap) + zstd decompress) | 61.3 µs | 26.7 µs | −34.6 µs |
| Wire bytes (deflate6 output) | 4,776 B | 5,351 B | **×1.12** (the accepted trade) |

- `Zstd.getFrameContentSize` == raw length on **all 42,589** frames (violations 0) —
  the bomb-guard/gauge premise holds.
- zstd-1 ratio 6.33 vs deflate-6 ratio 7.08 on raw 33,809 B/col — matches the store
  codec table; review finding B1's +5–12% wire prediction lands at exactly +12%.
- Honest R1 note: vanilla's deflate over the (incompressible) frame costs 54 µs/col —
  over half the ON live-serve cost is vanilla's wasted pass; still −82% net.
- **Threshold pick**: the corpus has ZERO columns under 4 KiB (real terrain columns
  are big), so `COLUMN_COMPRESS_MIN_BYTES` cannot be tuned from distribution data —
  set **512** as a safety floor (covers clears/degenerate shapes; the non-shrinking
  fallback in `ColumnBytes.frame()` covers the rest).
- **Gate inputs (plan §5.2)**: G1 expectation ~13–17% whole-process CPU/col drop on
  the warm arm (520 µs saved against a ~3–4 ms/col baseline); gate floor stays
  m = 0.10. G3 ceiling **1.15** with measured expectation 1.12.

## Phase 1 — protocol v19 + capability + live-path compression

DONE 2026-08-01. Landed exactly per plan §2:

- **common**: PROTOCOL_VERSION 19, `CAPABILITY_ZSTD_COLUMNS`, `COLUMN_CODEC_RAW/ZSTD`,
  `COLUMN_COMPRESS_MIN_BYTES=512`; `useCompressedColumns` (ServerConfigBase, default
  true); `StoreCodec.declaredContentSize` + dual-role javadoc; `WireDialect.V18` →
  `CURRENT`; `ColumnBytes` holder (lazy raw↔frame, memoized, non-shrinking fallback);
  `OffThreadProcessor.attachWireCodec` + one-holder-per-drained-result plumbing through
  `buildAndEnqueueColumnPayload(… ColumnBytes …)`; `wantsCompressedColumns` on
  `AbstractPlayerRequestState`; `QueuedPayload.wireBytes` + `TickDiagnostics`
  `wire_bytes` counter at send success; `ProcessingDiagnostics` cols_zstd/cols_raw.
- **fabric**: payload v19 layout (source, codec, array; v16Wire skips both; read
  memoizes the §0.5 charge `max(shipped, clamp(declared, 0, MAX))`);
  `ZstdWireSupport` (client probe holder + decompress); handshake declares 0x2 on
  probe success (both send sites); `isClearColumn` codec-gated; drain decompress with
  bomb guard + unknown-codec containment; stored-charge release symmetry; build-side
  per-recipient codec choice + probe-hash-raw via `armed()` gate; service wire-codec
  latch + four-term flag at registration; v16 egress codec-0 warn-drop guard.
- **paper**: encode codec overload (twin layout); splice removes TWO bytes + THROWS on
  codec-1; build twin; `Wiring.wireCompressionLive` + latch in productionWiring +
  registration flag; probe bridge `armed()`.
- **New tests**: `ColumnBytesTest`, `CompressedColumnWireTest` (layout golden +
  charge-rule quartet), `ClientColumnDecompressTest` (bomb trio + charge symmetry),
  `CompressedColumnBuildTest` (codec choice, latch, clear pin, mixed fan-out
  compress-once, wire_bytes vs raw charge), `CompressedColumnPaperWireTest` (layout
  twin + splice pins), Paper session-flag four-term AND (in
  `PaperRequestProcessingServiceTest`), HandshakeGate bit-agnostic pin. Existing
  fixture updates: parity ref layouts + protocol pins 18→19 + v16 decode fixtures +
  Paper client-guard sim gained the codec byte.
- **Deferred/noted**: Fabric asV16-seam guard is implemented but pinned only via the
  Paper splice twin (the Fabric seam needs a MinecraftServer; unreachable by
  construction — the session flag forces raw for v16). Fabric registration-flag
  derivation likewise pinned via the Paper twin + Tier 3 (Fabric service has no test
  ctor). `isClearColumn` codec-gating is structural (one line); a wrongly-compressed
  clear decodes correctly at the drain minus the resync flag.

## Phase 2 — store frame serving

DONE 2026-08-01. Landed per plan §3:

- `LodStoreService`: `FrameHit`, `getFrame` (default null), `depositFrame` (default
  false = unsupported; TRUE even on shed — the caller's false-fallback is a raw
  deposit and shed-as-false would double-deposit), canonical `contentHash` static
  (SQLite's private fnv1a now delegates — one hash, never twinned).
- `SqliteLodStore`: **SCHEMA_VERSION 2→3** (`fhash INTEGER NOT NULL` — one-time
  drop-and-rebuild on upgrade, release-note item); `getFrame` validates usize bounds +
  declared-content-size + fhash (no decompress) and feeds the SAME row-poison purge
  ladder; `Op.Deposit` gained the preFramed shape; `depositFrame` enqueues the wire
  frame with caller-computed usize/chash/fhash.
- `MemoryLodStore`: `getFrame` = resident frame verbatim (unvalidated — review A8
  accepted, stated); preFramed deposits skip the batcher compress.
- `AbstractChunkDiskReader`: `setServeStoreFrames` gate (set by both services ONLY
  when wire compression is live — frames off-compression would move decompress onto
  the processing thread for everyone); the frame rung consults getFrame EXCLUSIVELY
  (a miss falls to region IO, never a second get()); `ChunkReadResult` carries
  `frameBytes`/`frameRawSize` (compat ctors keep every rig).
- `OffThreadProcessor`: frame results wrap as `ColumnBytes.ofFrame` (capable
  recipients ship verbatim; raw-needing recipients cost one lazy ~24 µs decompress);
  `depositColumn` reuses a MATERIALIZED wire frame via depositFrame (opportunistic:
  the disk-path deposit runs after the primary build, so a later fan-out recipient's
  frame is missed — accepted); the gen path shares ONE holder between build and
  deposit (new `enqueueLoadedColumn` overload).
- **New tests**: `SqliteFrameServingTest` (frame round-trip, wire-frame-verbatim
  identity pin, all-air shape, nonsense refusal, fhash bit-rot → purge via SQL
  corruption), `MemoryFrameServingTest` (resident-verbatim + skip-compress),
  `StoreFrameServingRungTest` (frame rung exclusivity, all-air, miss-to-IO,
  flag-off bit-identity, throw containment), `StoreFrameDeliveryTest` (no-raw-
  materialization end-to-end, deposit frame-reuse with hash verification, raw-session
  fallback).

## Phase 3 — observability

DONE 2026-08-01. Landed per plan §4:

- `/lsslod diag` Bandwidth line now reads
  `Bandwidth: <rate>/s / <cap>/s global (<raw> total, <shipped> wire, cols zstd=N raw=M)` —
  the §1-confusion fix: `wire` matches observed network bandwidth, `total` stays the
  raw-denominated limiter charge. Threaded via `collectDiagData` (+wire param, both
  platform command sites + the CommandGameTests agreement site); DiagData gained
  wireTotal/colsZstd/colsRaw with a compat ctor; formatter goldens updated.
- Soak/benchmark exporters (Fabric + Paper twins): `service.wire_bytes`,
  `service.cols_zstd`, `service.cols_raw`; client snapshot `wire_received_bytes`
  (ClientSessionGate wire counter, reset with the session, netty-thread recorded from
  `payload.wireEstimatedBytes()`); benchmark deep-report `bandwidth.total_wire_bytes_sent`.
- `check_soak.py`: the three server fields in SERVER_MONOTONIC (auto-required via
  GLOBAL_SERVER_FIELDS) + fixture; `wire_received_bytes` in KNOWN_CLIENT_KEYS
  (presence-optional). **Law A2 verified unchanged**: both ends keep counting
  raw-denominated bytes (server estimatedBytes == client charge for honest frames), so
  `d(bytes_sent) == d(received_bytes)` holds under compression. Selftests: check_soak
  191 OK, soak_report 20 OK, release_check 59 OK.
- Exporter contract files rebuilt (comment header preserved, keys re-sorted) — the
  lockstep convention (contract + Paper twin + checker) moved together; `soak_report.py`
  deliberately untouched (review B6: byte-volume counters live in neither lens dict).

## Phase 4 — validation

DONE 2026-08-01 (remaining items below are deployment-gated, not implementation).

- **Tier 1** green both platforms; **Tier 2** green; **Tier 3 green incl. the new C7b
  pin** (wire < raw received proves the compressed session negotiated end-to-end —
  the test cannot silently pass raw).
- Tooling: `compress_gate.sh` + `compress_gate_check.py` (selftest 6 cases) +
  `analyze_profile_jfr.py` now counts `jdk.NativeMethodSample` (review B2 — the zip
  band was blind to native deflate).

### §5.2 CPU-proof — WARM arm: **PASS, 3/3 reps** (2026-08-01, stamp 20260801-130447)

`./scripts/compress_gate.sh warm 3 150` — warm-join, lodStore=full both arms,
~37.2k columns/run, interleaved same-box A/B:

| Gate | rep1 | rep2 | rep3 | median | limit |
|---|---|---|---|---|---|
| P: OFF zlib ratio (premise) | 6.90 | 6.91 | 6.91 | — | ≥3.0 |
| G1 server CPU-s/1k cols cut | +43.3% | +45.4% | +42.3% | **+43.3%** | ≥+10% |
| G1t zip-band CPU/col cut | +89.7% (222→23 µs) | +90.2% | +87.2% | **+89.7%** | ≥+50% |
| G2 client CPU/col ratio | 0.974 | 0.961 | 1.008 | 0.974 | ≤1.05 |
| G3 socket bytes ratio | 1.114 | 1.114 | 1.115 | **1.114** | ≤1.15 |
| G3x counted-wire/acked (ON) | 0.98 | 0.98 | 0.98 | — | 0.7..1.3 |
| G4 columns parity | 0.06% | 0.01% | 0.02% | — | ≤5% |

The CPU claim lands ~3x above the Phase-0-derived floor (the warm-join workload is
store-hit-dominated, so the compression band is a larger whole-process fraction than
the conservative 3-4 ms/col estimate assumed); wire cost lands exactly on the
Phase 0 prediction (×1.12); the client is a slight net WIN, not just non-regressed.

### §5.2 CPU-proof — COLD arm: **PASS, 3/3 reps** (2026-08-01, stamp 20260801-134641)

`./scripts/compress_gate.sh cold 3 150` — no-cache, lodStore=off both arms (the
disk/live serve path, compress-replaces-deflate):

| Gate | rep1 | rep2 | rep3 | median | limit |
|---|---|---|---|---|---|
| G1 server CPU regression | **−27.2%** (a win) | −27.0% | −29.0% | −27.2% | ≤+5% |
| G1t zip-band CPU/col cut | +73.8% (307→80 µs) | +74.3% | +72.0% | +73.8% | ≥+50% |
| G2 client CPU/col ratio | 1.046 | 1.015 | 0.993 | 1.015 | ≤1.05 |
| G3 socket bytes ratio | 1.115 | 1.114 | 1.115 | 1.115 | ≤1.15 |
| G3x / G4 / P | 0.98 / ≤0.07% / 6.90 | — | — | — | all green |

The "parity-or-better" cold gate is a clean WIN: zstd-1 + deflate-over-frame costs
~27% less whole-process server CPU per column than deflate-over-raw, matching the
Phase 0 live-serve premise (551→101 µs in-band). Both §5.2 arms are now green —
the CPU-reduction claim is PROVEN under the review-hardened gates.

### §5.1 correctness — Fabric soaks: **ALL PASS** (2026-08-01)

Chain (compression on, the default): fresh-backfill, warm-rejoin, store-second-join,
store-second-join-full, dirty-broadcast — every conservation law (A1–A7, B2),
quiescence, and the store byte-parity gates green under compressed serving.

**Kill-switch A/B** (`"useCompressedColumns": false` staged into the
store-second-join scenario config; the key added to check_soak's config allowlist):
raw arm PASS, 0 violations, columns identical (4472 == 4472) — decoded-content
parity across the flag holds.

**RTT rider (plan §5.1 / R6), measured 2v2**: ON 275/265 ms vs OFF 231/231 ms p50 on
the scenario's cold-generation tail (~+17%, reproducible — ON-ON spread 4%). This is
the anticipated R6 mechanism: the processing thread now pays ~50 µs/col compressing
gen serves (~0.2 s total across the 4.5k-column burst), visible on the tail answer of
a 3-minute cold-join storm. Bounded, all laws green, columns equal, and the paired
CPU gates show the server saving 27–43% whole-process CPU for it — recorded as the
accepted trade, not a regression. (The warm/store path shows no such stretch: store
frames ship verbatim with ZERO processing-thread compress.)

### §5.1 correctness — Paper soaks: **ALL PASS** (2026-08-01)

`SOAK_PLATFORM=paper ./scripts/soak.sh all` against a real Paper server:
fresh-backfill, warm-rejoin, dimension-trip, paper-dirty-falling-block,
paper-store-unfired-event — 0 violations, 0 warnings across all five. Compressed
serving is live-validated on the Paper platform (twin build path + splice guard +
frame rung).

### Release pre-flight: **OK** (2026-08-01)

`CI=true :fabric:build -x runClientGameTest :paper:test :paper:shadowJar` green;
`release_check.py --version 0.8.0` → `OK: release artifacts clean` (incl. the
LSS↔VSS wire-identity pin — the compressed-columns wire surface is brand-invariant
for free, as plan §6 predicted). Stale 0.9.0 jars from earlier local builds deleted.

## §5.3 LIVE ACCEPTANCE — PASS (2026-08-01, Modrinth test server)

Deployed to the live Fabric 26.2 server (Moonrise + Lithium + FerriteCore, store
`full`, 256-chunk LOD distance) and driven by a real player session. `/lsslod diag`
over RCON after 8m20s / 31,885 columns served:

```
Bandwidth: 20.9 MB/s / 300.0 MB/s global (1000.8 MB total, 157.1 MB wire,
                                          cols zstd=32312 raw=0)
DiskReader: … store=full h=31933 m=139 dep=139 drop=0 err=0 avg_read=31us
```

- **Wire ratio 6.37:1** (1000.8 MB raw-counted → 157.1 MB actually shipped) — within
  0.6% of the Phase 0 corpus prediction for zstd-1 (6.33:1). The design's headline
  observability goal is met: `wire` is the number that matches the host's bandwidth
  graph, `total` is the raw-denominated limiter charge, and the gap between them IS
  the compression.
- **cols zstd=32312 / raw=0** — every single column negotiated codec 1 end-to-end
  through a real client. The capability handshake, the codec byte, and the client
  decompress drain all work live.
- **Store frames served verbatim: 31,933 hits, 139 misses, `err=0`, 31 µs avg** — the
  Phase 2 frame path (fhash validation, no decompress) is live-proven at scale on a
  ~3.8 GB store; zero integrity failures, zero purges.
- No LSS warnings or errors in the boot log; `read_path=moonrise-low` unchanged.
- Note the per-player cap is charged in RAW bytes by design (§5): the session ran at
  the 20.9 MB/s configured cap while using only ~3.3 MB/s of actual network.

**The schema-v3 upgrade + full re-backfill are live-verified end to end** (traced
through the rotated log archives; each restart rotates `latest.log`, so the history
spans four files):

| Boot | Event |
|---|---|
| 21:11 | New jar → `LOD store: schema/wire/version drift — dropping and rebuilding` → backfill plans **676 regions / ~3.6 GB** → walks 487, then shutdown |
| 21:35 | Backfill **RESUMES at the 200 remaining** regions off the done-marks → **completes all 200** (72,501 deposited, 1 MSPT pause) |
| 21:51 | `0 region(s) to process` — genuinely complete |
| 21:54 | Meta matches (3/19) → no rebuild, 0 regions — correct steady state |

So the drop-and-rebuild convention, the resume-from-marks walk, and the ~3.8 GB
re-warm all worked as designed on a real 676-region world — the one live behavior no
test tier covers. (Trap for future triage: `0 region(s) to process` on the CURRENT
boot says nothing about whether a re-walk happened; the evidence is in the dated
`logs/*.log.gz` archives, one per restart.)

## 4-agent implementation review (2026-08-01) — folded

Four parallel lenses over the full branch diff (wire/client, server pipeline,
store/schema, tests/tooling): **zero product-code MAJORs**; the two MAJORs were
promised-pin gaps, both closed same-day. Fixes applied (commit `fix: 4-agent review
round …`):

- **Probe-path per-delivery containment** (pipeline F1): a `frame()` throw in the
  router's probe serve no longer fails the whole processing cycle (counted
  superseded, pass continues).
- **Frame-rung contract belt** (store F1): a data-claiming `FrameHit` with no frame
  reads as an errored miss, never a fabricated all-air clear.
- **Non-shrinking store frames refused** (wire F1): pre-built frames now honor the
  shipped<raw invariant — law A2's exactness holds for degenerate stored frames.
- **Tier 3 C7b read-order** (wire F2) + `wireEstimatedBytes` null-safety + v16-guard
  comments now state the reachable downgrade window + benchmark client key unified.
- **Promised-pin MAJORs closed**: the oversized-usize FRAME-shape refuse pin
  (refuses on `rawSize()` alone, no decompress — `CompressedColumnBuildTest`), and
  the probe-hash-raw discriminating soak — `store_offline_edit.sh` run under
  compression: **PASS** (edited probe re-served fresh, control probe byte-identical
  across the raw-NBT and store-frame legs).
- Gate-tooling hardening: compress_gate_check selftest 8 cases incl. the 3-rep
  majority rule; RSS + empty-client-JFR report lines; NativeMethodSample caveats
  documented in analyze_profile_jfr.py; plan-text corrections (getFrame default =
  plain miss, threshold strictly-below).
- Post-fix re-validation: Tier 1 both platforms + Tier 2 green.
- Recorded-not-fixed (reviewer-accepted): store frames bypass the live path's
  sub-512 floor (bytes-only, within all caps); MemoryLodStore frame aliasing note;
  v16-downgrade drops book send-success accounting (bounded to the downgrade
  instant); usize-rotted-to-0 all-air short-circuit (pre-existing get() parity);
  window-logic triplication in the gate scripts; G2 zero-falsiness laxity.

## Remaining (deployment-gated, pending user action)

- ~~**§5.3 live acceptance**~~ — **DONE 2026-08-01, PASS.** See below.
- **Merge + release**: branch is NOT merged. On release, the notes must carry (plan
  §6): the CPU win with the measured numbers, the ~+11.5% wire-bytes trade stated
  honestly (never "wire drop"), the one-time store rebuild (schema v3), the compat
  matrix incl. v16-shim degradation for older pairings, protocol 19.
- **Paper CPU numbers are inferred**, not measured (plan §5.2: compress_gate rides
  the Fabric-only benchmark harness; Paper correctness is soak-gated) — optional
  report-only sampler attach documented in the plan if ever wanted.

## Decisions log

- 2026-08-01: branch created off `feat/lod-store` (Phase 2 depends on the store).
- 2026-08-01: Phase 0 reuses the `lss.store.experiment.*` gradle property
  pass-through rather than adding a parallel one — the tool self-gates identically.
