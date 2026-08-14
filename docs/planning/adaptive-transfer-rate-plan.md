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

- **Measurement**: 2 s wall-clock intervals (review m6 — 1 s intervals alias
  against the batch cadence, capturing 0 or 2 bursts; at 4 Hz actuation a
  2 s window bounds the burst-count error to ~±12.5%, comparable to the
  kept-up band), measuring received LSS wire bytes (the session gate's wire
  counter — post-bottleneck arrival truth, network-thread accounting).
- **The congestion signal** (review M1): the client's own tab-list ping
  (PlayerInfo latency — the vanilla keepalive path, so it excludes LSS
  server processing time) against a session-rolling minimum baseline with a
  +1 ms/s upward drift — the client-side mirror of Mechanism B's signal.
  `pingExcess = ping − baseline`. **Pre-implementation check, RESOLVED
  (26.2 bytecode, 2026-08-13)**: the keepalive smoothing is
  `latency = (3·latency + sample)/4` on a 15 s send cadence
  (`ServerCommonPacketListenerImpl.handleKeepAlive`), and the tab-list
  UPDATE_LATENCY broadcast is every **600 ticks = 30 s**
  (`PlayerList.sendAllPlayerInfoIn`). Staleness only DELAYS engagement
  (the safe direction; the severe multi-second-excess class still crosses
  the 250 ms conjunct on its first refresh, ~15-45 s), but 30 s exceeds
  the ~10 s trust window for a ping-DRIVEN drain bias — so per the
  recorded decision rule the drain bias is the DETERMINISTIC variant (see
  the AIMD bullet).
- **Engagement gate**: UNENGAGED (no cap applied) until a qualifying
  CONGESTED-SHORTFALL interval — ALL of: (a) bytes were received; (b) the
  awaiting set was non-empty at both interval edges (demand-backed — an
  idle or converged interval must never adjust; the DH idle-collapse fix);
  (c) NO #71 backpressure halt overlapped the interval (integration m1: a
  halt keeps the awaiting set populated while the client deliberately
  stops ingesting — the depressed tail rate would read as shortfall and
  double-throttle exactly the weak-client population; the pre-halt TAPER
  regime is same-direction and intended composition, documented not
  excluded); (d) no reset/dimension-change spanned the interval (m3); (e)
  **`pingExcess > ENGAGE_PING_EXCESS_MS` (250) — the congestion conjunct**
  (review M1: a measured-rate shortfall alone CANNOT engage; measured rate
  equals the demand or serve rate whenever the link is not the bottleneck,
  so without this conjunct every walking player on a healthy link — LOD
  trickle ~100-300 KB/s with a standing awaiting set — and every gen-bound
  cold join would engage fleet-wide); (f) measured rate <
  `ENGAGE_BELOW_BYTES_PER_SEC` (4 MB/s — faster sessions never engage).
  Loopback ping ~0 never crosses (e), so harness inertness is structural
  — and the governor is ADDITIONALLY property-gated off under `-Dlss.soak`
  / `-Dlss.benchmark` (integration M1, the far-player harness-gate
  precedent) as determinism armor, with the GATE pinned in T1.
- **First engagement**: `desired = measured − STEP/2` (bootstrap-by-
  shortfall — starts at the true measured rate, not DH's 50 KB/s ramp).
- **Engaged AIMD** per qualifying interval (qualifying = (a)-(d); (e)/(f)
  are engagement-only): SHORTFALL when `measured < desired − STEP/4` — an
  ABSOLUTE band (review M3: the multiplicative 0.9 band admits equilibrium
  ~1.11× capacity, which ratchets standing queue; the absolute band nets
  zero queue per oscillation cycle) → `desired = max(measured − STEP/2,
  MIN_RATE)`. Otherwise KEPT-UP → `desired += STEP`. **The drain bias**
  (review M3's second defect: a rate-matched loop never drains queue it
  INHERITED — the pre-engagement burst can bank ~cap/4): every 8th
  consecutive kept-up interval is instead a deliberate DRAIN interval —
  `desired = measured − STEP/4`, no +STEP — bleeding standing queue at
  ~STEP/4 per 8 intervals. The DETERMINISTIC variant was chosen over the
  ping-driven one because the resolved 30 s tab-latency staleness would
  over-cut for up to ~45 s past actual drain (staleness + smoothing
  decay); the deterministic bleed is slower (~6-7 min for a full
  inherited bank) but monotone and testable, and B backstops the severe
  class meanwhile. STEP = 256 KB/s; MIN_RATE = 64 KB/s.
- **Actuation** (review M2 — the burst-quantum seam split): governed R
  (columns/s) derives from `desired` via the size estimator below, then
  the scanner's governed path supplies TWO values: `max(1, ceil(R/4))` at
  the BUDGET-CLAMP site and `R` at the SPACING-GATE site, so the spacing
  gate equilibrates at the 5-tick floor — 4 Hz quarter-batches, burst ≈
  desired/4 ≈ 250 ms of link time at converged utilization. (The naive
  single-supplier shape mathematically cannot express this: the spacing
  gate is `ticks × cap < 20 × lastSentCount` and the walk fills the
  clamped budget, so one value R yields 1 Hz FULL-second batches — a
  burst ~4× the plan's target that grazes B's 750 ms threshold and
  consumes the A/B separation margin. The MANUAL knob keeps its shipped
  single-value shape and 1 Hz-full-batch behavior — its semantics are
  released.) Composition with the manual knob: EFFECTIVE cap =
  `min(manual, governed)` with BOTH off-sentinels handled explicitly
  (`columnRateCap`'s contract is `<= 0` = off; manual=0 must not win a
  naive min — integration m2), applied at both sites; the conversion
  FLOORS at 1 column/s (m2/m5: a 0 governed budget would return -1 from
  the walk — no declaration ever, killing the want-set's only self-heal;
  the governor deliberately bypasses the manual knob's 50 clamp floor so
  it must carry its own). The 1 Hz fallback stays un-gateable (shipped
  invariant).
- **Size estimator** (review M4): per-session ASYMMETRIC EWMA of received
  column wire size — fast-up, slow-down — so a bimodal ocean→terrain
  boundary (0.1-1 KB ghost-clears vs 3-30 KB terrain) under-counts R
  transiently rather than over-bursting several× desired while a dragged-
  down mean catches up; wire-denominated (same denomination as measurement
  and desired — the pivot made the loop unit-consistent end to end, which
  the v1 server-enforced shape was NOT: the server charges RAW bytes);
  runs from session start (pre-engagement), division guarded until the
  first sample.
- **Disengagement**: (a) 10 consecutive qualifying intervals with
  `desired` above `ENGAGE_BELOW_BYTES_PER_SEC`; OR (b) `pingExcess <
  100 ms` for 30 consecutive intervals (~1 min of healthy link — review
  m10: without a ping-normal exit a converged session freezes its last
  `desired` forever, and a later teleport into heavy demand on a now-
  healthy link resumes under the stale low cap). Either path drops the cap
  entirely; fast links carry zero permanent state; re-engagement on a
  still-slow link costs 1-2 intervals. Fully-stalled intervals (zero
  bytes, demand-backed) are NON-QUALIFYING → no cut while totally stalled
  (m11 — deliberate: B owns the total-stall class via server-side ping;
  the stale `desired`'s recovery burst is bounded by the quarter-batch
  quantum).
- Kill switch: client config `enableAdaptiveTransferRate` (default true) —
  off = manual-knob-only, exactly today's shape. One INFO per session on
  first engagement (rate + reason) and one on disengagement — the
  client-side receipt.
- Sessions on legacy dialects (v16 fallback) are EXCLUDED (their pacing is
  the legacy drip-feed's own; the governor gates on a current-dialect
  session, mirroring the adaptive-cadence v16 exclusion).
- **Lifecycle** (review m3): governor state dies with the session (the
  adaptive-cadence reset-family precedent). The session gate's byte
  counters zero at reset, so an interval spanning a reset / dimension
  change / `/lss reset` reads a negative or garbage delta — such intervals
  are NON-QUALIFYING and re-seed the interval baseline; first engagement
  after a rejoin starts fresh.
- **Accepted tradeoff** (review n15): client-side actuation has no
  server-side neutralizer for a buggy fleet-deployed governor (the
  declaration design had one free). The kill switch is client config; B
  bounds the damage server-side.
- Diag attribution (review n14): the scanner's `rateGated` counter would
  conflate manual-knob and governor refusals — the `getRateGated`
  extension labels them separately.

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
  LSS-idle sessions for someone else's congestion) → the FIRST cut BINDS
  (review m7): `pingFactor = min(0.5 × pingFactor, 0.5 ×
  recentSendRate / cap)` — from factor 1.0, blind halvings would need ~6
  adjustments × ~15 s keepalive cadence ≈ 90 s+ before binding below a
  ~500 KB/s link; anchoring the cut to the OBSERVED send rate makes the
  first cut land below it, keeping the "~30 s to engage" live expectation
  honest. Floor: the factor that yields 64 KB/s effective.
- **Recover**: excess < 250 ms for 3 consecutive adjustments →
  `pingFactor = min(1.0, pingFactor * 1.25)`.
- **Documented over-cut** (review m8): after a binding cut the standing
  queue keeps excess elevated for many keepalives while it drains, and the
  attribution guard passes even at the floor rate — congestion events may
  drive the factor to floor, with recovery over minutes. Acceptable for a
  coarse backstop. Keepalive latency also includes server MSPT stalls, so
  a server lag spike mass-cuts every active player — the safe direction.
- **Baseline seeding** (review m9): 0/absent latency samples are IGNORED
  (never anchor a ~0 baseline that reads natural ping as excess); the
  baseline seeds from the first nonzero sample. Accepted bias: a session
  whose first sample lands after LSS congestion already began anchors HIGH
  and under-reads excess until drain or drift catches up.
- **Pre-implementation check, RESOLVED** (shared with A's signal): 26.2's
  keepalive smoothing is confirmed `(3·latency + sample)/4` — a smoothed
  field reads A's burst sawtooth near its MEAN, which is silently
  load-bearing for the composition margin on both platforms.
- Composition rule — ONE governor per session where possible, but A is now
  INVISIBLE to the server (client-actuated), so strict suspension is
  impossible. The safe composition: B's cut threshold (750 ms excess) sits
  far above A's converged operating point (~hundreds of ms), so on an
  A-governed session B never reaches its trigger — the loops separate by
  OPERATING REGION instead of population. The margin DEPENDS on review
  M2's quarter-batch actuation: A's converged burst is ~desired/4 ≈ 250 ms
  of link time (a 1 Hz full-batch actuator would peak ~800 ms and graze
  B's threshold — a constructible A-trips-B loop). If both ever act (A
  mis-converged high), they push the same direction with B coarse and slow
  — bounded, non-oscillatory (B cuts at most once per 5 s and recovers
  slower than A adapts). Effective server cap: `min(alloc, cap ×
  pingFactor)`.
- Kill switch: `enablePingBackstop` (server config, default true) — ALSO a
  `/lsslod set` row (the registry's first boolean row; the AUTO ceiling's
  precedent made its kill switch a live row, and B's live A/B on the rig
  is this program's working method — a config-edit-plus-restart lever
  would make the live gate needlessly slow).
- Integration precision (review m4 + m12): both services compute the
  per-player cap once OUTSIDE the player loop — `cap × pingFactor` is
  per-player and MUST compose into the `allocationBytes` argument passed
  to `flushSendQueue` inside the per-player loop: the per-player bandwidth
  bucket's bank clamp is `burstCap = allocationBytes/4`, so only this
  plumbing shrinks the banked burst (up to ~6.25 MB at default caps) on
  the FIRST post-cut tick — applied anywhere else, a cut leaves the
  old-cap bank intact for one full burst. Pinned in T1. Fabric reads `player.connection.latency()` (the
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
  config-allowlist comment naming AutoOutboundCeilingTest; the
  `RuntimeSettings` apply-note text for the key; the move-tracer boot-row
  echo note naming the ceiling (verify at implementation — control n13).
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

## Live round 2 (2026-08-13, the honest ungoverned-manual test)

The first "PASS" was accidentally run with the manual 50-col/s cap still set.
The honest test (manual cap 0, governor alone): **~500 ms steady, spikes to
~1500 ms under movement** — significantly better than the pre-program ~5 s,
but two mechanisms showed up in the receipts (`governed=60/s (320 KB/s)`,
server `pingf=0.02, yielded=747, paced=852, sq=308`):

1. **The AIMD equilibrium hovers AT capacity**: the climb/overshoot cycle
   keeps the link full, so a standing few-hundred-ms queue never drains —
   the steady ~500 ms. RETUNED: drain every 4th kept-up (was 8th) at STEP/2
   depth (was STEP/4) — the bleed now outruns the climb's overshoot.
2. **Movement spikes are vanilla competing**: the governor kept climbing
   +STEP into the window where a moving player's vanilla chunk bursts share
   the link; the cut then took 1-2 intervals — the spike's duration.
   ADDED: the movement hold — a kept-up interval that saw a chunk crossing
   (the manager's recenter hook) holds `desired` (no up-probe; shortfall
   still cuts; the drain streak is untouched).
3. **The backstop fired during the spikes** (`pingf=0.02`) and behaved
   exactly as designed: its binding cut landed at ~1 MB/s allocation —
   ABOVE the governor's 320 KB/s ask — so it never actually throttled the
   session (the operating-region separation held in the BINDING sense even
   though the spike excess crossed 750 ms). The server's standing
   `sq=308` is resolved-but-unsent RAM, not link latency (obuf stayed
   ~3 KB); the relevance prune + re-declaration own it.

## Live round 3 (2026-08-13, the instrumented flight — VERDICT)

One 328 s traced session (stand/fly/stand) with the governor-state +
real-ping-probe net rows settled all three hypotheses:

- **Final standing state: 82/87/90 ms (med/p90/max) with LODs streaming at
  ~134 KB/s and the governor correctly DISENGAGED** — the retuned drain +
  movement hold deliver the design goal; the user reports block-breaking
  "feels pretty good".
- **Movement spikes are H2 — vanilla's own chunk bursts**: while flying, the
  governor sat cut at ~122 KB/s and LSS DELIVERED a median 44 KB/s (near
  nothing), yet ping still burst to 3.5-4.4 s in ~10% of seconds — each
  terrain crossing ships several hundred KB of vanilla chunk packets on a
  ~500 KB/s link. LSS has nothing left to yield; the lever is vanilla-side
  (server view distance sizes the bursts).
- **The early-backfill minute carries brief pre-engagement spikes** (p90
  335 ms, max 1.9 s while ping's MEDIAN stayed under the engage conjunct) —
  the documented corner; the server pacer + yield kept it bounded.
- Instrument note: the trace's `rtt_p50/p95` measure WANT-latency including
  the server's standing queue (minutes during a big backfill), not network
  RTT — relabel before anyone reads them as ping.

**Program verdict: the LSS side of slow-link latency is DONE.** Remaining
movement discomfort at 4 Mbps is vanilla's own traffic (control test:
receiveServerLods=false on the same route).

## Live round 4 (2026-08-13, 6 Mbps — "acceptable", then the high-bandwidth PASS)

At 6 Mbps (just above vanilla's recommended 5): join hurts ~10 s (the
resolution-wave burst; the governor engaged at +1225 ms excess 9 s in, cut to
68 KB/s, healed), then flying feels GOOD — vanilla fits with headroom. The
governor re-engaged silently (once-per-session INFO latch) and converged at
~693 KB/s ≈ link capacity. **The high-bandwidth direct connection (no proxy)
was then tested and "works great"** — the governor never engages on a fast
link, closing that acceptance item.

## Live round 5 (2026-08-13, the fly-then-stop DESYNC — the sawtooth trace)

User report: "lots of desync when I move a lot then come to a stop", still at
6 Mbps. The 185 s trace (`lss-trace-20260813-204246.jsonl`) is decisive and
falsified one shipped design decision:

- **Rows 0-119 (~2 min): engaged at 611 KB/s — ping ~75 ms even flying at
  33 blk/s.** The governed state was delivering the design goal during
  movement.
- **Row 120: the ping-normal disengage (path b, review m10) fired** — 30
  intervals of the calm the cap itself was producing. The classic AIMD trap:
  the controller read its own success as "no longer needed".
- **Rows 120-149: line-rate runaway.** Wire jumped to ~730 KB/s, the flight
  declared the full disc, the server queue went deep, ping climbed 419 →
  4741 ms over ~25 s, `miss_view` climbed 20 → 43 (vanilla 20+ chunks behind
  its permanent edge-ring floor) — and the player rubber-banded on stopping
  (the server's move tracer booked new `rejected`/`silent` events in this
  exact window; the moved-wrongly class, 26.2 clients walk into unreceived
  terrain). The runaway lasted the full **30 s tab-ping blind spot** — the
  governor's congestion input refreshes at 600-tick cadence with (3l+s)/4
  smoothing, so it could not see what it had un-capped.
- **Row 150: the tab ping finally refreshed → re-engaged at 605 KB/s → ping
  instantly back to ~75 ms.** The user's "settles at an acceptable level but
  not very consistent" IS this sawtooth (period ~90 s).

Fixes (branch `fix/vanilla-first-cut`, 2-subagent-reviewed):
1. **The ping-normal disengage is DELETED** — disengagement is rate-evidence
   only (10 qualifying intervals above 4 MB/s). Normal ping under a binding
   cap is the governor's own success, never link health. A frozen
   (demand-limited) engaged session now stays engaged, deliberately: the cap
   sits above actual demand and the kept-up climb resumes with demand
   (net +2.5·STEP per 4 kept-up intervals with the drain bleed, so a healed
   link's rate disengage stays reachable).
2. **The governor's ping input moved to a client-driven 1 Hz probe**
   (`ServerboundPingRequestPacket` → vanilla's pong handler → the debug
   overlay's ping logger; tab ping stays as the no-sample fallback) — the
   trace's own probe mechanism, promoted. Any future runaway is visible in
   ~2 s, not ~25. Probe is `governorActive && !harnessJvm()`-gated (soak
   inertness).
3. **The vanilla-first cut**: a missing-vanilla-chunks count feeds a
   session-MIN floor (the permanent view-edge ring, ~20 on the rig); while
   ENGAGED, an interval seeing excess ≥ 8 over the floor CUTS unconditionally
   (before the offer-backing freeze — a PRIORITY decision, not rate evidence:
   the world around the player is missing, so LSS's link share is starving
   vanilla's catch-up). Unengaged (fast-link) sessions never evaluate it.
   Floor clears on reset + dimension change.

**2-subagent review round (control-loop + wiring lenses), all findings fixed:**
- **M1 (engage debounce):** the raw 1 Hz probe is spikier than the damped tab
  signal the 250 ms threshold was calibrated on, and the deleted escape made a
  false engagement sticky — ENGAGE now requires **2 consecutive qualifying
  congested intervals** (`ENGAGE_CONSECUTIVE_INTERVALS`); a transient resets
  the pending streak. The residual "engaged forever while server-limited"
  state was analyzed and accepted: the AIMD oscillates desired within ±STEP of
  the achieved rate (kept-up climb ↔ offer-backed cut), so a stuck engagement
  barely constrains — pinned by
  `bindingCapWithNormalPingNeverDisengagesWhileServerLimited`.
- **M2 (floor over a settings-dependent baseline):** client render distance /
  server view distance can change mid-session, and a stale-LOW floor would
  read the new permanent ring as perpetual excess → pinned at MIN with the
  disengage streak suppressed. Fixed with the ping baseline's own pattern: the
  floor **drifts up toward the newest sample** by max(1, gap/8) per evaluated
  interval (never past it; min-snap restores a lower floor instantly) — a
  spurious floor heals in ~a dozen intervals, a genuine vanilla-behind episode
  keeps its gap while the view keeps falling behind.
- **M3 + wiring-M1 (stale input):** the scanner's cached count updates only on
  PERIODIC fires — unbounded staleness in the governed 4 Hz steady state, and
  one tick after a dimension change it still holds the OLD dimension's count
  (the floor-relearn defeat). Fixed structurally: the manager samples the
  **live** `missingVanilla` supplier on the same 1 Hz probe cadence
  (`governorActive && !harnessJvm()` gated — one O(RD²) sweep/second, only
  while active); the scanner's cache stays the diagnostic it always was.
- **m2:** the cut now anchors `min(desired, measured)` (the MINOR-3 drain
  rule) — the post-cut in-flight tail measures above desired and a bare
  measured anchor would RAISE the cap mid-shed
  (`vanillaFirstCutNeverRaisesDesired`).
- **n1/m4:** the probe throttle seeds at `Long.MIN_VALUE/2` (a 0 seed sits
  AHEAD of a spec-legal negative nanoTime epoch and silences the probe
  forever). Wiring review verified in 26.2 bytecode: the ping logger RESETS on
  every disconnect path (no cross-server stale samples), pong units are ms,
  and `Connection.send` off-main matches the trace's proven pattern.

## Test plan

- T1: governor AIMD unit suite (engagement gate incl. demand-backing, the
  no-idle-collapse pin, the #71-halt non-qualifying pin, the reset/negative-
  delta non-qualifying pin, **the congestion-conjunct pin — a demand-limited
  shortfall with normal ping must NOT engage**, bootstrap-by-shortfall,
  kept-up/shortfall steps with the ABSOLUTE band, the every-8th-kept-up
  drain interval, disengagement both paths incl. the ping-normal exit, the
  min(manual, governed) composition incl. BOTH off-sentinel cases, **the
  seam-split pins — budget site gets ceil(R/4), spacing site gets R, the
  4 Hz equilibrium; the manual knob's single-value shape unchanged**, the
  asymmetric size-estimator pins (fast-up/slow-down, pre-first-sample
  guard, the ocean→terrain no-over-burst case), the floor-at-1 pin, the
  v16-session exclusion, the soak/benchmark property gate, kill switch,
  the rateGated attribution split); ping backstop unit suite (baseline
  drift + zero-sample seeding, attribution guard, the BINDING first cut,
  cut/recover ladder, the operating-region separation constants, **the
  allocationBytes plumbing pin — a cut shrinks the bank clamp on the first
  post-cut tick**, kill switch + its registry row); deletion pins (the
  7-arg flush overload's fixed-ceiling semantics unchanged; `ceil=`
  renders fixed/off; set row 0 = OFF); diag token goldens (`pingf=`
  insertion re-goldens the full line).
- T2 re-run. Paper T1 is a CHANGED surface (review M2): the config-test
  AUTO block rewrites to 0=OFF and the new key rows land there too.
- Guard soak: fresh-backfill (both governors must be structurally inert).
- **Live gate — the 4 Mbps throttled session: PASS (2026-08-13, user-run,
  exceeded the criteria)** — ping settled to ~30 ms (the target was
  300-600 ms) with LODs still streaming on the throttled proxy. The server
  receipts told the ideal story: `sq=0/1024, obuf=4.0 KB, pingf=1.00,
  yielded=0, paced=0` — the CLIENT governor alone held the rate under link
  capacity, so no server mechanism ever fired (the backstops backstopped);
  the only queue evidence was one bounded pre-engagement burst (obuf
  high-water 348 KB). Original expectations kept below for the record:
  tab ping settling to ~300-600 ms while LODs stream at ~0.35-0.45 MB/s
  wire (the client INFO logs the engaged rate); `yielded=` low;
  disconnect/rejoin re-engages within ~2 s. A second check with the CLIENT kill switch off: behavior
  degrades to yield-only (today's shape) and `pingf=` engages within ~30 s
  if ping balloons — B's live receipt. **Expected limit cycle (impl review
  m4, documented not fixed)**: on a permanently slow link the ping-normal
  disengage can produce a governed/ungoverned sawtooth (~60 s capped,
  ~30-45 s uncapped while the 30 s-stale tab ping catches up) — read it as
  the documented cycle, not a failure; if disruptive, the fix is
  re-engagement hysteresis.

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

**Control lens (Fable, 2026-08-13) — IMPLEMENT WITH FIXES, all folded:**
M1 the engagement gate could not distinguish link-limited from demand/
server-limited delivery — every walking player on a healthy link (LOD
trickle with a standing awaiting set) and every gen-bound cold join would
have engaged fleet-wide, and loopback-soak inertness was false → the
congestion conjunct (client tab-ping excess > 250 ms over a rolling-min
baseline) is now an engagement requirement, making harness inertness
structural (ping ~0) with the property gate as armor. M2 the governed
actuator was 1 Hz × full-second batches by the spacing-gate math (burst
~4× the plan's 220 ms claim, grazing B's 750 ms threshold — the A/B
margin was consumed) → the seam split: ceil(R/4) at the budget site, R at
the spacing site, equilibrating at the 5-tick floor = 4 Hz quarter-
batches. M3 the multiplicative 0.9 kept-up band ratchets standing queue
(equilibrium up to ~1.11× capacity) and a rate-matched loop never drains
INHERITED queue (the pre-engagement bank ~cap/4) → absolute band
(desired − STEP/4) + the ping-driven drain bias. M4 bimodal column sizes
(ghost-clears vs terrain) make a symmetric mean over-burst several× at
regime boundaries → asymmetric fast-up/slow-down estimator, seeded from
session start, division guarded. m5 folded into the actuation floor/
sentinel spec (the governed budget=0 walk-death case). m6 2 s measurement
intervals (phase aliasing). m7 B's first cut now BINDS (anchored to
observed send rate — blind halvings needed ~90 s). m8 B's over-cut-to-
floor documented as accepted; MSPT mass-cut noted safe. m9 B baseline
seeding specified (ignore zero samples; accepted high-anchor bias). m10
frozen-engaged closed by the ping-normal disengage path. m11 stalled
intervals non-qualifying, stated as deliberate (B owns total stalls). m12
the pingFactor plumbing must ride allocationBytes into flushSendQueue so
the bank clamp shrinks on the first post-cut tick — pinned. n13 residue
inventoried. n14 rateGated attribution split. n15 no-neutralizer tradeoff
recorded. n16 verified clean. Also verified by this lens: the v1 RAW-vs-
WIRE unit mismatch (server charges RAW, client measures WIRE) would have
ratcheted every zstd session to the floor under the declaration design —
the pivot made the loop unit-consistent, an independent argument for
client actuation. Two pre-implementation checks recorded: 26.2 tab-
latency refresh cadence/smoothing (A's signal; >10 s staleness switches
the drain bias to the deterministic every-8th-kept-up variant) and
keepalive smoothing (load-bearing for the A/B margin).

**Implementation review (3 Opus, 2026-08-13) — all three MERGE WITH FIXES,
all folded:**

*Control lens* — MAJOR-1 (the design finding): the governed actuator is a
stop-and-wait WINDOW (the outstanding-divisor gate waits for the whole
quarter-batch below B=20), so any answer-latency floor above 250 ms —
`enableAdaptiveScanCadence=false`, movement past the ring-128 walk-cost
coverage limit, actionable-retry/pressure holds, gen-bound serves, base
RTT ≥ ~300 ms — read as link shortfall and ratcheted `desired` to
MIN_RATE on exactly the target populations. FIXED two-sided:
**offer-backing** (a downward step requires the interval to have OFFERED
≥ ¾ of the governed rate — the manager counts post-send declared columns;
an under-offered shortfall FREEZES) and the **burst site falls back to
the full sustained rate when the adaptive cadence is off** (1 Hz full
batches — the manual knob's shipped shape — instead of quarter-rate
forever). Residual accepted: dynamic under-offer (movement past ring 128,
RTT > ~500 ms) freezes `desired` and delivers below it until conditions
clear — bounded, safe-direction, disengaged by the ping-normal path.
MAJOR-2: B's changed-ping gate sat before BOTH branches, and 26.2's
integer smoothing is bit-stable on calm links — a cut factor could freeze
below 1.0 for the session. FIXED: the gate is cut-side only; recovery
runs on the 5 s cadence. MINOR-1 window anchors now advance every
evaluation (the attribution guard reads a real ~5 s window; the
inverted tiny-rate anchor is gone). MINOR-2 dimension change now keeps
the governor's MEASUREMENTS (baseline + size estimate) and drops only
control state (`onDimensionChange()`); full reset stays session-scoped.
MINOR-3 the drain interval anchors at min(desired, measured) — an
EWMA-lag interval can no longer RAISE desired. N7 recorded: the #71
taper shrinks the batch but not the spacing charge, so the taper's
sustained-rate effect is limited — pre-existing with the manual knob.

*Integration lens* — M1 production-wiring pins added (see the test list
below). M2 `enablePingBackstop` added to check_soak.py's config
allowlist (the R4/S-8 same-commit rule — without it no soak arm could
ever A/B the backstop). m1 the LSSConstants 0=AUTO javadoc rewritten.
m2 the governor clock is now monotonic (nanoTime-derived — an NTP step
read as a rate collapse). m3 `resetFactor()` re-anchors the window (a
live off→on A/B no longer mis-anchors its first cut). m4 RECORDED as a
live-gate expectation, not fixed: on a permanently slow link the
ping-normal disengage (60 s calm) can open a governed/ungoverned
SAWTOOTH (~60 s capped, ~30-45 s uncapped while the stale tab ping
refreshes) — the live gate should read this as the documented limit
cycle, not a failure; B backstops the severe end. If observed as
disruptive, the fix is re-engagement hysteresis, not removal. m5 the
move-tracer boot row echoes `enablePingBackstop`. m6/n1 registry
containsAll + parseBoolean trim.

*Test-adequacy lens* — M1 the governor had ZERO wiring coverage: added
`engagedGovernorShrinksTheDeclaredBatchThroughTheProductionWiring` (also
reds a swapped-lambda wiring), `governorKillSwitchBindsThroughThe
ProductionConfigRead`, `legacySessionExcludesTheGovernor` (manager
level), and the scanner-level `governedSeamSplitEquilibratesAtFourHertz`
(M2 — the 4 Hz equilibrium through the real spacing gate). M3 the
zero-sample baseline pin was vacuous at a 600 ms natural ping (below the
cut threshold — it verified the threshold, not the seeding); re-pinned at
1000 ms where a zero-anchored baseline WOULD cut. Also added: disengage
path (a) (`sustainedRateAboveTheEngageThresholdDisengages`), the
pre-first-sample no-cap guard, the drain-never-raises pin, the
under-offered-freeze pin, both harness properties, the operating-region
constants pin, the refused-tick floor-counter pin (re-covering the one
surviving shape the deleted AUTO suite pinned), the `pingf=` value-render
pin, the observe-pass + formatter-plumb source-regex pins, and the
recovery-on-unchanged-calm-ping pin.
