# Adaptive transfer rate — client-measured pacing + ping backstop — plan

**Status: PLANNED, awaiting the 2-Fable review round** (2026-08-13, the v0.11.0
pause's found-feature loop, round 5 of the slow-link latency program;
supersedes and DELETES the AUTO outbound ceiling of
auto-outbound-ceiling-design.md).

## Why the ceiling program is being deleted (the evidence)

Three consecutive live falsifications on the 4 Mbps throttled rig, one per
build, each exposing a deeper layer:

1. **Round 3**: async netty writes made written-inclusive drain samples read
   phantom multi-MB/s rates (EWMA 12x over; `ceil=1.5 MB`).
2. **Round 4**: pure-drain samples were still poisoned — netty's pending gauge
   measures drain into the KERNEL socket buffer, whose post-burst absorption
   runs at memory speed (`ceil=1.2 MB`, ping 6000 ms).
3. **Round 5 (the median build)**: during movement, vanilla's interleaved
   writes turn most hold-tick deltas negative, starving the sample ring — the
   estimator never trains (`ceil=off`, ping 4000 ms).

And the structural finding that ends the approach rather than the estimator:
**bounding netty-queue DEPTH cannot deliver low latency at all**, because the
kernel send buffer (~0.5-0.7 MB on the test path) and any middle boxes sit
BELOW the gauge and stay full whenever the sender writes at link rate —
~1-2 s of standing ping that no netty-side ceiling can remove. The
experiment that proved the alternative: hand-setting
`mbPerSecondLimitPerPlayer 0.4` (below the ~0.5 MB/s link) drained every
buffer in the chain and restored normal latency instantly ("working
perfectly" — the user, live). Latency comes from pacing UNDER capacity, not
from bounding one queue's depth.

**Prior art confirming the shape** (user-directed research): Distant Horizons'
`ClientCongestionControl` — the CLIENT measures its received bytes per 1 s
interval and AIMD-adjusts a desired rate (kept-up → +50 KB/s; shortfall →
measured − 25 KB/s, floored), re-declaring it to the server ~1 Hz via its
session config; the server enforces it as the player's bandwidth limit.
Client-side received-rate measurement is POST-BOTTLENECK GROUND TRUTH — every
artifact class that falsified rounds 1-4 (async writes, kernel absorption,
vanilla interleaving, gauge clamps) is structurally invisible to it. The AIMD
up-probe is cheap here (+step for one interval ≈ ~100 ms of queue at the
target link, corrected within a second) — unlike the yield-signal AIMD the
ceiling design rejected, whose probes rebuilt multi-second queues.

## Mechanism A — the client transfer governor (primary; updated clients only)

The DH loop with three LSS amendments (their known weaknesses: an ~8-minute
additive ramp from a 50 KB/s start, and idle intervals collapsing the rate).

**Client side** (`common`-free client code beside `LodRequestManager`):

- Each 1 s wall-clock interval, measure `wireBytesReceived` delta (the gate
  already counts every column frame's shipped size; non-column S2C traffic is
  negligible and uncounted — documented).
- **Engagement gate**: the governor stays UNENGAGED (no declaration, server
  unconstrained) until a qualifying SHORTFALL interval: bytes were received,
  the awaiting set was non-empty at both interval edges (demand existed all
  interval — an idle or converged interval must never adjust; the DH
  idle-collapse fix), and measured rate < the current desired rate (when
  engaged) or < the harness-observable engagement bound (first engagement:
  any demand-backed interval whose delivery was slower than
  `ENGAGE_BELOW_BYTES_PER_SEC` = 4 MB/s — a link comfortably above any
  latency-relevant regime; faster sessions never engage at all, the
  disarm-posture equivalent).
- **First engagement**: `desired = measured − STEP/2` (DH's bootstrap-by-
  shortfall, without their slow ramp — we START at the true measured rate).
- **Engaged AIMD** per qualifying interval: kept-up (measured ≥ 0.9 × desired)
  → `desired += STEP` (STEP = 256 KB/s — reaches a 25 MB/s cap in ~90 s,
  vs DH's 8 min); shortfall → `desired = max(measured − STEP/2,
  MIN_DECLARED)` (MIN_DECLARED = 64 KB/s). Non-qualifying intervals (no
  demand / no data) change nothing.
- **Disengagement**: `desired` climbing past `ENGAGE_BELOW_BYTES_PER_SEC`
  with no shortfall for 10 consecutive qualifying intervals → send rate 0
  (= unconstrained) and return to UNENGAGED. Fast links carry zero permanent
  state.
- **Declaration wire**: re-send `ClientInfoC2SPayload` with an APPENDED
  optional field (the codec drains trailing bytes by design — "a future
  client may append fields"; old servers discard silently, no protocol bump,
  no new channel): `[dataVersion:VarInt][declaredRateBytesPerSec:VarLong]`
  where 0 = no constraint. Sent on change only, with 10% hysteresis + a 1 s
  min spacing; also re-sent once after any (re-)handshake (declaration state
  survives the client's ladder heals). The server's client-info handler must
  be repeat-tolerant (verify at implementation: the Via-guard input is
  read-idempotent).
- Kill switch: client config `enableAdaptiveTransferRate` (default true) —
  off = never declare, exactly the legacy-client shape.

**Server side**:

- Per-player `declaredRateBytesPerSec` (volatile; network thread writes,
  flush reads), clamped server-side to
  `[MIN_DECLARED, bytesPerSecondPerPlayer()]` — a hostile/buggy declaration
  can neither stall a session below the floor nor raise its cap.
- The per-tick flush already computes
  `perPlayerCap = min(allocation, config cap)`; the declaration joins as a
  third `min` term when nonzero. The banked-token burst divisor applies to
  the effective cap, so a declared 400 KB/s session banks at most ~100 KB —
  the burst amplitude collapse the 0.4-cap experiment measured.
- Sessions that never declare (v0.7-v0.10 clients, kill-switched clients,
  legacy dialects): unconstrained by A — Mechanism B and the yield gate are
  their protection. No regression versus any released version.
- Server kill switch: `enableAdaptiveTransferRate` (server config, default
  true) — off = ignore declarations entirely.

## Mechanism B — the vanilla-ping backstop (server-side; ALL clients)

Coarse, universal, zero wire changes: the server already tracks each player's
vanilla keepalive latency (Fabric: `ServerPlayer.connection` latency; Paper:
`Player.getPing()`), which is the true end-to-end queue including every
buffer LSS cannot see — the exact number the live sessions diagnosed with.

- Per player, per session: `pingBaselineMs` = rolling minimum of observed
  ping with a slow upward drift (+1 ms/s, so a genuinely changed route
  re-baselines in minutes) — a geographically-distant player's natural ping
  must never read as congestion.
- Sampled each service tick; ADJUSTED at most once per 5 s and only when the
  latency value has changed since the last adjustment (keepalive cadence is
  ~15 s — the loop is deliberately coarse).
- **Cut**: `ping − baseline > PING_BACKSTOP_EXCESS_MS` (default 750 — this is
  the timeout-and-multi-second-lag class, not fine tuning) AND LSS sent
  > 64 KB to that player in the last 5 s (attribution guard: never punish
  LSS-idle sessions for someone else's congestion) → `pingFactor *= 0.5`
  (floor: the factor that yields 64 KB/s effective).
- **Recover**: excess < 250 ms for 3 consecutive adjustments →
  `pingFactor = min(1.0, pingFactor * 1.25)`.
- Composition rule — ONE governor per session, populations disjoint:
  **B is SUSPENDED while a live declaration exists** (A owns updated
  clients; two loops on one plant oscillate — the night's lesson). Effective
  cap: A-sessions `min(alloc, cap, declared)`; B-sessions
  `min(alloc, cap × pingFactor)`.
- Kill switch: `enablePingBackstop` (server config, default true).

## Deletion inventory (the confirmed-dead AUTO ceiling)

Removed outright (same branch, before the new mechanisms land):

- `AbstractPlayerRequestState`: the estimator (median ring, streak counter,
  clock seam, `updateDrainEstimatorAndDeriveCeiling`, all `ceil*` fields and
  constants except as noted), the AUTO in-loop budget + presence gate, the
  AUTO whole-tick hold + `autoCeilingHeldTicks` floor, the
  `autoOutboundCeiling` mode parameter (the 8-arg overload folds back to
  7-arg), the `autoCeilingGauge`.
- Both service call sites lose the mode term; `ChannelAccessorContractTest`'s
  mode pin is deleted with it (the value pin stays).
- `AutoOutboundCeilingTest` deleted wholesale.
- **What SURVIVES**: the operator-FIXED entry-gate ceiling exactly as shipped
  in v0.10 (it predates this program and is not implicated), the 64 KB min
  re-clamp (small fixed ceilings on slow links are a legitimate manual lever;
  the old 4 MB floor's single-payload rationale is re-documented: a payload
  larger than a fixed ceiling simply holds until drained — operator-armed,
  operator's tradeoff), the `/lsslod set outboundBufferCeilingKB` row (a
  live-tunable fixed ceiling; **0 reverts to plain OFF** — the pre-AUTO
  meaning), the `ceil=` diag token (renders the fixed value or `off`), and
  the round-2 floor-reset rescope on the YIELD counter (send-success +
  empty-queue-only resets — independently correct, review-verified, pinned).
- Docs: auto-outbound-ceiling-design.md gets a terminal header (SUPERSEDED →
  this plan) and stays as the falsification record; CLAUDE.md's outbound-
  ceiling bullet rewritten (fixed-only + this plan's governors); the
  release-note items in all four drafts rewritten to the new mechanisms; the
  config-review erratum and flight-cadence back-pointers re-pointed;
  progress-doc pair entry.
- The yield gate, its floor, `deferred=`/`yielded=` attribution: unchanged
  (the backstop-of-last-resort for everything, incl. B-suspended shapes).

## Observability

- Server per-player diag line: `pace=<declared bytes/s|off>` and
  `pingf=<factor|1.0>` after `ceil=`. Always rendered.
- Client: the governor logs one INFO per session on first engagement (rate +
  reason) and one on disengagement — the client-side receipt the estimator
  rounds never had. Diag-level state (`desired`, interval measurements) at
  debug.
- No exporter/schema changes: both governors are latency mechanisms whose
  soak-visible behavior is identical to today on loopback (A never engages —
  loopback delivery outruns `ENGAGE_BELOW_BYTES_PER_SEC`; B never cuts —
  loopback ping ~0). Structural CI-inertness, pinned in T1.

## Test plan

- T1: governor AIMD unit suite (engagement gate incl. demand-backing, the
  no-idle-collapse pin, bootstrap-by-shortfall, kept-up/shortfall steps,
  disengagement, hysteresis + min-spacing on declarations, kill switch);
  sidecar codec append/roundtrip + old-server tolerance pin; server-side
  declaration clamp + min-composition into the flush cap (both platforms'
  call sites); ping backstop unit suite (baseline drift, attribution guard,
  cut/recover ladder, the A-suspends-B rule, kill switch); deletion pins
  (the 7-arg flush overload's fixed-ceiling semantics unchanged; `ceil=`
  renders fixed/off); diag token goldens.
- T2 + Paper T1: unchanged surfaces re-run.
- Guard soak: fresh-backfill (both governors must be structurally inert).
- **Live gate — the 4 Mbps throttled session**: tab ping settling to
  ~300-600 ms while LODs stream at ~0.35-0.45 MB/s wire; diag shows
  `pace=` ~350-450 KB/s on the player line; `yielded=` low (paced sends
  rarely hit the watermark); disconnect/rejoin re-engages within ~2 s. A
  second check with the CLIENT kill switch off: behavior degrades to
  yield-only (today's shape) and `pingf=` engages within ~30 s if ping
  balloons — B's live receipt.

## Process

Plan review: 2 Fable subagents (control-loop lens: AIMD dynamics, engagement/
disengagement edges, the A/B composition rule, the declaration clamp's trust
boundary; integration lens: sidecar/handler repeat-tolerance, the deletion
inventory's completeness, config/registry/diag registrations, doc sweep,
CI-inertness). Then implement → 3-Opus implementation review → gates → deploy
to the rig + rebuild the local client jar (the client half is the fix — the
user's Prism instance needs it).
