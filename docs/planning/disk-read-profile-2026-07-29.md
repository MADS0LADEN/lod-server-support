# Disk-read serving profile — vanilla IOWorker vs C2ME (Fabric, 2026-07-29)

Server CPU profile of the steady-state "chunks already generated, served from disk at high
rate" case, A/B'd between plain Fabric (LSS background-priority IOWorker reads) and C2ME
(LSS's chunk-IO-overhaul fallback: `chunkMap.read` + AdaptiveReadThrottle). JFR
(`settings=profile`) on the server for every run, external 1 Hz CPU/wire sampler, analysis
windowed to the active serving phase.

## Harness

- `scripts/profile_disk_read.sh` (new) — drives `benchmark.sh no-cache` per arm with the
  compare-harness staging: generation OFF, cold client cache each run, R=96 staged into both
  configs, 20 MiB/s per-player / 100 MiB/s global bandwidth, 5 disk reader threads.
- C2ME arm: `-Pbenchmark.c2me=true` (new, `fabric/build.gradle`) puts the same C2ME version
  `test-server.sh` installs (0.4.2-alpha.0.9+mc26.2, Modrinth `nvOkOiyi`) on the Loom dev
  runtime via `localRuntime`; `benchmark.sh` forwards it through
  `BENCHMARK_SERVER_GRADLE_ARGS` to the SERVER invocation only.
- Base world: 45,589 chunks, full Chebyshev square R=105 around spawn
  (`benchmark-worlds/base`, built by a 900 s C2ME-accelerated fresh run, ~50 gen/s).
  R=96 leaves margin; every profiled run read `not_found=0, errors=0, generation=0` —
  pure disk-read + serialization serving of the full 37 k-column disc.
- Arm validity is checked per run: the C2ME arm must log LSS's one-time
  "Background-priority disk reads unavailable" fallback warn (it did, both reps); the
  vanilla arm must not (it didn't). This pins that the C2ME arm really exercised the
  fallback path (`ChunkDiskReader.onBackgroundIncompatible` → adaptive throttle), live.
- JFR analysis: `scripts/analyze_profile_jfr.py` (new) — streams `jfr print`, filters
  events to the active window detected from the wire-byte slope (same detection as
  `analyze_benchmark_compare.py`), reports exec-sample shares, hot stacks, allocation,
  GC, slow file reads; writes `flame.collapsed` per run for flamegraph tooling.
- Results: `profile-results/20260729-144020/` (4 runs × {server.json, client.json,
  server-benchmark.jfr, cpu.jsonl, flame.collapsed, meta.json}, plus `jfr-report.md`).

## Headline numbers

Final matrix `profile-results/20260729-152424/` (after the difficulty=peaceful harness fix
— §anomaly below; the first matrix `20260729-144020` had 2 of 4 runs truncated by it).
All four clean runs served the full disc with tight within-arm agreement; per-rep values
below, medians in bold.

| | vanilla rep1 / rep2 | c2me rep1 / rep2 |
|---|---|---|
| columns delivered | 37,295 / 37,274 | 36,864 / 36,877 |
| active window | 66 / 65 s | 62 / 63 s |
| serving rate | 565 / 573 → **569 col/s** | 595 / 585 → **590 col/s** |
| wire throughput | **18.8 MB/s** | **19.4 MB/s** |
| server CPU (idle-corrected) | 1.91 / 1.83 → **1.87 CPU-s/1k cols** | 1.58 / 1.85 → **1.71 CPU-s/1k cols** |
| disk avg read | 2.26 / 2.17 ms | 0.76 / 0.85 ms |
| GC in window | 410 / 384 ms (163 / 152 pauses) | 179 / 208 ms (60 / 62 pauses) |
| sampled allocation in window | 29.5 / 27.7 GB | 28.4 / 29.2 GB |
| peak RSS | 1197 / 1257 MB | 1575 MB both |

(The ~420-column between-arm delivery difference is the in-memory-probe band: C2ME's
notickvd changes how much terrain sits loaded/tracked around the stationary player.)

**Verdict: near-parity.** The C2ME fallback path costs no measurable server CPU per column
(if anything ~4% less in this sample) and the AdaptiveReadThrottle did not limit throughput
at this load. C2ME's storage layer returns page-cache-warm reads ~2.5× faster than the
IOWorker BACKGROUND-priority queue (0.8 vs 2.1 ms — the vanilla number includes queueing
behind vanilla's own IO), but at 5 reader threads neither IO path is close to being the
bottleneck. The delivered rate in BOTH arms tracks the 20 MiB/s per-player bandwidth cap
(≈33 KB/column → ~590 col/s ceiling): **the shipped bandwidth default, not IO and not CPU,
is what bounds cold-backfill speed on this hardware.** (Config allows up to 100 MiB/s
per-player; a higher-cap profile would move the binding constraint to CPU/decode.)

## Where the CPU goes (JFR exec samples, active window)

Thread shares are the same shape in both arms — the platform IO layer swaps, everything
else holds:

| thread group | vanilla | c2me |
|---|---|---|
| LSS Disk Reader pool | 51.7% | 56.4% |
| Server thread (vanilla ticking + probe serves) | 30.3% | 28.4% |
| IO layer (IO-Worker / c2me-worker + C2ME Storage) | 11.9% | 13.1% |
| Netty / misc | ~4% | ~2% |
| LSS Processing Thread | 0.3% | 0.3% |

Top hot path, both arms, by a wide margin — **`LevelChunkSection` construction during NBT
parse**, i.e. `NbtSectionSerializer.parseSection` → `LevelChunkSection.<init>` →
`recalcBlockCounts` → `PalettedContainer.count` → `SimpleBitStorage.getAll` +
`Int2IntOpenHashMap.addToValue`. That single chain is ~40% of the disk-reader pool's
samples (~21% of all server CPU) in both arms.

Second tier: the NBT/DFU decode itself (CompoundTag loads, `DataInputStream.readUTF`,
DataResult/Codec plumbing, `Identifier.parse` validation) spread across the reader pool
and the IO layer; then wire-write (`FriendlyByteBuf.writeLong` / netty `ensureWritable0`).

Allocation (sampled weight, ~28 GB per ~62 s window in BOTH arms ≈ 450 MB/s churn):
`byte[]` ~9 GB, `DataResult$Success` ~3.6-4.0 GB, `long[]` ~1.6-2.2 GB, `String` ~1.3-1.6 GB,
`Pair`/HashMap nodes ~1-2 GB — the classic DFU-decode signature. GC keeps up easily
(sub-15 ms pauses, <1% of wall time), so this is CPU cost, not pause risk.

### Optimization round 1 — IMPLEMENTED and validated (commit 1576f73, matrix `20260729-optimized`)

Three of the leads below landed the same day (memoized palette decode, headless section
write with histogram counts, exact-size zero-copy buffers — both platforms, wire bytes
byte-identical, all goldens/parity/Tier-2 gates green). Validation matrix under identical
conditions vs baseline `20260729-152424`:

- **Serializer-band CPU (the target) roughly halved**: whole-recording exec samples
  -23-30% per vanilla run; the recount band 22.6% → 10.1% (residual = the honest
  histogram pass); `Identifier`/blockstate entry decode samples -78% (memo hit rate);
  netty grow-copies eliminated (zero size-mismatch fallbacks across all runs). The LSS
  reader pool is no longer the top thread — vanilla server-thread ticking now leads.
- **Sampled allocation -28%** (~28.4 → ~20.3 GB per active window).
- **Idle-corrected CPU-s/1k cols: vanilla 1.87 → 1.78 (-5%), c2me 1.71 → 1.59 (-7%)** —
  the whole-JVM number moves less than the band numbers because it includes vanilla
  ticking (which varied +30% absolute between matrices, eating much of the gain on this
  box) plus GC/JIT threads the exec sampler doesn't attribute. Throughput unchanged
  (bandwidth-bound by design).
- **What's left, measured**: the container-level codec plumbing barely moved (485 → 446
  samples — RecordCodecBuilder/ListCodec/NbtOps traversal + the boxed LONG_STREAM
  data-array decode, a per-section fixed cost the element memo cannot reach) and the raw
  NBT tag load (~15%). Killing those is exactly the already-designed Tier 2 NBT→wire
  transcode (agents' report: staged plan, per-section fallback ladder, additive goldens
  — est. 3-5 days, on the order of a further 25-35% of server CPU).

### Optimization round 2 — NBT→wire transcode, IMPLEMENTED and validated (2026-07-30)

Stages B+C of `nbt-transcode-design.md` landed (`useNbtTranscode`, default true): the disk
path skips the container codec entirely — palette global ids resolved through the extended
element memo, count headers from a raw histogram over the disk longs, palette + long array
emitted verbatim; per-section object fallback for Global configs / malformed data /
mask-needing sections. Byte identity gated by six new object-path-generated goldens
(two-entry/hashmap/global block tiers, duplicate-air, biome 3-bit/global), a 32-round
transcode-vs-object fuzz, the corpus + parity + Tier-2 disk-vs-live and masked gates.

**Measurement: same-night A/B, not cross-day.** Validation ran TWO matrices back-to-back
under identical conditions (`20260730-objectpath` staged `PROFILE_NBT_TRANSCODE=false`,
`20260730-transcode` the new default) because the whole-JVM wobble this file warns about is
real: the object-path matrix measured vanilla 1.60 CPU-s/1k the same evening that the SAME
code path measured 1.78 in the morning matrix — a cross-day compare would have minted a
phantom ~10% win. Bucket aggregation over `flame.collapsed` with shared category regexes
(first-match: recount/transcode/entry-decode/container-plumbing/nbt-load/serializer-other/
wire-send/vanilla-tick), LSS-attribution split by serializer frames.

- **The round-2 target is eliminated, not just shrunk**: LSS-attributed container-plumbing
  samples (RecordCodecBuilder/ListCodec/NbtOps/DataResult/LONG_STREAM under the serializer)
  354 → **0** (vanilla), 552 → **3** (c2me). The surviving "plumbing" band (~65-114
  samples/arm, both matrices) is vanilla's own ForkJoinPool codec work, present on both
  sides. Palette-entry decode −96/−99%; the recount/histogram band −92/−98% (its residual
  is the transcoder's own in-band histogram).
- **Whole LSS serializer band**: vanilla 920 → 540 samples (35.1% → 24.2% of all exec
  samples, −41%); c2me 1246 → 988 (41.9% → 35.5%, −21%). Total windowed exec samples −15%
  (vanilla) / −5% (c2me).
- **Sampled allocation −22% / −17%** (vanilla ~19.6 → ~15.2 GB, c2me ~20.1 → ~16.6 GB per
  active window) — the DFU DataResult/Pair/boxed-long churn is gone; `byte[]` now dominates
  what remains (the wire buffers themselves).
- **What did NOT move, as predicted**: the raw NBT tag load (−1%/+2% — now the largest
  serializer-side residual at ~19-23% of samples; killing it needs the custom region
  reader, out of round-2 scope), vanilla ticking (+4-5%, the known wobble band), wire send
  (noise-level counts). `queue_full`/`not_found`/`errors` all 0 in every run.
- **Whole-JVM CPU-s/1k is a wash** (vanilla 1.60 → 1.58, c2me 1.57 → 1.56): at this
  dilution the serializer-band win sits inside the box's day wobble — same lesson as
  round 1, judged on bands and allocation, not the aggregate. Throughput unchanged
  (bandwidth-bound by design; ~565/590 col/s both matrices).
- The cross-day compare against the official `20260729-optimized` baseline tells the same
  structural story (plumbing −73/−85%, entry decode −97/−99%, totals −21/−9%) and is
  recorded only as corroboration.

**Soak note (fresh-backfill, the same evening):** Paper PASS through the transcoder (40/40
windows). Fabric redded A7-only three times at exactly +5 = `diskReaderThreads` (one >10 s
IOWorker stall expiring all five blocked readers mid-gen-wave; reads averaged 107-131 ms
all evening vs ~2 ms in every profile run — the box was in the documented degraded-IO
state), with a `useNbtTranscode:false` control green between them and every byte-economy
law (A1-A6 + client laws) green in every run. Triage recorded in CLAUDE.md's A7 catalog:
the freed reader-pool CPU is a memo-style acceleration of the gen-save flood on a
constrained box, not byte drift — a transcoder byte bug would fire A5/up-to-date-economy
anomalies, never A7.

### Optimization leads (round-1 source material; transcode Tier 2 still open)

1. **Skip or cheapen `recalcBlockCounts` on the disk path.** The section ctor recounts
   4096 cells into a fastutil histogram per section purely so `write()` can emit
   `nonEmptyBlockCount`. ~21% of server CPU during backfill. Options: transcode NBT →
   wire directly without constructing `LevelChunkSection` (palette + bit storage are
   already nearly wire-shaped), or count from palette frequencies without the
   per-cell histogram. Wire bytes must stay identical (golden corpus + parity tests
   pin this) — the xray masking hooks sit inside these serializers, so any transcoder
   must preserve the mask choke points.
2. **DFU/Codec block-state decode churn** (DataResult/Pair/String allocation) — a
   palette-string intern/memo cache across sections of the same read burst would cut a
   large slice of the ~450 MB/s allocation rate; palettes repeat heavily across sections.
3. Not worth touching: GC (already cheap), the IO layer (not the bottleneck at either
   priority), send path (~5% band).

## Bandwidth-cap backpressure experiment (2 MiB/s, vanilla arm)

`profile-results/20260729-lowbw/vanilla-rep9`: same staging with
`bytesPerSecondLimitPerPlayer` dropped 10× to 2 MiB/s (via the new
`PROFILE_BW_PER_PLAYER` override). Question under test: does the cap backpressure the
disk pipeline, or does capped serving do work that gets thrown away?

Measured (285 s active window, run did not converge by design):

- Delivered 16,855 columns at 59.1 col/s = 1.9 MiB/s — pinned to the cap (9.6× down).
- **Disk reads: 17,340 submitted = delivered + 454 still buffered at halt. Zero repeat
  reads, zero errors, zero drops.** The client re-declared 227,097 positions over 284
  one-second cycles; all but ~2% resolved as in-pipeline duplicates (map lookups) or
  cheap `up_to_date` answers. Completed reads are never discarded by a new want-set.
- Server CPU: 0.26 cores avg vs 1.17 at the full cap — a 4.5× drop for a 9.6× rate cut.
  Idle-corrected per-column cost ≈ 2.8 CPU-s/1k vs 1.87 at full cap; the gap is
  duration-proportional overhead (ticking, plus processing the 1 Hz all-duplicate
  re-declarations all run long), not pipeline waste.
- `queue_full` (the router's send-queue admission gate) fired **zero** times — the
  4000-entry send queue never came close to filling. **For a single player the client's
  own bounded want-set (WANT_SET_BUDGET = 800 declared positions) is the binding
  backpressure**: once every declared position is admitted/enqueued, later declarations
  are pure duplicates and admission stops until deliveries free want-set slots. The
  send-queue gate is the second line (multi-player / larger-want-set shapes), the disk
  headroom gate the third. Read-ahead over the wire is bounded to ~a want-set (~500
  columns observed), so the low-cap RAM cost is small — the 4000-entry queue worry from
  the pre-measurement analysis does not materialize single-player.

### Forcing the server-side gate (`20260729-queuegate/vanilla-rep8`)

The run above never engaged the server-side mechanism, so it only proved the client-side
bound. A second run staged `sendQueueLimitPerPlayer: 100` (below the 800 want-set and the
200 slot cap, via the new `PROFILE_SEND_QUEUE` override) with the same 2 MiB/s cap, forcing
the router to face declared-but-unadmittable entries every cycle. Pre-registered
predictions, all met:

| prediction | measured |
|---|---|
| `queue_full` > 0 (was 0 in every prior run) | **5,598** ≈ once per 20 Hz cycle, all run |
| delivery rate unchanged (bandwidth still the limiter) | 16,890 cols, 59.1 col/s, 1.91 MiB/s — identical to the ungated run |
| reads stay exactly-once, no drops | submitted 16,678 (+ 477 probe serves), completed 16,678, errors 0 — **fewer reads than columns delivered**; ~244 buffered at halt (queue cap + slot-cap overshoot) |
| `superseded` climbs (gate retains → 1 Hz replace supersedes) | **155,173** vs 1,945 ungated |
| CPU unchanged | 74.98 s over 286 s = 0.26 cores (ungated: 74.11 s / 285 s) |

So with the gate genuinely engaged every cycle: same wire rate, same CPU, zero repeat
reads, zero losses — the retain-don't-bounce design holds under sustained saturation; the
cost of a gated cycle is counters and an O(1) retained-entry restore.

**Outcome (landed):** `sendQueueLimitPerPlayer` default changed 4000 → 1024
(`= MAX_BATCH_CHUNK_REQUESTS`, `ServerConfigBase`). Rationale: under v17 replace semantics
a player's backlog is at most one wire batch, so enqueued payloads structurally cannot
exceed 1024 — at this default the gate stays unreachable for any legal client (v17 clients
declare 800; the v16 shim's synthetic want-set also caps at 800) while worst-case
buffered-payload RAM drops ~4× per player. Kept configurable (ops can still shrink it —
measured harmless — or raise it). Note: only fresh installs get the new default; an
existing config file's saved 4000 is honored. Release notes should carry this as a
Configuration item.

## C2ME-specific observations

- The fallback latch works exactly as designed on real C2ME 0.4.2 in the 26.2 dev
  runtime: single warn, `chunkMap.read` route, throttle armed; no NPE storm (the
  f65a447 containment holds).
- C2ME adds its own visible costs on the read route: `C2MEStorageThread.getChunkData`
  and `NewChunkHolderVanillaInterface.getTickingChunk` (~3% of samples together) —
  bounded, and paid back by the faster storage reads.
- `useBackgroundReadPriority=false` (FOREGROUND vanilla reads) was not run this round;
  the July compare measured it (v17-fg arm) at parity with bg-priority on this box.

## Client early-stop "anomaly" (2 of 4 first-matrix runs) — RESOLVED: a zombie

Symptom: mid-backfill, the client stops declaring its want-set and never recovers —
`send_cycles` freezes (19-23 cycles in), `tracker_in_flight` drains to 0, the decode
queue reads 0 (count AND bytes — the byte gauge is newly exported as `queued_bytes` in
the client snapshot, added for this diagnosis), session stays enabled/overworld/LOD 96,
no ingest failures, no up_to_date/not_generated. The scanner's last-walk gauges stay
frozen at queue-pressure-scaled values (budget 796), proving `maybeScan` stopped being
reached at all — every in-ladder gate (backpressure halt, cache gate) was exonerated by
the exported state.

**Root cause: `tick()`'s `player.isDeadOrDying()` guard, working as designed.** The 900 s
base-world build run saves `level.dat` at in-game NIGHT (18000 ticks); every `no-cache`
run copies that world, so every profile run started at night on default difficulty, and a
zombie sometimes reached the idle unarmored player at spawn ("PlayerNNN was slain by
Zombie" in exactly the two truncated matrix runs and both instrumented repro runs; absent
in both full runs). A dead player on a headless client never respawns, so declarations
stop forever while the client keeps ticking/snapshotting. The July 23 compare matrix
never hit this because its shorter base-world build saved a daytime world.

Not an LSS bug. Fixed in the harness: `benchmark.sh` now writes `difficulty=peaceful`
into the benchmark server.properties (also removes hostile-mob tick noise from profiles).
The final matrix below re-ran clean under that fix.
