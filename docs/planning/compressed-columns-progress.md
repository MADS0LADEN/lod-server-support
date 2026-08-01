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
| 3 — observability (wire_bytes, exporters, soak schema) | not started | |
| 4 — validation (tests, soak, compress_gate.sh) | not started | |

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

Not started. Work list tracked in plan §4.

## Phase 4 — validation

Not started. Work list tracked in plan §5 (correctness + the compress_gate.sh CPU
proof: G1/G1t/G2/G3/G4, premise pins, NativeMethodSample tooling fix).

## Decisions log

- 2026-08-01: branch created off `feat/lod-store` (Phase 2 depends on the store).
- 2026-08-01: Phase 0 reuses the `lss.store.experiment.*` gradle property
  pass-through rather than adding a parallel one — the tool self-gates identically.
