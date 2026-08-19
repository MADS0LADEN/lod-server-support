# Flight-cadence unlock + transport backpressure — implementation plan

Status: **PLAN, review round 1 folded** (2026-08-01). Follows directly from
`elytra-chunk-wall-investigation-2026-08-01.md` §8.6.3, which measured the cause.

> **§11 records the 3-agent review round.** Where §2–§10 below disagree with §11, §11 wins —
> it is the as-built decision. The headline changes: D is **cut**, C ships **default-off**,
> A's gate becomes **predictive** (no new field, no hot-loop counter), the threshold moves
> 262144 → 65536 because the original refused the very case it claimed to admit, and the
> netty pending-bytes formula was **wrong** for the netty actually on the classpath.

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

> **2026-08-18 amendment:** the next paragraph's "correct and must stay" is superseded —
> scan prefix retention (scanner-reopened-rings-plan.md) now decrements the prefix and
> reopens the trailing crescent as a ring bitset instead of zeroing, precisely because
> the from-zero re-walk was the measured render-thread hitch. The crescent geometry this
> paragraph identifies is exactly what the retention plan's Euclidean band covers, and
> the CADENCE analysis below (the walk-cost gate) shipped unchanged.

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

---

# 11. Review round 1 (3 agents: correctness / regression-risk / scope) — folded

Three independent reviews. They converged on cutting a third of the plan and found one
shipped bug, one falsified formula, and one arithmetic error that inverted a threshold.

## 11.1 Decisions

| Change | Decision | Why |
|---|---|---|
| **A** | **Keep, re-specified** | Premise confirmed by all three; instrument replaced (§11.2) |
| **B** | **Keep, formula corrected** | The measurement that retires this line of investigation; §3.1's identity was wrong (§11.3) |
| **C** | **Keep, DEFAULT OFF** (`outboundBufferCeilingKB = 0`) — *the 2026-08-13 AUTO detour was deleted the same day (adaptive-transfer-rate-plan.md); 0 = off stands* | Guards a mechanism measured absent; armed-by-default makes the live A/B uninterpretable (§11.4) |
| **D** | **CUT** | Its signal cannot discriminate, and the one that can already ships (§11.5) |

## 11.2 Change A — predict the walk, don't remember it

**Defect (2 of 3 reviewers, independently).** `lastWalkCost` records the walk that *already
ran* and gates the walk that *has not*. Those differ precisely in the case A exists to
unlock: after `recenter()` the next walk restarts at ring 0, while the recorded one started
at the frontier. So the gate admits the first expensive walk after **every** prefix
collapse — it degrades one walk late, every time. Worse, it splits two identically-shaped
events: `hasActionableRetries` (also "next walk starts at ring 0") keeps riding 1 Hz while
movement now runs fast. Same cost, opposite verdict.

**Resolution — no new field, no hot-loop counter.** The scanner already stores both terms.
Predicted cost of the *next* walk, from `confirmedRing` (`SpiralScanner.java:63`) and
`scanRing` (`:64`):

```java
// Σ 8r for r in [confirmedRing, scanRing] = 4·(s(s+1) − c(c+1)); a walk truncated at the
// budget stops at scanRing, so this is exact for truncated walks too.
private int predictedWalkCost() {
    int s = this.scanRing, c = this.confirmedRing;
    long cost = 4L * ((long) s * (s + 1) - (long) c * (c + 1));
    return cost <= 0 ? 0 : (int) Math.min(cost, Integer.MAX_VALUE);
}
```

After `recenter()` zeroes `confirmedRing` this becomes `4·s(s+1)` — the full re-walk it is
about to do. This deletes §2.2's field, the `Integer.MAX_VALUE` init (unreachable anyway —
`lastSentCount <= 0` returns first, and that is only set after a walk), and the §7 pin for
it.

**Threshold: 262144 → `FAST_RESCAN_MAX_WALK_COST = 65_536`.** All three reviewers caught
that a full walk to ring R costs `4R(R+1)`, not `4R²`, so ring 256 is **263,168 > 262,144**
— the old constant *refused* the case §2.3 claimed it admits, and sat 0.4% from the live
server's configured `lodDistanceChunks=256`, which would read bimodally in the A/B. The new
constant is derived from measurement rather than tidiness: the measured flight walk is
`4·75·76 = 22,800`, so 65,536 gives ~2.9× headroom and refuses a warm full-256 disc walk
(263k) by 4×.

That last part is a deliberate policy, now stated: **fast re-scans run only while the walk
is cheap, which is exactly when there is near work to do.** On a disc already satisfied out
to 256 the walk is expensive *and* there is little to fetch — 1 Hz is right there. This is
never a regression, since movement is 1 Hz today unconditionally.

Also folded: §2.3's per-second framing was the wrong unit — an admitted walk costs its
whole price *inside one client tick*, so the budget is a frame budget (~65k × 50–100 ns ≈
3–7 ms), not a per-second one.

**Docs/pins that must move with it** (none were in §10): `SpiralScannerTest`'s
`prefixInvalidationHoldsFastFiresUntilTheNextWalk`, the `fastRescanDue` javadoc that *is*
the design argument, the adaptive-cadence constants pin, `CLAUDE.md`'s want-set paragraph
(states the `confirmedRing > 0` gate as decided design), and
`adaptive-scan-cadence-design.md` §5.5/§11/§12 — where this exact knob is listed as a
deliberate **v1 non-goal**. This plan reverses a 3-Opus-reviewed decision; the amendment
must say so, and say that the reviewers' cost estimate was analytic while the trace
measured 22,800 iterations ≈ 0.9 ms with fps flat at 60.

## 11.3 Change B — the netty identity in §3.1 is wrong

MC 26.2 ships **netty 4.2.15**. Its `ChannelOutboundBuffer` computes
`bytesBeforeUnwritable = high − pending + 1` and `bytesBeforeWritable = pending − low + 1`,
so §3.1's formula is off by one in both branches. Two harder defects:

- **`Channel.bytesBeforeWritable()` returns `Long.MAX_VALUE` when the outbound buffer is
  null** (closed/unregistered), while `isWritable()` returns false in the same state — so
  §3.1's unwritable branch computes `32768 + Long.MAX_VALUE` and **overflows to ≈ −9.2e18**.
  Reachable every tick between socket close and the next lifecycle drain.
- User-defined writability flags can make a channel unwritable with a near-empty buffer,
  yielding a phantom 32 KiB.

**Corrected**, with `-1` = no signal:

```java
if (!ch.isActive()) return -1;
if (ch.isWritable()) { long b = ch.bytesBeforeUnwritable(); return b <= 0 ? high : high - b + 1; }
long b = ch.bytesBeforeWritable();
return (b <= 0 || b == Long.MAX_VALUE) ? -1 : low + b - 1;
```

**And §7's test for it was circular** — a hand-written fake channel implements
`bytesBefore*` to match whatever formula the author believes, so it asserts the plan against
itself. Use a real `io.netty.channel.embedded.EmbeddedChannel` with configured water marks
and unflushed writes, which exercises the real `ChannelOutboundBuffer`.

Diag renders `obuf=n/a` on no-signal rather than a plausible-looking number.

## 11.4 Change C — placement was a shipped bug; default off

> **SUPERSEDED twice (2026-08-13):** `0` briefly meant AUTO
> (auto-outbound-ceiling-design.md), which was live-falsified and DELETED the
> same day — `0` is off again and slow-link pacing moved to the client transfer
> governor + server ping backstop (adaptive-transfer-rate-plan.md). The clamp
> table above (256..65536) is historical; the live clamp is 64..262144, 0 = off.

**The bug (all 3 reviewers).** §4.1's "before any send … return" sits above three things
that live outside the send loop (`AbstractPlayerRequestState.java:547-551`, `:588`):

1. the **only** drain of `readyPayloads` → `sendQueue`,
2. the `sendQueueSizeSnapshot` publish, and
3. `sweepDepartedColumns()`.

The consequence inverts the feature: `sendQueueSizeSnapshot` is the *only* input to the
router's retain-and-stop gate (`IncomingRequestRouter.java:269-276`), so an early return
means `sendQueueFull()` never trips and **the router keeps draining the backlog and
dispatching disk reads for the entire deferral** — severing the very backpressure the gate
exists to create. §4.1's claim that `sendQueueLimitPerPlayer` remains the backstop is false
as written: the limit is checked against `sendQueue`, which the skipped drain never fills.
Skipping the sweep also leaks one `departedColumns` entry per column ever sent.

**Resolution:** the gate goes *after* the drain and snapshot, before the send loop, with
`sweepDepartedColumns()` still running on the deferral path (single exit). Return value is
the existing `NO_DROPPED_POSITIONS`; there is no `NOTHING_SENT`.

**Default off (`outboundBufferCeilingKB = 0`).** Two reviewers independently: C guards a
mechanism the investigation calls "conclusively dead" (flat 20–26 ms ping is a *direct and
sensitive* probe of shared-queue depth — 2 MB pending at ~4 MB/s would show as ~500 ms of
added RTT, and did not), its ceiling is admittedly unmeasured, and shipping it armed
alongside A makes the §7 A/B unanswerable ("was that A or C?"). It ships correct and
tested, and `obuf_hw` from the live run decides whether it is ever turned on.

The old default was also **exactly `MAX_SECTIONS_SIZE`** (2,097,152 B), so a single legal
maximum-size column could trip the gate by itself.

Standing warning, recorded: C is shaped like two mechanisms this repo deliberately retired
(the movement cadence debounce, the vanilla-load scan budget scale) whose shared failure
mode was *silently stopping LOD during fast travel*. `deferred=` in diag is the tripwire; a
nonzero value on a healthy link is a red flag, not the gate working.

## 11.5 Change D — cut

`PlayerChunkSender.unacknowledgedBatches` is marked **"pegged" in all three columns** of the
investigation's own discriminator table (`elytra…md:166`) — it cannot distinguish anything.
The row that discriminates is `desiredChunksPerTick`, which already ships client-side as
`ClientNetTrace`'s `dcpt` (and measured a flat 3.500 through the whole flight). D would add
a third accessor mixin to re-derive a duplicate, from the side of the connection where the
symptom is not observed. It also reds `PaperCommandsTest`'s exact 9-line count for a
Fabric-only feature.

## 11.6 Scope corrections to §7/§10

- **`players[].obuf` is dropped from the soak snapshot schema.** It would force
  `BenchmarkMetricsExporter` + `PaperSoakMetricsExporter` + the shared
  `server-snapshot.contract` golden + both contract tests to move in lockstep, and the soak
  cannot exercise the gate anyway. **Diag-only.**
- **`check_soak.py`'s `SERVER_CONFIG_INT_KEYS` allowlist must gain
  `outboundBufferCeilingKB`** or no scenario can ever set it — a failure that is *silently
  green* in CI because `--selftest` never ties the allowlist to `ServerConfigBase`.
- **`PaperConfigValidationTest` asserts exact config key-set equality** and needs a
  `SHARED_BOUNDS` entry plus a dedicated nonzero-floor test (copy the `lodStoreMaxMB`
  pattern); `ConfigValidationTest`'s floor-allowlist switch needs a `0` arm.
- `DiagnosticsFormatterTest` asserts whole line lists — the new token moves goldens.
- The 11 test rigs subclassing `AbstractPlayerRequestState` require the probe to default to
  `NO_SIGNAL` at construction.

## 11.7 Validation — the ladder was pointed at the wrong things

**The soak harness structurally cannot exercise Change A.** The soak client never moves
continuously; all movement is discrete server-side `tp` commands, and `rate-limit-storm`'s
client is stationary for its entire run. So §7's predicted storm re-baseline is aimed at a
scenario the change barely touches, and A's actual risk surface has **zero** soak coverage.
The real soak exposure is the **client-law window floors**: `service.requests_received` is a
moving term, so any 5 s window containing a declaration is disqualified — a denser cadence
erodes the quiescent-window count directly. Soak's honest role here is "did not break
anything else".

**The live A/B cannot falsify the top risk as designed**, because the control no longer
reproduces the wall (§8.6.1: same 100 MB/s cap, no wall). Both arms would be wall-free, so a
green arm proves nothing about consumed headroom.

**The decisive experiment is zero-code and comes first:** on the *current* build, sweep
`bytesPerSecondLimitPerPlayer` upward until `runway` collapses, and record the throughput at
onset. That is a server config knob — no jar, no deploy, no client change. It converts the
risk to arithmetic: *A must not push sustained flight throughput above X MB/s, where X is
the measured onset minus margin.* If projected `budget × cadence` exceeds X, A needs a
cadence ceiling, which C cannot supply — C guards head-of-line blocking, while the wall was
receiver-cost.

**Falsifiable success criteria** (control numbers from §8.6.3):

- `min(runway)` ≥ 8 chunks **and** ≥ 4.0 s (control: 9–14 / 4.4–6.8 s)
- `p95(ping)` ≤ 35 ms (control: 20–26 ms)
- **scan-gap distribution** shifts below 1.0 s — without this, nothing distinguishes "the
  gate lifted" from "throughput rose for another reason"
- `deferred == 0` in the arm (nonzero ⇒ C fired ⇒ the ungated risk is untested)
- ≥ 3 runs per arm, same route and world state, abort pre-registered

**Rollback is asymmetric and §8 must say so:** `enableAdaptiveScanCadence` is a *client*
config, so once A ships in a released jar no server operator can revert it. The live A/B
must therefore run **before** any release carrying A.

## 11.8 Revised order of work

1. **Cap sweep on the current build** (no code) — bounds the risk arithmetically.
2. Implement A + B + C(default-off), with the §11.6 scope corrections.
3. Tier 1/2/3 + full soak — as a "broke nothing else" gate, not as A's validation.
4. Local flight A/B on `./test-server.sh run-fabric` with the trace.
5. Modrinth A/B against the §11.7 criteria, before any release.

---

# 12. Review round 2 (implementation, 2 agents: correctness / test-adequacy) — folded

## 12.1 MAJOR — the prediction was blind to the walk it most needed to see

`predictedWalkCost()` measured to `scanRing`. But `scan()`'s **only** early exit is the
budget `break outer`; without it the loop iterates every ring out to `lodDistance`, and
`scanRing` records merely the outermost ring that *queued* something. Those coincide only
for a truncated walk.

On a **warm disc — the shipped server's own regime** (`lodStore=full`, backfill complete,
`lodDistanceChunks=256`) — a moving client finds work only in the trailing view-edge
crescents near ring ≈ viewDistance. So `scanRing` stays ~12 while the walk still examines
the whole disc:

| | predicted | actual |
|---|---|---|
| warm 256 disc, crescent at ring 12 | **~96** | **~263,000** |
| same at the 2048 ceiling | ~100 | **16.8 M** |

The gate would have admitted, at up to 4 Hz, exactly the walks its own javadoc says it
refuses — and this is **worse than the old proxy**, which hard-refused every post-`recenter`
tick. Fix: `scan()` records whether the budget truncated it (one field, set once per walk,
no hot-loop counter), and the prediction measures to `getEffectiveLodDistance()` when it did
not. Pinned by `untruncatedWalkPredictsToTheLodDistanceNotTheLastQueuedRing`.

**Also fixed:** the ring sum omitted the starting ring — the loop runs `[confirmedRing,
scanRing]`, so the lower term is `c(c−1)`, not `c(c+1)`. Nil at `c = 0` (which is why no
test saw it), 16,384 short at the 2048 ceiling.

## 12.2 MAJOR — the instrument had no assertion anywhere

Both accessor mixins, both platform adapters and both wiring sites were executed by no
test. The failure mode is silent and terminal (one warning, then `NO_SIGNAL` forever), and
a dead gauge reads exactly like **"no buffer is building"** — a false negative on the one
measurement that decides whether deference is ever armed. §7 had specified a Tier-2 pin;
§11 never retracted it and it was not written.

Landed: `ChannelAccessorContractTest` (source-regex + reflective field-type checks for both
accessors, mixin-config registration, the Paper twin's matching field literals, and both
platforms' probe-install + `*1024` ceiling wiring — `ServiceGlueTest` only exercises the
overload that hard-codes `0L`, so a revert would otherwise ship green), plus
`outboundBufferGaugeResolvesThroughTheRealMixinsAndChannel` in `ServiceLifecycleGameTests`,
which runs the whole chain against a live netty channel (Tier 2 is now 62 tests).

## 12.3 Other fixes folded

- **A throwing probe could take down every later player's flush** — it runs inside the
  per-player loop. Contained at the call site (both adapters already caught internally; this
  is the belt), pinned by `aThrowingProbeCannotTakeTheFlushDown`.
- **`deferred=` counted idle ticks**, so the operator tripwire over-reported. Now counts
  only ticks that actually withheld queued work.
- **Calibration was prose-only.** No test evaluated either number the constant's javadoc
  cites, and at the rings the suite exercised, the correct `4R(R+1)` and the wrong `4R²`
  agree on the verdict — so the arithmetic error that inverted the first draft's threshold
  was invisible. Now pinned: the measured flight walk is exactly `4·75·76 = 22,800` and
  **admitted**; the full 256 disc is `263,168` and **refused**.
- **The deferral path's `sweepDepartedColumns()`** — the third thing §11.4 said the
  mis-placed return would skip, and the only one that had no test.
- **The ceiling boundary** (`>` vs `>=`) and the **`obuf=n/a`** rendering, both previously
  unpinned.
- Stale comments corrected: `SpiralScannerTest`'s "exactly like movement and dirty
  re-opens" (no longer true — the retry term is now deliberately the strictest of the
  three), the long-math justification, and the probe's reconnect claim.

## 12.4 Recorded, not fixed

- `walkCostIsPredictedForTheNextWalkNotRememberedFromTheLast` asserts `> 0` against a zero
  baseline. It does discriminate the remembered-cost alternative — its stated job — but it
  is weaker than the calibration pins that now sit beside it.
- Paper renders no player diag line in any test (every Paper diag test stubs
  `getPlayers() → Map.of()`); the shared formatter is pinned once via the Fabric tier.
- `check_soak.py`'s config allowlist is still not tied to `ServerConfigBase`'s field set, so
  the next new key will be silently green in CI again.

## 12.5 Validation as landed

Tier 1 both platforms, Tier 2 (62 gametests), Tier 3, and all three script selftests
(`check_soak.py` 191 cases, `soak_report.py` 20, `release_check.py` 59) green.

**Still outstanding, and unchanged by this round:** the §11.7 sequencing. Soak cannot
exercise Change A (its client never moves continuously), and the live A/B cannot falsify the
top risk while the control no longer reproduces the wall. The zero-code cap sweep on the
current build remains the first thing to run.
