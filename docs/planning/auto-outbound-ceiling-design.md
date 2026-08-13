# Auto outbound ceiling — per-player latency-bounded LOD sending — design

**Status: DESIGN, awaiting review round** (2026-08-13, the v0.11.0 pause's
found-feature loop; user-directed from the live 4 Mbps throttled-link session).

## The problem (measured live, 2026-08-13)

The transport-yield gate (`lodYieldsToVanillaTransport`, v0.11.0 default true)
keys on netty's writability, and vanilla sets the channel's high-water mark at
**2 MB** (live diag: `obuf=478.0 KB/2.0 MB`). The gate therefore bounds queue
GROWTH, not queue LATENCY: on a writable channel LSS flushes up to its bandwidth
cap (default 25 MB/s — ~100x a 4 Mbps link) until ~2 MB is queued, and those
bytes are already committed to the ORDERED TCP stream ahead of every subsequent
vanilla packet. At 500 KB/s drain that is ~4-5 s of head-of-line delay, re-primed
the moment the queue dips below the low-water mark. Measured: with LSS enabled
through a 4 Mbps throttled proxy every player action lagged ~5 s while
`yielded=1254` proved the gate WAS engaging; vanilla-only was normal (its chunk
sender paces against client ACKs and never builds a deep queue). The yield gate
prevented the pre-flip 30 s timeout kicks — growth-bounding works — but a slow
link needs latency-bounding.

`outboundBufferCeilingKB` is the right MECHANISM for that (skip the flush while
channel pending exceeds a ceiling; `deferred=` counts holds) but is dead as
shipped: its minimum clamp is 4096 KB, ABOVE vanilla's 2 MB watermark, so the
yield gate always binds first and the knob cannot help. It is also a single
static value, which cannot be right for a server hosting a gigabit client and a
512 kbps client at once — the unit of harm is TIME, and latency = queued bytes /
drain rate, so any one byte value is wrong for someone.

## Decision: measured AUTO ceiling (time-denominated, per player)

    ceiling_bytes = OUTBOUND_TARGET_LATENCY_MS (250) x drain_rate_ewma
    clamped to [64 KB, the channel's reported watermark (fallback 2 MB)]

- Per-player, per-session state. Nothing to tune: the operator constraint.
- A gigabit client's computed ceiling exceeds the watermark → the ceiling never
  binds and the yield gate stays the only governor there (2 MB = 16 ms — fine).
- A 4 Mbps client's ceiling converges to ~125 KB → standing LOD queue bounded at
  ~250 ms while the link still runs at full rate (the ceiling bounds standing
  depth, not throughput: 250 ms = 5 ticks of drain by construction, and the
  queue is refilled to the ceiling every tick).

### Why not the AIMD alternative (scale up while quiet, down on yield)

Considered and REJECTED, with the reasoning recorded because it is the shape the
project reaches for elsewhere (`AdaptiveReadThrottle`):

1. **The signal disappears when the mechanism works.** With the ceiling binding
   below the watermark, yield ~never fires again; "no yield needed" stops
   meaning "headroom exists" and starts meaning "the ceiling is working". The
   controller loses its input exactly on success.
2. **Up-probes hurt the player.** AIMD rediscovers headroom only by pushing the
   ceiling up until yield fires — each probe on a slow link rebuilds the
   multi-second queue it exists to prevent.
3. **Capacity is directly observable here**, unlike TCP's position: the flush
   site already reads the channel's pending-bytes gauge once per tick (the
   `obuf=` source). AIMD is for unobservable capacity; measurement needs no
   probing and never hurts the player to learn.

## The drain-rate estimator

Fed at the existing single per-tick probe read in
`AbstractPlayerRequestState.flushSendQueue` (per-player single-threaded — the
"main thread only" contract already on `departedSweepMarkNanos`):

    drained_sample = pending_prev + lss_wire_written_prev_tick - pending_now

- **Busy-period guard**: sample only when `pending_prev > 0` (an idle channel's
  drain is demand-limited, not link-limited) and `pending_now >= 0` signal-wise
  (probe -1 = no signal → skip).
- **Negative guard**: `drained_sample < 0` means OTHER writers (vanilla bulk,
  far-player lane) grew the queue between reads — skip the sample. Because LSS
  only counts its own written wire bytes, mixed-traffic samples UNDER-count
  drain → the ceiling errs small → more headroom for vanilla. The conservative
  direction, accepted.
- **EWMA over wall-clock** (nanoTime deltas, injected-clock seam for tests —
  the frontier-damper pattern): time constant ~2 s. Fast enough to track a
  degrading link within seconds; slow enough that one stalled tick does not
  collapse the ceiling.
- **Optimistic start**: no valid sample yet → no AUTO ceiling (today's exact
  behavior). A slow link eats one today-shaped burst (bounded by the watermark
  + yield, self-healing) while vanilla's own join burst usually trains the
  estimator BEFORE the first column flush — the pending gauge is channel-wide,
  so vanilla traffic is signal too.
- A single payload can exceed a small ceiling; the effective bound is
  max(ceiling, largest in-flight payload). Honest, no wedge: the ceiling gates
  BEFORE writing, so one oversized payload ships whole and the next flush waits.

## Config semantics (`outboundBufferCeilingKB`)

| value | old meaning | new meaning |
|---|---|---|
| 0 (default) | OFF | **AUTO** (this design) |
| explicit 64..262144 | clamped to 4096..262144, fixed ceiling | fixed ceiling, min re-clamped 4096 → **64** |
| 262144 | fixed 256 MB (inert) | the documented OFF idiom (never binds) |

Back-compat: the measured-absent knob means effectively every install carries 0
→ the fleet flips to AUTO, which is the intent (the yield-default-flip
precedent: the pause is the live observation window). An explicit operator value
keeps exact fixed semantics INCLUDING no starvation floor (F2-7 preserved).

**AUTO gets the yield floor**: ceiling-held ticks in AUTO mode count into the
same consecutive-held-ticks floor as yield (one payload per 100 held ticks) —
an estimator collapsed near zero on a dying link must degrade LOD to the floor
rate, never to silence. (Fixed/operator ceilings keep today's no-floor
contract.)

## What does NOT change

- The yield gate: unchanged, the backstop (UNKNOWN writability, vanilla's own
  bulk breaching the watermark, estimator wrong/stale, proxied servers).
- Counters: `deferred=` counts ceiling holds (existing), `yielded=` counts
  watermark holds — the two governors stay separately attributed. The
  bytes-withheld integral stays yield-only.
- Bandwidth caps (rate guard), issue #71 ingest backpressure (slow decoders):
  orthogonal bottlenecks, unchanged. This design covers slow LINKS.
- Wire: nothing. Store/router/want-set: nothing.

## Known limitations (documented, not fought)

- **Proxy blind spot** (inherited from the yield gate): behind Velocity/Bungee
  the server channel drains at LAN speed → estimator sees a fast link → AUTO
  ceiling sits at the watermark cap → best-effort only.
- **Kernel send buffer**: the gauge (and thus the bound) covers netty pending
  only; the socket's autotuned SO_SNDBUF adds in-flight depth ≈ the path BDP,
  which is genuine pipe content, not standing bloat. Accepted.
- CI-inertness: loopback drains at memory speed → EWMA huge → AUTO ceiling caps
  at the watermark, where yield already holds → soaks and gametests are
  provably unaffected (the yield flip's CI-inertness precedent; pinned in T1 by
  the cap behavior, not re-measured per suite).

## Observability

The per-player diag line gains `ceil=<bytes>|off` after `obuf=` (off =
pre-convergence or the OFF idiom; a fixed ceiling renders its value). Diag-only
gauge — deliberately NOT exported to soak snapshots (loopback makes it
meaningless there; the store.queue-adjacent trap of gauges in quiescence-adjacent
data).

## Test plan

- T1 (`TransportYieldFlushTest` + siblings): estimator truth table through the
  scripted probe + injected clock (busy/negative/no-signal guards, EWMA
  convergence, optimistic start), ceiling derivation clamps (64 KB floor,
  watermark cap), AUTO-holds-count-into-the-floor, fixed-ceiling semantics
  unchanged (incl. no-floor), CI-inertness pin (fast-drain estimator → ceiling
  at watermark cap). Config suites both platforms: 0=AUTO default pin, min
  re-clamp, OFF idiom.
- Diag goldens: `ceil=` token (DiagnosticsFormatterTest + the Paper command
  goldens if the players line renders there).
- Soak: one no-op guard run (fresh-backfill) — loopback inertness is structural.
- **Live gate: the 4 Mbps throttled proxy session** (the user's rig) — the
  acceptance test is near-vanilla action latency with LODs streaming, `deferred=`
  climbing, `yielded=` near-quiet, `ceil=` ~100-150 KB.

## Review round

2 reviewers (subagent budget): (1) control/estimator correctness — sampling
guards, EWMA/clock, flush-site mechanics, floor interplay, threading; (2)
config/back-compat/harness blast radius — semantics table, clamp change, diag
goldens, CI-inertness, docs/release-notes sweep. Attack surfaces named by the
plan: the busy-period sampling validity and the optimistic-start window.
