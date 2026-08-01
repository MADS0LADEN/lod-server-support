# Compressed columns (protocol 19) — implementation progress

Plan: `compressed-columns-implementation-plan.md` (2-agent review folded, §9 there).
Design: `compressed-columns-design.md` (wire-size premise corrected 2026-08-01).
Branch: `feat/compressed-columns` (off `feat/lod-store`).

## Status

| Phase | State | Notes |
|-------|-------|-------|
| 0 — premise measurement | **DONE 2026-08-01** | all premises confirmed; numbers below |
| 1 — protocol v19 + capability + live-path compression | not started | |
| 2 — store frame serving (fhash, getFrame, deposit reuse) | not started | |
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

Not started. Work list tracked in plan §2.

## Phase 2 — store frame serving

Not started. Work list tracked in plan §3.

## Phase 3 — observability

Not started. Work list tracked in plan §4.

## Phase 4 — validation

Not started. Work list tracked in plan §5 (correctness + the compress_gate.sh CPU
proof: G1/G1t/G2/G3/G4, premise pins, NativeMethodSample tooling fix).

## Decisions log

- 2026-08-01: branch created off `feat/lod-store` (Phase 2 depends on the store).
- 2026-08-01: Phase 0 reuses the `lss.store.experiment.*` gradle property
  pass-through rather than adding a parallel one — the tool self-gates identically.
