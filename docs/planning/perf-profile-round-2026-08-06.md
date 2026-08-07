# Server-side CPU profile round — 2026-08-06

Evidence-driven performance pass over the SERVER side of the mod, per the standing goal:
minimize CPU overhead without sacrificing throughput. Generation is out of scope (always
slow and heavy by design); this round profiles the **read/serve paths** and the
**store backfill walk** on both platforms.

All runs on main @ 28ec1ac (post-PR #84), WSL2 box (16 cores), idle (load 0.00, no
leftover test servers — checked). JFR `settings=profile` on every server JVM; band
attribution + per-column CPU via the existing `analyze_profile_jfr.py` machinery.

## ERRATUM (2026-08-06, found by the implementation-plan review round)

**The F1/F2/F3 configs were not what this doc's Runs table implies.**
`profile_disk_read.sh` never exports `BENCHMARK_CONFIG_STAGED=1`, so `benchmark.sh`'s
neutral-staging block (added 2026-08-02) silently replaced the staged config: F1
actually ran the **shipped defaults** — 3 reader threads (auto), not the staged 5;
15 MiB/s per-player cap (and the wire ran at 93% of it, so the serve arm was
bandwidth-bound, not CPU-bound); generation enabled (unreached — the 90 s run never
crossed the pre-generated disc's edge, `sources.generation == 0`). Every `PROFILE_*`
knob of that harness is currently inert. The band splits and hotspot rankings in this
doc are honest profiles of the real serve path under default config and stand; the
absolute per-column numbers are corpus- and config-specific context, and **must not be
used as A/B baselines** — the implementation plan's Phase 0 fixes the harness and
re-baselines. Number corrections from the review's re-derivation: serve-path
LSS-attributed ≈ **262 µs/col** (not 224 — window accounting), memo-machinery samples
under `MemoizedNbtCodec` on the backfill thread = **76 (25% of the thread)** (not ~87 /
29%), backfill IO-Worker whole-recording count **415** (410 was the windowed subset),
and of F1's 366 IO-Worker samples **274 carry an LSS frame** (the rest is vanilla's own
save IO — the plan's Phase 3 gate counts the filtered number).

### Re-baseline under the fixed harness (2026-08-06, same day)

After the one-line `BENCHMARK_CONFIG_STAGED` fix, two verified arms
(`profile-results/rebaseline-20260806*/`, 0 clobber lines, staged config confirmed live):

- **Run A — staged config (5 reader threads, 20 MiB raw cap, gen off), 150 s:** active
  window 79 s, 45.2k disk columns at ~572 col/s (data phase rides the 20 MiB raw cap),
  server CPU 56.2 s, LSS-attributed 18.0% → **~222 µs/col** (upper-bound denominator).
  **IO-Worker 488 samples (390 LSS-frame) vs the entire 5-thread pool 316 (311)** — the
  single vanilla thread still out-works the whole pool. Bands: zip 6.3%, nbt 5.8%,
  serialize 4.5%. Memo machinery ~60 samples on the pool.
- **Run B — same + 100 MiB caps (CPU-bound), 120 s:** the whole 45.2k-column world
  serves in a **28 s window at ~1,615 col/s**, server 1.54 cores, LSS-attributed
  **39.0%** → ~368 µs/col. **IO-Worker 454 (409 LSS-frame) vs pool 327 (318)** — at the
  throughput ceiling the IOWorker is the busiest serving thread by ~7× per-thread.
  Bands: nbt 16.2%, zip 11.0%, serialize 9.2%. This arm makes sections/s a REAL
  regression detector (the capped arm cannot be one).

Both runs confirm the original hotspot ranking and strengthen R1's motivation. Known
arm artifact: at R=256 the spiral crosses the pre-generated world's edge (ring ~128+),
and with gen off the tail is a not-found storm answered `NOT_GENERATED` once per
position — the wire-slope window self-truncates before most of it, and future arms
should size duration to the data phase (~80 s at 20 MiB, ~30 s at 100 MiB).

## Runs

| id | condition | harness | result |
|---|---|---|---|
| F1 | Fabric no-cache cold disk-read serve, R=256, store off | `profile_disk_read.sh run vanilla 1 90 256` | valid arm; 32,608 cols served in 75 s active |
| F2 | Fabric warm-join store off/on A/B, R=96 | `store_gate.sh warm 1 120` | GATE PASS (hit ratio 0.988, addressable cut 97.8%) |
| F3 | Fabric store backfill walk, 1000 col/s cap, server-only | custom (runBenchmarkServer, no client) | 45,589 cols deposited in 55 s (~830/s effective) |
| P1 | Paper warm-rejoin (Moonrise LOW reads + warm resync) | `SOAK_PLATFORM=paper soak.sh warm-rejoin` + jcmd JFR | soak PASS, 0 violations |
| P2 | P1 + `SOAK_LODSTORE_OVERRIDE=full` (deposit path) | same | soak PASS; 3,304 deposits |

Artifacts: `profile-results/perf-round-20260806/`, `store-gate-results/perf-round-20260806/`.

Measurement caveats (documented in `analyze_profile_jfr.py`): NativeMethodSample
under-weights native time ~2×, so absolute µs/col understate zip/zstd work; the huge
`unattributed` share is mostly epoll-parked Netty threads; the Paper soak world is
superflat, so Paper per-column signal is thin — the shared `common/` pipeline carries the
Paper conclusions.

## Headline numbers

**F1 — cold disk-read serve (the main read path), per column served:**
- ~224 µs/col LSS-attributed (band share 14.9% of 49 CPU-s active). ~430 col/s at ~10% of one core.
- Band split of LSS work: zip 40% (region zlib inflate ~105 samples, vanilla net deflate 56,
  wire zstd 45, region-save deflate 11), nbt-parse 29%, transcode/serialize 21%, other 10%.
- **Thread placement is the striking part**: the single-threaded vanilla IOWorker executor
  carries 366 samples (inflate + full NBT parse of every LSS read) vs 207 on the whole
  5-thread LSS reader pool. The IOWorker does ~2× the on-CPU work of the pool that
  nominally owns reading.
- Allocation: 14.6 GB sampled weight in 75 s (~195 MB/s) — dominated by NBT parse garbage
  (String 1.28 GB, HashMap$Node ~1.9 GB combined, HashMap$EntryIterator 328 MB). GC still
  cheap (111 pauses, 292 ms total) but this is the churn budget.

**F2 — warm store path:** store hits are microsecond-class (read avg 16 µs, p95 29 µs,
69.8× vs off-arm disk read). LSS bands ~37 µs/col. The single biggest *serve-related*
CPU item on the warm path is now vanilla's `CompressionEncoder` deflate of the
already-zstd wire frames: ~11 µs/col, i.e. ~30% tax on top of the LSS serve cost.
Store on-arm cut sampled allocation 4× vs off-arm (3.7 GB vs ~14 GB).

**F3 — backfill walk (~994 attributable samples over 55 s, ~400 µs/col direct):**
- vanilla-IOWorker side (inflate + NBT parse of backfill reads): 410 samples — 41%
- backfill thread (transcode + memo lookups): 301 — 30%; of which ~87 (29% of the thread)
  are pure memo-key machinery (CompoundTag hashCode/equals/iterator allocs)
- SQLite batcher: 283 — 29%; of which contentHash (FNV-1a byte loop) 80 ≈ 28% of the
  thread, zstd-1 compress 65, NativeDB.step/commit ~130
- Backfill reads ride the SAME single vanilla IOWorker executor as serve reads.

**P1/P2 — Paper:** the Paper-specific rungs add nothing pathological. Run-2's CPU burst
is Moonrise chunk reload + lighting (vanilla), not LSS; `launchAsyncLoad` submissions are
minor. In P2's deposit burst (2,144 deposits in ~10 s) the store batcher thread does not
even reach the thread top-list — on the superflat soak corpus, Paper deposit cost is
unmeasurably small; F3's Fabric numbers are the authoritative deposit-cost measurement
(same `common/` batcher). One design confirmation: P2 ended with store hits = 0 — on a
server that stays up, the warm timestamp cache answers resyncs before the store rung is
ever consulted; the store pays off across restarts and cold client caches, as designed.

## Ranked recommendations

### R1 (largest structural win): move inflate + NBT parse off the vanilla IOWorker executor
The background-priority rung currently runs `RegionFileStorage.read(pos)` — pread + zlib
inflate + full `NbtIo` parse — inside the IOWorker's single-threaded executor
(`ChunkDiskReader.backgroundRead`, fabric). Measured: that thread does ~2× the CPU of the
entire reader pool on the serve path, and the backfill walk adds its own 41% there.

Feasibility VERIFIED against 26.2 bytecode: `RegionFile.getChunkDataInputStream` is
`synchronized` and returns a `DataInputStream` over `createStream(ByteBuffer, int)` — a
`ByteArrayInputStream` over a **private heap copy** read during the call. So the split is:
on the executor, resolve the region file + call `getChunkDataInputStream` (file IO +
copy only); hand the stream to the LSS reader pool for inflate + parse + transcode.
The executor confinement stays (it is the mutual-exclusion domain for RegionFileStorage's
region-file cache), read-your-writes semantics are unchanged (this path already gave them
up, documented), and the fallback ladder / `ChunkDiskReaderTest` pins are untouched — the
change is inside the background rung only.

Effect: most of the IOWorker-side LSS work moves to the multi-threaded pool
(re-baseline run A: 390 of 488 IO-Worker samples carry an LSS frame vs 311 on the whole
5-thread pool; the F3 backfill window's IO-Worker work was similarly ~all-LSS — ad-hoc
measurement, superseded by the plan's Phase 0 counters). Total CPU ~neutral, but: (a) LSS stops monopolizing the one
thread vanilla's own saves/loads need — directly shrinking the documented A7
timeout-storm mechanism ("one >10 s IOWorker stall event expiring all five blocked
readers"); (b) read throughput headroom rises without adding threads; (c) the backfill
stops taxing the serve path's bottleneck thread.

### R2 (largest CPU cut): selective NBT parse on the LSS disk path
The nbt band is 29% of serve-path LSS CPU and the majority of the 195 MB/s allocation
churn, and most of a chunk's NBT is subtrees LSS never reads (block_entities,
block_ticks, fluid_ticks, structures, PostProcessing, Heightmaps, ...). A selective
loader (vanilla's StreamTagVisitor infrastructure, or a hand-rolled skip-scan over the
NBT token stream) that materializes only `sections` (+ DataVersion/status/coords/light
flags) would cut parse CPU roughly in half and allocation churn by more.
Constraints: the transcoder, x-ray pre-gate, and per-section fallback ladder consume the
full section subtree — keep those whole; skip only top-level keys never consulted.
Ship behind a kill switch mirroring `useNbtTranscode` conventions (flag-off = full parse),
and note the NBT-leniency pins (tech-review round 2, R2-1) when writing tests.
Scope correction (implementation plan, 2026-08-06): this applies only where LSS owns the
parse call — i.e. the Fabric background rung AFTER R1 hands LSS the raw stream. Every
current rung (vanilla `storage.read`/`chunkMap.read`, Moonrise `loadDataAsync`, Paper)
returns an already-parsed tag from platform code, so R2 without R1 has nowhere to act.

### R3 (cheap, contained): cache-friendly memo key for MemoizedNbtCodec
The palette memo keys a raw `CompoundTag`: every hit pays `AbstractMap.hashCode`
(allocates an EntryIterator, re-hashes freshly-parsed Strings) + structural
`AbstractMap.equals`. Measured: ~40% of the transcode subtree on the serve path
(~9% of serve LSS CPU), ~29% of the backfill thread, plus the 328 MB EntryIterator churn.
Fix: wrap keys in a small holder computing a one-pass, allocation-free structural hash
(cache it in the holder; equals falls back to tag equality only on hash match). No wire,
no store, no behavior change; the memo cap/semantics stay as pinned.

### R4 (store-schema bump): replace contentHash FNV-1a with CRC32C
`LodStoreService.contentHash` is a byte-at-a-time FNV-1a loop — inherently serial,
~1 B/cycle. It is 28% of the SQLite batcher thread under deposit load, and frame
deposits ALSO compute chash+fhash on the processing thread (the pipeline choke) for
compressed sessions. `java.util.zip.CRC32C` is a JDK intrinsic (~10+ GB/s, SSE4.2/AVX)
and serves the same purpose here (corruption detection, not crypto). The hashes are
store-internal, so this is a store schema bump — drop-and-rebuild on upgrade, the
established policy for derived data (precedent: registry-fingerprint drift). Combine
with any other store format change to avoid a standalone rebuild.

### R5 (document, don't code): the vanilla double-compression tax
Vanilla's network `CompressionEncoder` deflates every packet above
`network-compression-threshold`, including LSS's already-zstd column frames — measured
~17 µs/col cold-path, ~11 µs/col (a ~30% overhead) on the warm store path, on the Netty
event loop. There is no per-packet opt-out in the vanilla framing, and recommending a
global threshold change would hurt vanilla chunk-packet bandwidth. Record it as a known
structural tax (ops note: proxy-side compression offload changes the calculus on
networks that terminate compression at Velocity/etc.). Revisit only if a future protocol
bump wants to weigh "raw + vanilla deflate" against "zstd + wasted deflate" — measured
today, zstd-1 + tax still wins on total CPU and on client decode.

### R6: backfill — no dedicated work
At max pace the walk costs ~0.33 core and finishes a 64-region world in <1 min; its
biggest component is the shared read path, so R1–R3 are its levers too. The MIN_PRIORITY
worker + MSPT gate already bound its impact.

### R7: the store remains the dominant warm-path CPU eliminator
Re-validated this round: 97.8% addressable-CPU cut, 69.8× hit-latency win, 4× allocation
cut. It stays opt-in by policy (world-folder doubling), but the docs recommendation to
enable it on servers that can afford the disk stands — no serve-path micro-optimization
approaches its effect.

## Non-findings (checked, healthy)
- Probe suppression (2026-08-05 round) is working: serialize-live band is 0.3–0.6%.
- GC: sub-0.5% of wall time in every run.
- SQLite read rung: 16 µs avg / 29 µs p95 — not a cost center.
- Send/flush path (`flushSendQueue`, batching, sweeps): single-digit samples everywhere.
- Paper lifecycle/mailbox machinery: invisible in the profile (healthy).
