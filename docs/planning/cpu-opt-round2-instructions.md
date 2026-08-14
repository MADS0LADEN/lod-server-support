# Instructions: CPU optimization round 2 (disk-read serving path)

**Status: EXECUTED — historical.** Round 2 shipped (PR #74, `useNbtTranscode` default
true) and later perf rounds superseded this work order. Do not execute; kept as the
methodology record. (Banner added by the 2026-08-13 staleness sweep.)

You are continuing a validated CPU-optimization effort on LSS's disk-read LOD serving
path. Round 1 is DONE and measured; your job is round 2: implement the next tier,
validate byte-identity through the existing gates, and prove the CPU win with the same
profile harness. This document is self-contained — read it plus the two companion docs
before touching code:

- `docs/planning/disk-read-profile-2026-07-29.md` — the profile, the harness, round-1 results.
- `docs/planning/nbt-transcode-design.md` — the verified design you will implement (Tier 2).

## Context in three paragraphs

A JFR profile of saturated disk-read serving (37k columns at ~570/s) showed ~62% of all
server CPU in the NBT→wire column assembly inside `NbtSectionSerializer` (Fabric) /
`PaperNbtSectionSerializer` (Paper twin). Round 1 (commit `1576f73` on branch
`perf/disk-read-profile`) removed three chains while keeping wire bytes byte-identical:
a memoized palette-entry decode codec (`MemoizedNbtCodec` twins), a headless section
write (no `LevelChunkSection` construction on the unmasked disk path; the two wire count
headers come from an `int[paletteSize]` histogram), and exact-size zero-copy serialize
buffers. Validated result: serializer-band CPU roughly halved, whole-recording exec
samples -23-30%, sampled allocation -28%, wire bytes identical through every gate.

What measurably remains: the container-LEVEL codec plumbing (RecordCodecBuilder/
ListCodec/NbtOps traversal + boxed long-array decode — the element memo cannot reach it)
and the raw NBT tag load. The transcode design doc eliminates the former; its Stage A is
already landed, you implement Stages B and C (per-section NBT→wire transcoder with a
per-section fallback ladder, behind a `useNbtTranscode` rollback flag).

Everything runs on the branch `perf/disk-read-profile` (local, not pushed). Baselines to
beat: `profile-results/20260729-optimized` (post-round-1; vanilla ~1.78 CPU-s/1k cols,
c2me ~1.59). The pre-round-1 baseline is `profile-results/20260729-152424`.

## The measurement workflow (do this exactly)

1. **Preconditions**: `pgrep -af 'KnotServer|quickPlayMultiplayer'` must be empty; port
   25565 free; `benchmark-worlds/base` must exist (45,589 chunks, full square R=105 — if
   missing, rebuild: `BENCHMARK_SERVER_GRADLE_ARGS="-Pbenchmark.c2me=true"
   ./scripts/benchmark.sh fresh 900`, ~16 min).
2. **Profile matrix**: `RUN_STAMP=<yyyymmdd-label> ./scripts/profile_disk_read.sh matrix 2 300 96`
   (~30 min, runs 2 reps × {vanilla, c2me} interleaved; JFR + CPU sampler collected per
   run; per-run arm validity is checked automatically via the C2ME fallback warn).
3. **Analyze**: `python3 scripts/analyze_benchmark_compare.py analyze profile-results/<stamp>`
   (CPU-s/1k table) and `python3 scripts/analyze_profile_jfr.py compare profile-results/<stamp>`
   (windowed hot methods/threads/allocation per run + `flame.collapsed` files).
4. **Bucket comparison**: aggregate the `flame.collapsed` files with category regexes and
   compare against the same aggregation of the baseline stamp (the round-1 session's
   categories are reproduced in the report doc). Judge success primarily on BAND-level
   deltas and total exec-sample counts, not the whole-JVM CPU-s/1k (see pitfalls).

## Acceptance criteria for the round

- All existing tests green: `./gradlew :fabric:test :paper:test -x runGameTest -x
  runClientGameTest` then `./gradlew :fabric:runGameTest` (Tier 2 gametests — the
  disk-vs-live and masked byte-parity tests are the decisive end-to-end gates).
- Wire bytes byte-identical: every `golden_*` corpus test, the Paper
  `goldenCorpusIsByteIdenticalToTheFabricTwin` diff, `WireParityTest` both modules,
  the xray fixtures. New golden cases are ADDITIVE only (regen flow:
  `-Dlss.regenGoldens=true`, commit both modules' fixtures together).
- One `fresh-backfill` soak green per platform (`./scripts/soak.sh fresh-backfill`,
  `SOAK_PLATFORM=paper ./scripts/soak.sh fresh-backfill`) — byte drift disk-vs-live
  surfaces as up-to-date-economy anomalies the checker catches.
- Profile matrix shows the targeted band shrinking without any other band growing
  beyond noise, and no `queue_full`/`not_found`/`errors` regressions in server.json.
- Commit on `perf/disk-read-profile` with the same commit-message style as `1576f73`.

## Mistakes made in round 1 — do not repeat them

1. **`jfr print` defaults to `--stack-depth 5`.** That truncated caller frames and
   mis-bucketed the biggest hot chain for half a session. `analyze_profile_jfr.py` now
   passes `--stack-depth 64` — don't regress it, and be suspicious of any stack that
   looks root-less.
2. **There are MULTIPLE decompiled-MC caches on this box, for different MC lines.**
   `~/.gradle/caches/paperweight-userdev/v2/work/setupMacheSources_*` — one of them is
   NOT 26.2 and shows a ONE-short `LevelChunkSection.write`. Verify the cache before
   trusting it (26.2 has the `fluidCount` field and writes TWO shorts). Rule: never
   assert a vanilla wire/format fact from memory or from the first cache found —
   decompile and check a version-distinctive symbol first. Paper facts come from
   `paper/.gradle/caches/paperweight/taskCache/mappedServerJar.jar` (javap it).
3. **Fabric access-widener namespace is `official`, not `named`** (this repo uses Mojang
   mappings). The existing `fabric/src/main/resources/lss.accesswidener` is the example.
4. **Paper is NOT vanilla**: Moonrise makes `PalettedContainer.data` public (no AW
   needed) but its `LevelChunkSection` ctor takes the RW biome container where vanilla
   26.2 takes RO — the twins genuinely differ in small signatures. Compile Paper early,
   not after finishing Fabric.
5. **Benchmark worlds save at whatever in-game time the build run ended** — a 900 s
   build saves NIGHT, and a hostile mob killing the idle player silently freezes all
   client declarations (`tick()`'s `isDeadOrDying` guard): frozen `send_cycles`,
   `tracker_in_flight` 0, session otherwise healthy. `benchmark.sh` now stages
   `difficulty=peaceful`; if you ever see a truncated run, grep server.log for
   "slain by" BEFORE suspecting LSS.
6. **The box is shared and noisy (WSL2, and the user works on it concurrently).**
   Never compare across days or across box states. Interleave arms within one matrix,
   check the analyzer's `noise_cores` and gate warnings per run, and expect the
   vanilla-tick band to wobble ±30% between matrices — that wobble ate most of round 1's
   whole-JVM delta. Judge band-level exec-sample counts, allocation, and the targeted
   chains; treat CPU-s/1k as a secondary, noisy aggregate. If numbers look wrong, re-run
   the matrix rather than arguing from one run.
7. **Throughput will NOT improve and that is expected**: the serve rate rides the
   20 MiB/s per-player bandwidth default (~590 col/s at ~33 KB/col). CPU wins land as
   CPU-per-column and headroom, not col/s. Don't chase col/s.
8. **Client-side JFR is useless here** — the benchmark client exits via `halt(0)` and
   the recording ends up 0 bytes. Server JFR + the soak-style client snapshots
   (`queued_bytes` is now exported) are the observability.
9. **Check the pinned-decision culture before "fixing" anything.** This repo pins
   deliberate tradeoffs with tests and documents them in CLAUDE.md; several plausible
   optimizations are DESIGNED-OUT and were explicitly rejected in round 1's analysis:
   - Do NOT suppress or skip want-set re-declarations (client or server side) — the 1 Hz
     re-declare is the protocol's only self-heal; skipping identical batches breaks the
     ts≤0 honest re-resolution and the soak laws.
   - Do NOT swap `enqueuedColumns`/`departedColumns` to non-concurrent maps — they are
     legitimately cross-thread; the boxing is the price (measure-first if ever).
   - Do NOT pool `SnapshotBuffers`/per-tick maps — fresh allocation is the pinned
     ownership-transfer contract.
   - Do NOT suppress the loaded-chunk probe to save serialization — a suppressed probe
     falls through to a DISK read of a loaded chunk (stale-data hazard).
   - The masked x-ray path must keep constructing real sections (mask headers can only
     be recomputed by the counting ctor — the fluid gotcha cuts both ways).
10. **Estimates vs reality**: agents estimated round 1 at ~40% total CPU; the honest
    measured number was band-halving and -23-30% exec samples, diluted in the whole-JVM
    metric. Calibrate round-2 promises accordingly and report what the buckets actually
    show, including what did NOT move (the container-plumbing samples were the honest
    "didn't move" of round 1 and they define your round).

## Your work plan

1. Read the two companion docs. Skim `NbtSectionSerializer.java` /
   `PaperNbtSectionSerializer.java` as they are TODAY (post-round-1 headless shape) —
   the transcoder replaces their parse+write core, and the Tier 1 memo
   (`MemoizedNbtCodec`) becomes your palette-id resolver (extend its cached value with
   `globalStateId`/`isAir`/`hasFluid`).
2. Implement Stage B then Stage C from `nbt-transcode-design.md` (new goldens FIRST,
   generated by the current path; per-section fallback ladder; `useNbtTranscode` flag
   default true with the object path as the permanent fallback rung).
3. Gate per the acceptance criteria, then run the profile matrix and write the
   before/after bucket comparison into the profile report (follow the round-1 section's
   format).
4. If Stage B/C lands with margin to spare, the smaller validated leftovers from the
   round-1 investigation, in value order: the Paper frame-assembly copy kill
   (`PaperPayloadHandler.encodeVoxelColumnPreEncoded` exact-size + array steal — one
   full-column copy per Paper column), the router micro-wins (hoist per-dimension
   timestamp-cache resolution + packed-long overloads on the duplicate ladder; direct
   `IncomingRequest[]` ingress instead of ArrayList+toArray; return
   `Long2ObjectMaps.emptyMap()` for converged players' probe maps), and an 8-byte-stride
   `DirtyContentFilter.fnv1a64` (save-path only — hash values are never persisted).
   Each is small; gate each with its named tests.

## Files that matter

- Serializers: `fabric/src/main/java/dev/vox/lss/networking/server/NbtSectionSerializer.java`,
  `MemoizedNbtCodec.java`; `paper/src/main/java/dev/vox/lss/paper/PaperNbtSectionSerializer.java`,
  `PaperMemoizedNbtCodec.java`. Keep the twins textually parallel — house style.
- Tests: `NbtSectionSerializerTest` both modules (goldens + the round-1 fuzz pin
  `headlessWriteMatchesLevelChunkSectionWriteForRandomizedSections` — your transcoder
  must keep passing it via the fallback-vs-transcode equivalence), `XrayMaskFilterTest`
  twins, `WireParityTest` twins, `SerializerParityGameTests` (Tier 2).
- Harness: `scripts/profile_disk_read.sh`, `scripts/analyze_profile_jfr.py`,
  `scripts/analyze_benchmark_compare.py`, `scripts/benchmark.sh`.
- Config: `common/.../config/ServerConfigBase.java` (add `useNbtTranscode` here — both
  platforms inherit; follow the `useBackgroundReadPriority` javadoc/rollback pattern;
  config tests pin defaults in `JsonConfigLoadTest` + `PaperConfigLoadTest`).
