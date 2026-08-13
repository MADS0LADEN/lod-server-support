# Adaptive transfer rate — client-measured pacing + ping backstop — plan

**Status: PLANNED v2, under the 2-Fable review round** (2026-08-13, the
v0.11.0 pause's found-feature loop, round 5 of the slow-link latency program;
supersedes and DELETES the AUTO outbound ceiling of
auto-outbound-ceiling-design.md). **v2 (user direction, mid-review): the
governor is CLIENT-ACTUATED through the existing want-set rate-cap machinery —
the server-enforced declaration is shelved** (tradeoffs recorded in
§Mechanism A; the user's live experiment — manual client cap 50 resolving the
4 Mbps session — validated the actuator directly).

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

## Mechanism A — the client transfer governor (primary; CLIENT-ACTUATED)

**Revised at plan time (user direction + live experiment):** the governor's
actuator is the CLIENT'S OWN want-set sizing, not a server-enforced rate. The
user set the existing manual column-rate cap (`lodColumnsPerSecondLimit`, the
Sodium "Max LOD Download Rate" machinery — budget clamp + size-weighted
fast-fire spacing) to 50 on the live 4 Mbps session and the latency problem
resolved — the actuator is already shipped and field-validated. The AIMD loop
drives that machinery automatically.

Why client actuation beats the server-enforced declaration (the tradeoffs,
recorded):

- ZERO wire changes and zero server-side governor: no sidecar append, no
  declaration lifecycle/trust boundary/repeat-tolerance, no Paper pump
  marshalling. The loop is one client-side class.
- Fixes the REVERSE population: an updated client is governed against EVERY
  released v17+ server (v0.7-v0.10) — the server-enforced design required
  both sides updated, and the slow-link player controls the client side.
- Cheaper for the server: an unasked column is never read, serialized, or
  queued at all (vs paced-after-resolving).
- Tighter loop: the next scan applies the adjustment; no round-trip.
- The cost — burstiness (the tradeoff that favored server enforcement,
  quantified and accepted): the server answers each declared batch at line
  rate then idles, so arrivals burst at ~interval x rate — at the 4 Hz
  adaptive scan cadence ~110 KB per burst ≈ 220 ms of transient queue at a
  500 KB/s link (server pacing would smooth to ~25 KB/tick). Bounded and
  acceptable; server-side pacing remains addable LATER as a pure enhancement
  — and the later shape needs NO declaration either: the server can INFER
  the client's self-imposed pace from the want-set's own size/cadence and
  smooth its sends to match (user direction 2026-08-13: leave it out for
  now unless it proves easy to get right; this plan's design keeps the
  option open by keeping the rate byte-denominated internally).
- Mid-flight degradation still delivers the already-declared outstanding set
  before the cut bites (bounded by one scan's budget; the #71 edge-triggered
  backpressure clear remains the escape hatch for the pathological case).

**The loop** (client-side, beside the scanner/manager; all state per session):

- Each 1 s wall-clock interval, measure received LSS wire bytes (the session
  gate already counts every column frame's shipped size).
- **Engagement gate**: UNENGAGED (no cap applied) until a qualifying
  SHORTFALL interval: bytes were received, the awaiting set was non-empty at
  both interval edges (demand existed all interval — an idle or converged
  interval must never adjust; the DH idle-collapse fix), and the measured
  rate < `ENGAGE_BELOW_BYTES_PER_SEC` (4 MB/s — faster sessions never engage,
  the disarm posture).
- **First engagement**: `desired = measured − STEP/2` (bootstrap-by-shortfall
  — starts at the true measured rate, not DH's 50 KB/s slow ramp).
- **Engaged AIMD** per qualifying interval: kept-up (measured ≥ 0.9 ×
  desired) → `desired += STEP` (STEP = 256 KB/s); shortfall → `desired =
  max(measured − STEP/2, MIN_RATE)` (MIN_RATE = 64 KB/s). Non-qualifying
  intervals change nothing.
- **Actuation**: the byte-denominated `desired` converts to a columns/s bound
  via a per-session EWMA of received column wire size (the client knows every
  frame's size) and feeds the SAME internal path the manual
  `lodColumnsPerSecondLimit` uses (budget clamp + size-weighted fast-fire
  spacing). Composition with the manual knob: the EFFECTIVE cap is
  `min(manual, governed)` — the auto governor may go below the manual knob's
  50-columns/s clamp floor (its own floor is MIN_RATE in bytes); the manual
  knob keeps its meaning as a hard operator/user bound.
- **Disengagement**: no shortfall for 10 consecutive qualifying intervals
  with `desired` above `ENGAGE_BELOW_BYTES_PER_SEC` → drop the cap entirely,
  return to UNENGAGED. Fast links carry zero permanent state.
- Kill switch: client config `enableAdaptiveTransferRate` (default true) —
  off = manual-knob-only, exactly today's shape. One INFO per session on
  first engagement (rate + reason) and one on disengagement — the client-side
  receipt.
- Sessions on legacy dialects (v16 fallback) are EXCLUDED (their pacing is
  the legacy drip-feed's own; the governor gates on a current-dialect
  session, mirroring the adaptive-cadence v16 exclusion).

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
- Composition rule — ONE governor per session where possible, but A is now
  INVISIBLE to the server (client-actuated), so strict suspension is
  impossible. The safe composition: B's cut threshold (750 ms excess) sits
  far above A's converged operating point (~hundreds of ms), so on an
  A-governed session B never reaches its trigger — the loops separate by
  OPERATING REGION instead of population. If both ever act (A mis-converged
  high), they push the same direction with B coarse and slow — bounded,
  non-oscillatory (B cuts at most once per 5 s and recovers slower than A
  adapts). Effective server cap: `min(alloc, cap × pingFactor)`.
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

- Server per-player diag line: `pingf=<factor|1.0>` after `ceil=` (B's
  receipt). A's receipt is CLIENT-side: the engagement/disengagement INFOs +
  the existing `/lss` client rate diagnostics (`getRateGated` already renders
  the manual cap's gating — extended to show the governed rate).
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
  disengagement, the min(manual, governed) composition, the column-size EWMA
  conversion, the v16-session exclusion, kill switch); ping backstop unit
  suite (baseline drift, attribution guard, cut/recover ladder, the
  operating-region separation constants, kill switch); deletion pins (the
  7-arg flush overload's fixed-ceiling semantics unchanged; `ceil=` renders
  fixed/off); diag token goldens.
- T2 + Paper T1: unchanged surfaces re-run.
- Guard soak: fresh-backfill (both governors must be structurally inert).
- **Live gate — the 4 Mbps throttled session**: tab ping settling to
  ~300-600 ms while LODs stream at ~0.35-0.45 MB/s wire (the client INFO
  logs the engaged rate); `yielded=` low; disconnect/rejoin re-engages
  within ~2 s. A second check with the CLIENT kill switch off: behavior
  degrades to yield-only (today's shape) and `pingf=` engages within ~30 s
  if ping balloons — B's live receipt.

## Process

Plan review: 2 Fable subagents (control-loop lens: AIMD dynamics, engagement/
disengagement edges, the A/B composition rule, the declaration clamp's trust
boundary; integration lens: sidecar/handler repeat-tolerance, the deletion
inventory's completeness, config/registry/diag registrations, doc sweep,
CI-inertness). Then implement → 3-Opus implementation review → gates → deploy
to the rig + rebuild the local client jar (the client half is the fix — the
user's Prism instance needs it).
