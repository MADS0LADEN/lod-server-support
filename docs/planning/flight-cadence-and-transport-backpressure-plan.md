# Flight-cadence unlock + transport backpressure — implementation plan

Status: **PLAN** (2026-08-01). Follows directly from
`elytra-chunk-wall-investigation-2026-08-01.md` §8.6.3, which measured the cause.

## 0. Evidence this plan is built on

From the 26 s flight trace (`lss-trace-20260801-190539.jsonl`) and the live server:

- Cadence is pinned at **exactly 1.0 s** during flight and runs **2–3 Hz** while
  stationary. `confirmedRing` climbs 60→64 standing still, collapses to 9 at the first
  movement, and never recovers.
- Throughput is therefore `budget × cadence` = 800 × 1 Hz ≈ 920 columns/s =
  **25–27 MB/s raw / ~4 MB/s wire**, against a 100 MB/s cap. The cap is irrelevant.
- **Nothing is saturated**: decode queue ~0 of 8000, ingest ~0 (peak 3379 of 6144 halt),
  `inflight` 0 in 18 of 26 samples, fps 60 flat, `dcpt` constant 3.500, server
  `sq=0/2000`, `saturated=0`.
- **Transport is healthy**: `ping` 20–26 ms across the whole flight.

## 1. Goals and non-goals

**Goals**

- **A** — let the adaptive fast cadence work while the player is moving, without
  reintroducing the render-thread walk hitch the current gate protects against.
- **B** — measure the server's per-player netty outbound queue, the H1 measurement the
  investigation could never take, and surface it in `/lsslod diag`.
- **C** — stop LSS feeding a backed-up connection: defer column sends while the player's
  outbound buffer is deep, so LSS payloads cannot head-of-line-block vanilla's chunk
  packets.
- **D** *(cuttable — see §9)* — vanilla's own per-player chunk-sender state in diag, so the
  gauge in B has a symptom to correlate against.

**Non-goals**

- Any protocol/wire change. All four changes are local.
- Per-player AIMD or a full flow-control law (`elytra…md` §7). C is the deference gate
  only; the adaptive ceiling stays future work.
- Raising `WANT_SET_BUDGET`. It has only +28% headroom to the wire cap and is not the
  binding term; cadence is.

## 2. Change A — replace the prefix gate with a walk-cost gate (client)

### 2.1 What is actually wrong

`SpiralScanner.recenter()` sets `confirmedRing = 0` on every chunk-boundary crossing. This
is **correct and must stay**: the confirmed prefix was derived for the old center, and the
trailing view-edge crescents (positions leaving vanilla's view circle) become LOD-needing
at ring ≈ viewDistance, so the prefix genuinely cannot survive movement.

The bug is that `fastRescanDue()` uses `confirmedRing > 0` as a **proxy for walk cost**,
and nothing re-derives the prefix until the next *walk*. At 33 blocks/s crossings run
2.76 Hz against 1 Hz scans, so the first crossing after each scan zeroes the proxy and the
fast path is dead until the next scan. The proxy is measuring movement, not cost.

### 2.2 The fix

Measure the thing the gate is actually about. `scan()` already iterates every candidate
position; count them.

```java
// SpiralScanner
/** Positions the last walk EXAMINED (ring iterations), not positions declared. The
 *  fast-cadence walk-cost gate reads this. Initialised to MAX_VALUE so a scanner that
 *  has never walked fails the gate closed. */
private int lastWalkCost = Integer.MAX_VALUE;
```

Incremented once per inner-loop iteration in `scan()` (before the exclusion/classify
branches, so skipped positions still count — they are the cost), stored alongside
`lastBudget`/`lastQueued`.

In `fastRescanDue()`, replace:

```java
if (this.confirmedRing <= 0) return false;
```

with:

```java
if (this.lastWalkCost > FAST_RESCAN_MAX_WALK_COST) return false;
```

`hasActionableRetries` keeps its own term (it resets the prefix *inside* `scan()`, after
the predicate has already run — see the existing comment; that asymmetry is unchanged).

### 2.3 Choosing the threshold

Ring *r* holds 8*r* positions, so a walk from ring 0 to ring *R* costs ≈ 4R² iterations.

| Frontier | Walk cost | At 4 Hz |
|---|---|---|
| ring 75 (the measured flight) | ~22,800 | 91 k/s |
| ring 256 (default max LOD distance) | ~263,000 | 1.05 M/s |
| ring 2048 (`MAX_LOD_DISTANCE`) | ~16.8 M | 67 M/s ← the documented hitch |

`FAST_RESCAN_MAX_WALK_COST = 262_144` (2^18) admits a full walk at the default 256
distance and refuses the 2048 ceiling by two orders of magnitude. A classify is a fastutil
lookup plus branches (~50–100 ns), so the admitted worst case is ~50–100 ms/s of render
thread — the same order as today's 1 Hz walk at that distance, because the gate degrades
to the 1 Hz fallback exactly where the walk stops being cheap.

### 2.4 Why this is safe

- No correctness-bearing state changes. `confirmedRing`, `recenter()`, the walk, and the
  declared want-set are untouched; only the *cadence decision* changes.
- The documented intent ("only cheap frontier walks run fast") is preserved and now
  actually measured rather than proxied.
- The remaining fast-fire terms are unchanged: ≥95% answered, ¼-halt pressure on all three
  pipes, no actionable retries, not v16, 250 ms floor.
- `enableAdaptiveScanCadence=false` remains a complete rollback to fixed 1 Hz.

### 2.5 Expected effect — and why C must land with it

Cadence should rise from 1 Hz toward the 2–4 Hz seen while stationary, so flight
throughput goes from ~26 MB/s raw toward 50–100 MB/s. **That is the wall's band**
(the original incident ran at 21–25 MB/s), which is precisely why B and C are in the same
change set rather than a follow-up.

## 3. Change B — per-player outbound-buffer gauge (both platforms)

### 3.1 Measuring absolute pending bytes without netty internals

Vanilla sets **no** `WriteBufferWaterMark` (verified: no channel options in `Connection`,
`ServerConnectionListener`, or `EventLoopGroupHolder`), so netty's defaults apply —
low 32 KiB, high 64 KiB. Two consequences:

- **A raw `isWritable()` gate is unusable.** LSS flushes ~200 KB/tick at current rates, so
  the buffer routinely exceeds a 64 KiB high mark on a perfectly healthy link. Gating on
  `isWritable()` would oscillate and roughly halve throughput for no reason.
- Absolute pending bytes are still recoverable from public `Channel` API, without
  `unsafe()`:

```java
// writable:     pending = high - bytesBeforeUnwritable()
// not writable: pending = low  + bytesBeforeWritable()
long pendingOutboundBytes(Channel ch) {
    var cfg = ch.config();
    return ch.isWritable()
            ? cfg.getWriteBufferHighWaterMark() - ch.bytesBeforeUnwritable()
            : cfg.getWriteBufferLowWaterMark() + ch.bytesBeforeWritable();
}
```

Both methods are `Channel` interface members; the identity holds by netty's own
`ChannelOutboundBuffer` definitions.

### 3.2 The seam

New in `common`:

```java
/** Per-player transport pressure. -1 = no signal (probe unavailable) — every consumer
 *  must treat that as "do not throttle", so an unreachable channel degrades to today's
 *  behaviour rather than stalling the player. */
public interface ChannelPressureProbe {
    long pendingOutboundBytes();
    ChannelPressureProbe NO_SIGNAL = () -> -1L;
}
```

Held by `AbstractPlayerRequestState`, set at registration, defaulting to `NO_SIGNAL`.

- **Fabric**: two accessor mixins — `ServerCommonPacketListenerImpl.connection`
  (`protected final`) and `Connection.channel` (`private`). Resolution failure → `NO_SIGNAL`
  with a once-warn, matching the `backgroundIncompatible` precedent.
- **Paper**: `ServerGamePacketListenerImpl` is reachable via NMS, but `connection` is
  protected from `dev.vox.lss.paper`, so one cached reflective `Field` lookup, resolved
  once per JVM behind a lazy holder; any failure → `NO_SIGNAL` + once-warn. Same shape as
  `MoonriseReadCompat`'s resolution ladder.

### 3.3 Sampling and surfacing

Sampled once per player per tick in the service tick (where `flushSendQueues` already
runs), feeding a per-player current value and a session high-water, mirroring the existing
`*_hw` gauges.

`/lsslod diag` per-player line gains: `obuf=<pending>/<hw>`, plus a `deferred=<n>` counter
from Change C. Soak snapshot schema gains `players[].obuf` and `players[].obuf_hw`
(additive — `check_soak.py` tolerates unknown keys; no law reads them, so no re-record of
existing corpora is needed).

## 4. Change C — writability deference gate on the send path

### 4.1 Behaviour

In `AbstractPlayerRequestState.flushSendQueue`, before any send:

```java
long pending = this.channelPressure.pendingOutboundBytes();
if (ceilingBytes > 0 && pending > ceilingBytes) {
    diagnostics.recordSendDeferred();
    return NOTHING_SENT;   // queue RETAINED, not dropped
}
```

- **Retain, never drop.** The send queue keeps its entries and drains next tick. This
  matches the router's existing "a full slot cap retains the entry" convention, and avoids
  turning a transient buffer spike into `queue_full` loss.
- The queue's own `sendQueueLimitPerPlayer` overflow remains the backstop; sustained
  unwritability will eventually hit it, and those drops are counted as they are today and
  healed by re-declaration.
- Dirty broadcasts and session config are **not** gated — they are tiny and latency-
  critical. Only the bulk column path defers.

### 4.2 Ceiling

`outboundBufferCeilingKB`, default **2048** (2 MB), clamp 256..65536, `0` disables.

Rationale: a healthy flush is ~200 KB, so 2 MB is ~10 ticks of slack — deep enough never
to trip on a working link, shallow enough that a vanilla chunk packet queued behind it
waits well under a second at any plausible drain rate. It is a config so the live server
can find the real knee empirically.

### 4.3 Why this is the right gate even though H1 was not observed

The investigation measured `sq=0/2000` and flat ping, so LSS is not currently queueing.
This gate is a *guard*, and it is the precondition for Change A: A deliberately pushes the
system toward the throughput band where a queue can form. Shipping A without C removes the
only thing that would keep LSS from starving vanilla's chunk delivery if it does.

## 5. Change D — vanilla chunk-sender telemetry (Fabric, cuttable)

`ServerGamePacketListenerImpl.chunkSender` is **public** (verified), so only
`PlayerChunkSender`'s own fields need an accessor mixin: `desiredChunksPerTick`,
`unacknowledgedBatches`, `maxUnacknowledgedBatches`, `pendingChunks.size()`.

Surfaced in diag as `vanilla=<dcpt>/<unack>:<max>/<pending>`. Without it the B gauge has no
symptom to correlate against — "the buffer is deep" and "vanilla chunk delivery is
suffering" are different claims, and `unacknowledgedBatches` pegged at max is the direct
evidence for the second.

## 6. Config surface

| Key | Default | Clamp | Platform |
|---|---|---|---|
| `outboundBufferCeilingKB` | 2048 | 256..65536, 0=off | server, both |

No new client config: `enableAdaptiveScanCadence` (existing) is already the complete
rollback lever for Change A.

`ServerConfigBase` holds the field, defaults and clamp verbatim for both platforms, per the
existing convention.

## 7. Test plan

### Tier 1 (new pins)

**Change A** — `SpiralScannerTest` / the adaptive-cadence suite:
- a walk whose recorded cost is under the threshold **arms** the fast path even with
  `confirmedRing` freshly zeroed by `recenter()` — *the regression this plan exists to fix*
- a walk over the threshold does not
- `lastWalkCost` counts examined positions (exclusion-skipped and satisfied-skipped
  included), not declared ones
- a never-walked scanner fails closed (`Integer.MAX_VALUE` init)
- the existing disarm family (0-count walk, send failure, disconnect, reset family, v16,
  ¼-pressure, actionable retries) all still disarm — re-run unchanged

**Change B** — `ChannelPressureProbeTest`: the writable/unwritable pending-bytes identity
against a fake channel with known water marks; a throwing/unavailable probe yields
`NO_SIGNAL`.

**Change C** — `AbstractPlayerRequestState` twins: over-ceiling defers and **retains** the
queue (same entries, same order, nothing dropped); `-1` probe is inert (bit-identical to
today); `ceiling 0` disables; the deferral counter increments once per deferred tick;
dirty/session sends are not gated.

**Config** — clamp + malformed-file tolerance on both platforms; `DiagnosticsFormatter`
golden line updated.

### Tier 2

`ServiceLifecycleGameTests`: service still serves normally with a probe reporting 0, and
defers with a probe pinned above the ceiling (no crash, no drops, queue drains after).

### Tier 3

Unchanged — but it exercises the client cadence, so a green run is evidence A did not
break the request loop.

### Soak — **expect a re-baseline**

Change A raises the declaration rate, and `service.superseded` scales with it. CLAUDE.md
records that `rate-limit-storm`'s ceiling was already re-baselined 370 → 1500 for the
adaptive cadence; lifting the movement gate will push it again. **Plan for it: run the
suite, and if the storm ceiling trips, re-baseline it with the measured number and record
the reason** — do not treat it as a regression without checking the premise first.

Full Fabric suite + the four Paper scenarios. `store_offline_edit.sh` for the store path.

### Live A/B (the real gate)

On the Modrinth server, same route, trace on, at the same 100 MB/s cap:

1. **Control**: `enableAdaptiveScanCadence=false` (fixed 1 Hz).
2. **Arm**: default (gate lifted).

Compare from the `net` events: scan gaps, `raw_bps`/`wire_bps`, `ping`, `runway`,
`q`/`qb`/`ingest`, and server-side `obuf`/`obuf_hw`/`deferred`. **Success is not "faster"
— it is faster with `runway` never collapsing and `ping` flat.** If `runway` degrades, C's
ceiling is the first knob, then the cap.

## 8. Rollout

1. Land A+B+C(+D) behind their defaults on `feat/flight-cadence`.
2. Tier 1/2/3 + full soak locally.
3. Deploy to the Modrinth server; run the live A/B above.
4. Only then consider merging; the release note must state that LOD fill during movement
   gets substantially faster and that `outboundBufferCeilingKB` exists.

**Rollback:** `enableAdaptiveScanCadence=false` (client) reverts A; `outboundBufferCeilingKB=0`
reverts C; B and D are diagnostics with no behavioural effect.

## 9. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Throughput jump re-creates the elytra wall | **high** | This is the known band. The live A/B is the gate, not the local tests. C plus the existing #71 ingest taper and decode-queue halt are the standing guards; `runway` in the trace is the direct observable. |
| Render-thread walk cost at large LOD distance | medium | That is exactly what `FAST_RESCAN_MAX_WALK_COST` bounds; the gate degrades to 1 Hz where walks stop being cheap. |
| Pending-bytes identity wrong for some netty version | medium | Unit-pinned against a fake channel; any anomaly degrades to `NO_SIGNAL` (never throttle). |
| Paper reflection breaks on a future NMS shape | low | Cached lazy resolution, failure → `NO_SIGNAL` + once-warn. Fabric keeps the mixin path. |
| Soak storm ceiling trips | low | Anticipated in §7; re-baseline with the measured number. |
| Change D scope creep | low | Cuttable; it is the only item with no behavioural consumer. |

## 10. Files

**common**
- `processing/ChannelPressureProbe.java` *(new)*
- `processing/AbstractPlayerRequestState.java` — probe field, deferral gate in
  `flushSendQueue`, gauge accessors
- `config/ServerConfigBase.java` — `outboundBufferCeilingKB` + clamp
- `LSSConstants.java` — ceiling min/max/default
- `DiagnosticsFormatter.java` — `obuf=`, `deferred=`, `vanilla=` tokens

**fabric**
- `mixin/AccessorServerCommonPacketListener.java` *(new)* — `connection`
- `mixin/AccessorConnection.java` *(new)* — `channel`
- `mixin/AccessorPlayerChunkSender.java` *(new, D)*
- `networking/client/SpiralScanner.java` — `lastWalkCost`, threshold constant, gate swap
- `networking/server/RequestProcessingService.java` — probe wiring + per-tick sampling
- `config/LSSServerConfig.java` — inherited field surfaces automatically

**paper**
- `PaperChannelPressure.java` *(new)* — cached reflective resolution
- `PaperRequestProcessingService.java` — probe wiring + sampling
- `PaperConfig.java` — inherited

**scripts**
- `check_soak.py` — optional `players[].obuf` / `obuf_hw` passthrough; storm ceiling
  re-baseline if it trips
