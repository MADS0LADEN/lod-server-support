# Vanilla-first LOD transport yield — implementation plan v2.1 (2026-08-06)

Implements mitigation M1/M2 from `docs/planning/moved-wrongly-investigation-2026-08-06.md`
as a **transport yield**: LSS column traffic defers to the connection's own backpressure
signal, so vanilla packets — chunk data above all — are never queued behind a deep LSS
backlog on the shared channel.

**Revision history.** v1 → v2 after a two-Opus round (systems + adversarial; §8 table).
v2 → **v2.1 after a single-Fable full-plan review** (§8.1 table), which found that v2's
budget formula — written to fix v1's fixed threshold — degenerated into an unconditional
~64 KiB/tick pacing cap (the probe reads at most the 64 KiB Netty high-water mark while
the channel is writable, so `budget = max(64 KiB, highWaterMark) − pending` bound every
tick on every link, capping LOD at ~1.25 MiB/s wire below the plan's own bandwidth
defaults). v2.1 resolves it by following that arithmetic to its conclusion: with any
sane decoupled ceiling the budget never binds while writable and is zero while
unwritable — so the gate IS **Netty's writability flag**, with no budget arithmetic and
no first-payload exemption needed at all.

## 0. Purpose — and the exposure this closes anyway

1. **Priority**: vanilla terrain outranks LOD on the wire. When the channel is backed
   up, drain capacity goes to vanilla's queued packets and whatever the chunk system
   writes next — not to more LOD.
2. **Bounded buffering** (the stronger argument): Netty's `ChannelOutboundBuffer` is
   unbounded — the watermarks only flip a boolean, the flush loop never consults
   `isWritable()`, and the limiter keeps admitting up to `bytesPerSecondLimitPerPlayer`.
   Today a client draining at 1 MB/s against a 15 MB/s stream accumulates server-side
   direct-buffer memory without limit until the connection dies. Under the v2.1 gate the
   LSS-attributable Netty queue is bounded by **the high-water mark + one tick's
   admitted burst (~64 KiB + ~560 KiB wire at the shipped caps — see §1.2), with one
   exception: the §1.4 starvation floor may add one payload per 5 s to a channel that
   never drains** (in practice vanilla's ~30 s keep-alive timeout disconnects such a
   client, so the floor adds at most a handful of payloads; stated because this bound is
   the feature's primary justification).

**Honest framing:** this is an *outbound-queue backpressure gate*, not a scheduler.
While the channel is writable, LSS sends at whatever the bandwidth limiter allows —
vanilla gets no rate reservation (that would be investigation M3, the v3 direction).
What the gate removes is the pathology: LSS piling megabytes into an unwritable
channel's queue ahead of vanilla's packets.

**Measurement boundary:** writability is Netty's own signal that the kernel socket
buffer stopped accepting writes and Netty is queuing. The kernel buffer underneath
(OS-autotuned, often hundreds of KB) is invisible to it; the gate bounds the Netty-side
queue only, and this plan makes no end-to-end latency claims. E3/E6 measure the actual
effect on event rates.

## 1. Mechanism (v2.1)

### 1.1 Probe snapshot extension (shared with the tracer — ONE refactor)

`ChannelPressureProbe` grows a **default method** `snapshot()` returning
`{pendingBytes, highWaterMark, writable}`, defaulting to
`{pendingOutboundBytes(), unknown, unknown}` so every existing lambda site (including
`NO_SIGNAL` and every test rig) compiles and degrades to no-yield. Both platform
adapters already read all three fields (they pass them into `OutboundBufferMath`
today). The adapters' access is widened via one public factory — **the same refactor
the move-desync tracer needs for its envelope `obuf` reads; whichever PR lands first
implements the combined shape** (Fable F2-8). A third reader does not violate the "ONE
authoritative probe read per tick" comment in `flushSendQueue` — that invariant makes
the diag gauge and the deference decision agree within one flush; the tracer reads a
different instant for a different purpose.

### 1.2 The gate: yield while the channel is not writable

In `flushSendQueue`, after the existing ceiling branch (§1.3):

```
snapshot = probe.snapshot()          // the same single read that feeds the diag gauge
if (no signal / writable unknown) -> no yield (fail-safe, unchanged)
if (!snapshot.writable)            -> skip this tick's column flush, retain the queue,
                                      count the tick yielded (if queue non-empty),
                                      still sweep departed/probe-suppress, return
else                               -> flush normally (bandwidth limiter governs)
```

Properties, each replacing a v2 defect:

- **No pacing cap on healthy links** (Fable F2-1): while writable, the limiter is the
  only constraint — the §0 claim "LSS uses whatever capacity the limiter allows" is
  true again. A tick's admitted burst is bounded by the limiter's 250 ms bucket
  (~`cap/4` ≈ 3.9 MB raw ≈ ~560 KiB wire at the shipped defaults) — that burst is the
  oscillation amplitude on a saturated link: write burst → channel flips unwritable →
  yield until Netty drains below the **low**-water mark and flips writable (Netty's
  own hysteresis) → next burst. A per-tick byte cap to shrink the amplitude is the v3
  lever if E6 shows the oscillation matters.
- **The single-payload invariant holds by construction**: a payload is only ever
  written to a *writable* channel, so one legal maximum-size column (2 MB raw
  v18-compat worst case) is never blocked — it can only flip the channel unwritable
  and make *followers* wait until it drains. No first-payload exemption, no
  `pending == 0` condition (v2's exemption was mostly dead during movement anyway —
  Fable F2-5). The `MIN_OUTBOUND_BUFFER_CEILING_KB` invariant comment gains one
  sentence pointing here. Consequence stated honestly: after a large raw column, a
  slow link holds followers off until it drains — v18-compat sessions on thin links
  ride closer to the floor rate.
- **CI inertness is provable, not empirical**: loopback/memory channels never go
  unwritable under our write sizes; the Tier 1 pin is simply "writable → no yield"
  plus the existing `OutboundBufferMathTest` watermark pins. Armed-on-loopback soaks
  behave identically to unarmed (no throughput cap to accidentally exercise — the v2
  formula would have throttled them, F2-1).

### 1.3 Ordering: ceiling first, yield second

Unchanged from v2 (S-6): the `outboundBufferCeilingKB` branch keeps its position and
its `deferred=` counter semantics. One consequence stated (Fable F2-7): **the §1.4
floor applies to yield starvation only** — an operator-armed ceiling deferral has no
floor, exactly as today.

### 1.4 The starvation floor (liveness)

Unchanged from v2 (A-4): if a player's flush has sent nothing for `YIELD_FLOOR_TICKS`
(100 ticks = 5 s) and the queue is non-empty, send exactly one payload regardless of
writability. Bounds worst-case LOD at ~1 column/5 s, distinguishes "yielding hard"
from "dead", charges the limiter normally. The floor payload is drawn from the head of
the *pruned* queue (§2.1 runs at the top of the same flush), so the floor never spends
its one payload on terrain the prune was about to discard.

### 1.5 Send-path matrix — the gate verified per supported configuration

The gate never touches the chunk system, so its *mechanism* is identical everywhere;
what differs per configuration is (a) which code writes the vanilla chunk packets the
gate is yielding to, (b) whether the probe resolves, and (c) the traffic pattern the
gate reacts to. All four send paths below were verified against real bytecode
(decompiled vanilla 26.2; Moonrise-Fabric 1.1.0; C2ME 0.4.2-alpha.0.35 for 26.2), not
assumed.

| config | vanilla chunk-send path | probe | gate validity + interplay |
|---|---|---|---|
| **Fabric, plain** | `PlayerChunkSender` batch/ACK flow (client-metered `desiredChunksPerTick` 0.01…64, ≤10 unACKed batches) | `FabricChannelPressure` (accessor mixins → `Connection` → channel) | Baseline. Chunk batches and LOD share the obuf; the ACK flow self-limits vanilla traffic, so the channel is mostly writable and the gate rarely engages. |
| **Fabric + C2ME** | **Still vanilla `PlayerChunkSender`** — C2ME's notickvd only *tunes* it: `MixinChunkDataSender` multiplies the ACK-reported `desiredChunksPerTick`, and the `0` ("unlimited") setting forces the memory-connection flag in the ctor, which removes the batch-size cap — chunk bursts hit the channel unmetered. notickvd's extra no-tick chunks ride the same sender. C2ME does not touch `Connection`/channel classes. | same as plain Fabric — no new resolution risk | Gate works unchanged and is MORE valuable here: uncapped/multiplied chunk bursts legitimately flip the channel unwritable, and the gate holds LOD out (at the floor rate) for exactly that duration. Honest consequence: a large notickvd join burst holds LOD near the floor for its duration — vanilla first is the feature's definition. (LSS's C2ME read-path fallback, `backgroundIncompatible`, is a different subsystem and unaffected.) |
| **Fabric + Moonrise** (the live server) | `RegionizedPlayerChunkLoader` — bypasses vanilla scheduling entirely (own priority queue + `StaggeredRateLimiter`, calls the *static* `PlayerChunkSender.sendChunk` per chunk, no ACK feedback) | same as plain Fabric — Moonrise doesn't touch the channel plumbing either | Gate works unchanged. This is the config where it matters most: with no ACK feedback, a slow/stalling client cannot throttle Moonrise's sends — the backlog lands in the obuf, which is precisely the signal the gate reads (investigation doc §2.2 correction). It is also where the A-4 starvation regime is most likely — hence the floor. |
| **Paper / Purpur** | Paper vendors the same spottedleaf chunk system natively (`RegionizedPlayerChunkLoader` in the server jar) — Moonrise-Fabric row applies | `PaperChannelPressure` (reflection; shipped, wired at `PaperRequestProcessingService` registration) | Gate works unchanged. **Proxy caveat (Paper-typical deployments):** behind Velocity/Bungee the channel is the server→proxy hop, so the gate sees the player's real link only insofar as the proxy propagates backpressure (Velocity does for slow clients, eventually — but a fast LAN hop plus a buffering proxy can blind the gate). On proxied networks the gate is **best-effort: it can under-yield, never over-yield** — stated in the config key's docs. |
| **Folia** (experimental) | as Paper, regionized — region threads write chunk packets; Netty channel writes are thread-safe from any thread | as Paper; the pump-thread probe read is **already load-bearing in production today** at this exact call site (the read is unconditional for every handshaked player — S-12) | Gate works unchanged; zero new call sites, threads, or Netty API surface. Release notes mention Folia's experimental status as always. |
| **Integrated server / LAN** | host player rides an in-memory connection (always writable → gate inert for the host); a LAN guest over real WiFi is an ordinary socket → gate applies normally — NOT covered by the CI-inertness argument; one of the live regimes the default-off period observes | same accessors | No special handling. |

Per-config verification steps (all deferred — nothing runs now): loopback soaks on all
three platforms (provably inert per §1.2 — zero baseline movement expected); the dev
rig's C2ME A/B (`run-fabric` vs `run-fabric-no-c2me`, plus an uncapped
`chunkSendingSpeedMultiplierPercentage=0` session); the live server as the Moonrise
gate (E3, §4); `run-paper`/`run-folia` manual flights; the E6 netem rig for
shaped-link behavior, one flight per config.

## 2. Companion changes

### 2.1 Send-queue relevance prune — main-thread, inside the flush (revised: Fable F2-3)

Unchanged motivation (A-5): yield stretches queue residency from ~1 tick to minutes;
unpruned, the post-yield drain ships kilometres-behind terrain oldest-first, and the
M3 sweep deliberately keeps enqueued positions' done-bits pinned.

**Revised mechanism** — v2 hung the prune off the eviction-cycle hook, which runs on
the **processing thread**, while the send queue is a non-thread-safe main-thread-owned
`PriorityQueue`: a data race as specified. v2.1: the prune runs **inside
`flushSendQueue` on the owning thread**, gated by a tick counter to the same cadence
(~once a minute; the queue is ≤1024 entries, an O(n) `removeIf` at that cadence is
noise). Inputs (player chunk position, ingress radius + margin) are available
main-thread. Per pruned entry: `decrementEnqueued` + clear its `diskReadDone` bit so a
future declaration re-resolves honestly. No proximity re-ordering (same-position edit
convergence depends on submission order); re-ordering stays the v3 option.

### 2.2 Client fast-rescan damper — CUT from this release (Fable F2-2)

v2's damper condition ("the awaited set differs from what the previous fast fire left
behind") is **vacuous**: a fast fire requires ≥95% of the last batch answered, and
answered positions leave the awaited set — so the set *always* differs at any moment a
fire is permitted, and the stuck-tail state the damper targeted can never reach a fire
in the first place (an all-stuck batch is 0% answered). The v2 test pin would have
passed with the damper deleted.

The real exposure is also narrower than the A-6 round assessed: once the yielded data
tail exceeds 5% of a declared batch (≥40 positions at full budget), the ≥95% trigger
fails naturally and the client self-settles to 1 Hz — the 4 Hz window is the warm-sweep
transient (~a minute per warm rejoin), at ~51 KB/s C2S worst case.

Disposition: **cut from this release** (it is non-load-bearing while the gate is
default-off), and record the redesigned shape as the **precondition of the default
flip**: damp on *persistence of a specific unanswered remnant* — if any position has
been awaited across N consecutive fast fires with zero column-data deliveries in that
span, hold 1 Hz until data arrives or the fallback cadence heals it. That distinguishes
stuck-tail (persistent identical remnant) from healthy warm convergence (remnant
churns), which the v2 formulation did not.

### 2.3 Staleness window note (S-15, unchanged)

A queued payload can carry a pre-edit snapshot for the yield's duration; the dirty path
clears done-bits and a fresh re-serve sorts after the stale payload, so state
converges — but the stale-hold window grows from ~1 tick to the yield duration. One
sentence in the config key's doc; the prune caps how stale a *distant* entry can get.

## 3. Interactions audited (unchanged from v2 where not noted)

- **Backpressure chain**: real but delayed (S-13) — the router's send-queue gate trips
  at the 1024-entry cap, not the first yielded tick; the interval is the §2.1 memory
  exposure, bounded by the prune + entry cap at tens of MB per hard-yielding player,
  transiently.
- **No redundant work during yield** (S-13): queued entries hold `enqueuedColumns`, so
  the enqueued rung absorbs re-declarations silently and `skipProbe` suppresses
  re-serialization — a long yield costs memory and latency, never duplicate serves.
- **Duplicate-serve grace / probe suppression**: stamps fire only on send success — a
  yielded tick stamps nothing (S-11).
- **Bandwidth limiter**: governs whenever the channel is writable (§1.2); its burst
  bucket refilling during a yield sets the post-yield oscillation amplitude, stated in
  §1.2.
- **Batch responses / dirty broadcasts / session config**: bypass `flushSendQueue` on
  both platforms (S-14), deliberately — and the resulting cadence interaction is
  §2.2's cut-and-reschedule.

## 4. Config & default

`lodYieldsToVanillaTransport` (boolean, **default FALSE**) in `ServerConfigBase`,
inherited by `PaperConfig`; no clamp (boolean precedent). Default-off is the project's
recorded evidence discipline (config-review §8.2; the investigation's "decide only
after E1/E2 data") — v1's default-true pre-empted the measurement its own parent
demanded. **E3 is the one-key A/B on the live server**; its success metric is **event
rates** (moved-wrongly / rejected counts from the tracer) — with the writability gate,
`yielded=` engages only under real congestion, so it is a valid secondary signal
again, but the event rate is what decides. The default flips in a later release citing
that measurement, with §2.2's redesigned damper as a stated precondition.

`JsonConfig`'s malformed-file fallback is whole-file — a broken config silently
restores all defaults, which with default-off disarms the gate (fail-safe direction).
Support lines: 26.2/main only; backport on demand.

## 5. Observability

- Per-player diag: `yielded=N` beside `obuf=…/… deferred=…`; `PlayerDiag` grows one
  record component — compat constructor per house style, golden lines updated (S-7).
- Service-level cumulative pair (A-7): `yield.ticks_total` +
  `yield.bytes_withheld_total` in the service diag block, plus a one-shot INFO the
  first time any player crosses N consecutive fully-yielded ticks — the log archive
  carries the signal for after-the-fact complaints. (Shipped shape, v0.10.0 A2: a
  conditional `Yield: armed=…, ticks_total=…, bytes_withheld=…` diag line — armed
  shows immediately as the arming receipt; counters live in `TickDiagnostics` so
  they survive per-player teardown (the R2-9 lesson); the one-shot INFO fires at the
  first FLOOR send, i.e. `YIELD_FLOOR_TICKS` consecutive withheld ticks;
  `bytes_withheld_total` is a per-tick byte-tick pressure INTEGRAL of held queue
  bytes, never a delivered-bytes count — v0.10.0-progress.md 2026-08-07.)
- `check_soak.py`'s `SERVER_CONFIG_BOOL_KEYS` gains `lodYieldsToVanillaTransport` in
  the same commit (S-8 — the twice-shipped R4-class allowlist defect).
- Tracer integration is a dependency, not a present surface (S-10): when the tracer
  lands, its `lss` block gains `yielded`, **and tracer analysis must partition rows by
  the boot row's `lodYieldsToVanillaTransport` snapshot** — an armed-gate collection
  period shifts the envelope `obuf` distribution by design (Fable cross-plan note).

## 6. Tests

- **Tier 1, gate** (template: the existing `FlushSendQueueTest` ceiling pins):
  - `writable → no yield` (the CI-inertness pin) and `!writable → queue retained,
    nothing sent, yielded counted only when non-empty, departed sweep still ran, no
    stamping, pre-flush snapshot published` — the verified v2 invariants re-pinned on
    the v2.1 condition;
  - **max-size payload pin, retargeted** (A-1/S-3/F2): a `MAX_SEND_SECTIONS_SIZE`
    payload sends on a writable channel; with the channel unwritable it waits; it is
    never permanently blocked (the floor guarantees eventual progress);
  - starvation floor: after `YIELD_FLOOR_TICKS` of zero sends with a non-empty queue,
    exactly one payload; floor resets; floor draws from the post-prune head;
  - ordering pin: ceiling fires before yield; `deferred=` vs `yielded=` attribution;
    the ceiling path has no floor (F2-7, pinned so the asymmetry is deliberate);
  - `snapshot()` default-method degrade: a bare-lambda probe (legacy shape) yields
    never;
  - no-signal never yields; overload defaults pin (short overloads = yield off, S-9a);
  - **wiring pin** (S-9b): sibling assertion in `ChannelAccessorContractTest` for the
    yield pass-through on both platforms.
- **Tier 1, prune**: outside-radius entries pruned on the flush thread at the cadence
  tick + `decrementEnqueued` + done-bit cleared; inside-radius/in-grace kept;
  edit-order convergence unaffected; no prune work on non-cadence ticks.
- **Tier 1, config/diag**: default-FALSE pin + Paper parity; allowlist entry
  round-trip; golden diag lines; `PlayerDiag` compat constructor.
- **Tier 2 / soak**: no changes; pre-release `all` on both platforms proves loopback
  inertness (later — box busy). An armed loopback soak is now also expected-identical
  (v2's budget would have throttled it — F2-1; worth one armed smoke run to confirm).
- **Live gate**: §4's one-key A/B, event rates as the metric.

## 7. Risks & accepted tradeoffs

1. **Yield-to-floor during sustained flight is expected on the live config**: Moonrise's
   unthrottled sender + elytra-speed churn can hold the channel unwritable for whole
   flights; LOD runs at the floor until the player slows. That IS "vanilla first", but
   it is player-visible, and it is why the honest v3 direction — if live data demands
   it — is a rate *reservation* (M3), not a better clamp.
2. **Burst-sized oscillation on saturated links** (~560 KiB wire amplitude, set by the
   limiter's 250 ms bucket): acceptable for v2.1; a per-tick byte cap is the v3
   smoothing lever.
3. **Kernel-buffer blindness / no latency claims** (§0).
4. **Proxy blindness**: best-effort behind Velocity/Bungee — under-yields, never
   over-yields.
5. **Memory residency during yields**: bounded by prune + entry cap (§3), transient.
6. **v18-compat large raw columns near the floor on thin links** (§1.2) — the
   population is the v0.7.x–v0.8.x install base on slow connections; release notes.
7. **The benefit is derived, not observed** — which is why the default is off and the
   first deployment is the experiment.

## 8. Review round record — round 1 (two Opus reviewers: systems + adversarial)

| finding | disposition |
|---|---|
| A-1/S-3 threshold below one legal payload, contradicting `MIN_OUTBOUND_BUFFER_CEILING_KB`'s invariant | v2.1 §1.2: writability gate — the invariant holds by construction (a payload is only written to a writable channel); Tier 1 max-payload pin retargeted |
| A-2/S-2 probe caps at high-water while writable; kernel sndbuf invisible; v1 latency numbers unsupportable | §0 measurement boundary; all ms claims deleted; v2.1 gates on writability rather than a pending threshold |
| S-1 burst bucket writes ~4×/tick the v1 derivation assumed | §1.2: the burst is the stated oscillation amplitude; no budget pretends otherwise |
| A-3 "priority" framing overclaims | §0 honest framing; M3 named as the reservation direction |
| A-4 no liveness property | §1.4 floor (kept in v2.1; §1.3/§0.2 exceptions stated) |
| A-5 send-queue stale FIFO under long residency | §2.1 prune (v2.1: moved to the owning thread — see F2-3) |
| A-6 status answers bypass the gate → 4 Hz reachable | §2.2 (v2.1: v2's damper found vacuous — cut, redesigned shape recorded as the default-flip precondition) |
| S-4/A-10 default-true contradicts recorded decisions | §4 default FALSE |
| S-5a unbounded Netty buffering is the stronger justification | §0.2 |
| S-5b/A-9 heap residency unstated | §2.1/§3 |
| S-6 yield-first ordering retires `deferred=` | §1.3 ceiling-first + pin |
| S-7 `PlayerDiag` arity | §5 compat ctor |
| S-8 soak config-key allowlist | §5 same-commit |
| S-9 overload defaults + wiring pin | §6 |
| S-10 tracer field described as existing | §5 dependency phrasing |
| S-11..S-17 verified claims | kept; CI-inertness now provable (§1.2) |
| A-7 service-level counters | §5 |
| A-8 proxy topology; LAN ≠ loopback | §1.5 rows |

### 8.1 Review round record — round 2 (one Fable reviewer, full v2 pass, both plans)

| finding | disposition |
|---|---|
| F2-1 BLOCKER: v2 budget = unconditional ~64 KiB/tick pacing cap below the shipped bandwidth defaults; S-16 "inertness" pin proved the wrong thing; E3's `yielded=` signal confounded | v2.1 §1.2: budget deleted — gate on `writable` (reviewer's option (b) taken to its fixed point); CI-inertness pin restated as `writable → no yield`; E3 metric = event rates (§4) |
| F2-2 MAJOR: client damper vacuous (≥95%-answered trigger guarantees the set differs at fire time); exposure self-limits past a 5% tail | §2.2 cut from release; persistence-remnant redesign recorded as default-flip precondition |
| F2-3 MAJOR: prune raced the main-thread-owned `PriorityQueue` from the processing-thread eviction hook | §2.1 prune runs inside `flushSendQueue`, cadence-gated, owning thread |
| F2-5 first-payload exemption's `pending == 0` mostly dead during movement; v18 large columns ride the floor | exemption deleted (unneeded under writability gating); v18 consequence stated §1.2/§7.6 |
| F2-6 floor breaks the §0.2 bound on a dead channel | §0.2 floor exception stated (keep-alive bounds it) |
| F2-7 floor unreachable under an armed ceiling | §1.3 stated + pinned |
| F2-8 probe refactor needs one owner; `snapshot()` must be a default method | §1.1; degrade pin in §6 |
| F2 cross-plan: tracer analysis must partition by the boot-row yield flag | §5 |

## 9. Effort (v2.1)

| piece | estimate |
|---|---|
| Probe snapshot (default method + public factory, shared with tracer) + writability gate + floor + ordering (both platforms) | ~half a day |
| Relevance prune (flush-side) | ~2 hours |
| Tier 1 suite (§6, incl. contract-test additions + allowlist) | ~half a day |
| Docs (config key, CLAUDE.md diag guidance, release notes) | ~1 hour |
| Pre-release soak `all` both platforms (+ one armed loopback smoke) + live one-key A/B | later, box-dependent |

The §2.2 damper is out of this release's scope (default-flip precondition). Ships
independently of the tracer; the two compose.
