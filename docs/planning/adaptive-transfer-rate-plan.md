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
  interval must never adjust; the DH idle-collapse fix), NO backpressure
  halt overlapped the interval (review m1: a #71 halt keeps the awaiting
  set populated while the client deliberately stops ingesting — the
  depressed tail rate would read as shortfall and double-throttle exactly
  the weak-client population; the pre-halt TAPER regime is same-direction
  and intended composition, documented not excluded), and the measured
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
  frame's size; wire-denominated, same denomination as the measurement) and
  feeds the SAME internal path the manual `lodColumnsPerSecondLimit` uses
  (budget clamp + size-weighted fast-fire spacing). The conversion FLOORS AT
  1 column/s (review m2: `columnRateCap`'s contract is `<= 0` = OFF, and
  MIN_RATE ÷ a >64 KB column EWMA would integer-convert to the off sentinel
  at exactly the moment the cap must bind hardest). Composition with the
  manual knob: the EFFECTIVE cap is `min(manual, governed)` with BOTH
  off-sentinels handled explicitly (manual=0 means "manual off", and must
  not win a naive min) — the auto governor may go below the manual knob's
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
- **Lifecycle** (review m3): governor state dies with the session (the
  adaptive-cadence reset-family precedent). The session gate's byte
  counters zero at reset, so an interval spanning a reset / dimension
  change / `/lss reset` reads a negative or garbage delta — such intervals
  are NON-QUALIFYING and re-seed the interval baseline; first engagement
  after a rejoin starts fresh.

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
- Kill switch: `enablePingBackstop` (server config, default true) — ALSO a
  `/lsslod set` row (the registry's first boolean row; the AUTO ceiling's
  precedent made its kill switch a live row, and B's live A/B on the rig
  is this program's working method — a config-edit-plus-restart lever
  would make the live gate needlessly slow).
- Integration precision (review m4): both services compute the per-player
  cap once OUTSIDE the player loop — `cap × pingFactor` is per-player and
  applies inside the per-state path (at the `flushSendQueue` allocation),
  not to the shared cap. Fabric reads `player.connection.latency()` (the
  move-tracer precedent, −1 = no signal); Paper reads the same NMS field
  off its ServerPlayer handle. B's per-player state (baseline, factor,
  5 s sent-bytes window) is pump-thread-confined on the state object —
  fine on Folia too, where a stale-int latency read off the pump is
  benign.

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
- Test/harness stragglers the first inventory missed (review M2 — the
  first three are COMPILE or hard-red breaks, not drift):
  `PaperConfigValidationTest` references `AUTO_CEILING_DISARM_BYTES` and
  pins the whole 0=AUTO semantics block — rewrite to 0=OFF (Paper T1 is
  NOT an unchanged surface; it also gains the `enablePingBackstop`
  default/key rows); `RuntimeSettingsTest`'s "0 returns to AUTO" pin and
  the `RuntimeSettings` row help text ("0 = AUTO … 262144 = off") flip to
  the pre-AUTO meaning (0 = off; 262144 loses its special role — note in
  the set reply that it's now just a large fixed ceiling);
  `DiagnosticsFormatterTest`'s `ceil=` VALUE pin (driven by the deleted
  AUTO gauge), `DiagnosticsFormatter`'s `getAutoCeilingGauge` fallback +
  pre-auto-ceiling compat ctor, and the full-line golden (also gains
  `pingf=`); `ConfigValidationTest`'s AUTO-comment context;
  `ServerConfigBase`'s 0=AUTO javadoc for the key; `check_soak.py`'s
  config-allowlist comment naming AutoOutboundCeilingTest.
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
- No exporter/schema changes. CI-inertness is NOT structural for A
  (review M1): the byte-denominated engage threshold is met by soaks whose
  own configs throttle bandwidth (`bandwidth-throttle` caps global at
  256 KB/s), by superflat scenarios (~1-2 KB columns keep the BYTE rate
  under 4 MB/s at any column rate), and by the generation-paced benchmark —
  and a governed want-set breaks premises calibrated to the constant
  `WANT_SET_BUDGET` (bandwidth-throttle's `queue_full >= 1`,
  disk-saturation's `superseded >= 100`, rate-limit-storm's ceiling).
  Therefore the governor is PROPERTY-GATED OFF under `-Dlss.soak` and
  `-Dlss.benchmark` (the far-player precedent —
  `FarPlayerClientSupport`'s harness gate), and T1 pins the GATE, not a
  structural claim. B stays structurally inert on loopback (ping ~0 never
  crosses 750 ms excess) — that half keeps its structural pin.

## Test plan

- T1: governor AIMD unit suite (engagement gate incl. demand-backing, the
  no-idle-collapse pin, the #71-halt non-qualifying pin, the reset/negative-
  delta non-qualifying pin, bootstrap-by-shortfall, kept-up/shortfall steps,
  disengagement, the min(manual, governed) composition incl. BOTH
  off-sentinel cases, the column-size EWMA conversion incl. the floor-at-1
  pin, the v16-session exclusion, the soak/benchmark property gate, kill
  switch); ping backstop unit suite (baseline drift, attribution guard,
  cut/recover ladder, the operating-region separation constants, the
  per-player factor application point, kill switch + its registry row);
  deletion pins (the 7-arg flush overload's fixed-ceiling semantics
  unchanged; `ceil=` renders fixed/off; set row 0 = OFF); diag token
  goldens (`pingf=` insertion re-goldens the full line).
- T2 re-run. Paper T1 is a CHANGED surface (review M2): the config-test
  AUTO block rewrites to 0=OFF and the new key rows land there too.
- Guard soak: fresh-backfill (both governors must be structurally inert).
- **Live gate — the 4 Mbps throttled session**: tab ping settling to
  ~300-600 ms while LODs stream at ~0.35-0.45 MB/s wire (the client INFO
  logs the engaged rate); `yielded=` low; disconnect/rejoin re-engages
  within ~2 s. A second check with the CLIENT kill switch off: behavior
  degrades to yield-only (today's shape) and `pingf=` engages within ~30 s
  if ping balloons — B's live receipt.

## Process

Plan review: 2 Fable subagents (control-loop lens: AIMD dynamics, engagement/
disengagement edges, the A/B composition rule; integration lens: the deletion
inventory's completeness, config/registry/diag registrations, doc sweep,
CI-inertness). Then implement → 3-Opus implementation review → gates → deploy
to the rig + rebuild the local client jar (the client half is the fix — the
user's Prism instance needs it).

### Review log

**Integration lens (Fable, 2026-08-13) — IMPLEMENT WITH FIXES, all folded:**
M1 the CI-inertness claim was false for A (soak configs themselves create
sub-4 MB/s qualifying intervals; a governed want-set breaks premises
calibrated to the constant budget) → property gate under
`-Dlss.soak`/`-Dlss.benchmark`, pin the gate. M2 deletion inventory missed
the Paper config-test COMPILE break (`AUTO_CEILING_DISARM_BYTES`), the
RuntimeSettings 0=AUTO row-help/pins, the DiagnosticsFormatter AUTO-gauge
fallback + compat ctor + full-line golden, ConfigValidationTest context,
ServerConfigBase javadoc, check_soak.py comment → all inventoried; "Paper
T1 unchanged" claim dropped. m1 #71-halt intervals must be non-qualifying
(halt keeps awaiting populated while ingestion deliberately stops). m2
columns/s conversion can emit the `<=0` OFF sentinel → floor at 1 +
explicit off-sentinel min composition. m3 lifecycle vs the reset family
specified (non-qualifying spanning intervals, state dies with session).
m4 per-player factor placement + Folia thread-confinement note + `pingf=`
golden. Nits: `enablePingBackstop` promoted to a registry row (decision
recorded — first boolean row); Sodium slider under-run is rendered via the
`getRateGated` extension (no new slider row — the adaptive-cadence
precedent); SOAK_DIALECT fidelity moot under the M1 gate.
