# Join slow start for the client transfer governor — implementation plan

**v1.3 — 2026-08-14. Status: MERGED (PR #179) + the round-3 post-merge review
fold (§8 records all three rounds). User decisions: join latency beats LOD fill
speed; toggle in client config AND the Sodium menu, default enabled.**

Normative context: adaptive-transfer-rate-plan.md (Mechanism A — the governor this
amends), `TransferRateGovernor` (xplat) + `LodRequestManager`/`ClientSessionGate`
wiring.

## 0. Problem

The governor is reactive-only: sessions start UNCAPPED and engagement requires
evidence (ping excess >250 ms over a rolling-min baseline, ×2 intervals). Three
defects concentrate at join:

1. **The damage window is the evidence window.** At join the client declares its full
   800-position want-set while vanilla delivers its spawn disc; earliest engage is
   ~4-6 s in; on a slow link the queues are already full and drain slowly (the
   bufferbloat finding) — the first impression is seconds of input lag.
2. **Baseline self-pollution.** The rolling-MIN ping baseline is established while our
   own flood inflates every sample. Scope honestly (review): slow start fixes this
   only when INITIAL genuinely fits under the link — which is why INITIAL is
   MIN_RATE (§1.1), and why sub-512 kbps links remain Mechanism B + transport
   yield's territory, not the ramp's.
3. **Vanilla-first stops at the engage boundary.** The missing-vanilla signal
   evaluates only on engaged sessions, but the join burst is the likeliest
   vanilla-behind moment.

## 1. Design — a phase machine over the existing AIMD

`boolean engaged` generalizes to `enum Phase { DISABLED, RAMP, OPEN, ENGAGED }`.
Today's unengaged = OPEN (capless); today's engaged = ENGAGED (byte-for-byte
unchanged AIMD, its disengage landing in OPEN). DISABLED = capless + inert (the
`!active` posture). Downstream unchanged: min-compose with the manual knob, the
seam split, floor-at-1, harness/legacy gating, `adoptFrom`.

### 1.1 Constants

```java
static final long SLOW_START_INITIAL_BYTES_PER_SEC = MIN_RATE_BYTES_PER_SEC;   // 64 KB/s
static final long SLOW_START_CEILING_BYTES_PER_SEC = 2 * ENGAGE_BELOW_BYTES_PER_SEC; // 8 MB/s
static final double SLOW_START_SEED_COLUMN_BYTES = 32.0 * 1024;                // pre-sample conversion
static final long RAMP_ENGAGE_MIN_DESIRED_BYTES_PER_SEC = ENGAGE_BELOW_BYTES_PER_SEC / 2; // 2 MB/s (impl review)
static final int RAMP_MOVEMENT_HOLD_EXCESS_MS = ENGAGE_PING_EXCESS_MS / 4;      // 62 ms (impl review)
```

- **INITIAL = MIN_RATE (64 KB/s), not STEP** (both reviewers): 256 KB/s ≈ 2.1 Mbps
  already saturates a 1 Mbps link 2× from the first interval — reproducing defect 2
  instead of fixing it. 64 KB/s = 512 kbps fits under 1 Mbps, the first intervals
  are genuinely quiet, the baseline seeds clean, and the first overshooting doubling
  is measured against a TRUE baseline → the ramp engages at the knee, which is the
  entire point. Fast-link cost: two extra doublings = 4 s. Revised numbers:
  ~14 s to the ceiling, ~4-5 MB ≈ ring 8-10 of LODs in the first 10 s (ring ~8 at
  32 KB columns — the hedge is deliberate), OPEN at ~35 s.
- The 8 MB/s ceiling bounds the OPEN-confirmation stretch; parking AT the ceiling is
  verified harmless (8 MB/s wire ≈ 16-24 MB/s raw ≈ the 25 MB/s per-player cap).
- The 32 KB seed applies ONLY in RAMP while `sizeEwmaBytes < 0`: the safe direction
  is fewer columns than the byte budget intends. RAMP→ENGAGED always has a real
  sample (verified: bytes and columns increment together, so any measured interval
  recorded one, and the first sample REPLACES the seed rather than decaying).

### 1.2 Phase rules

**Entry.** Manager construction with the toggle armed → RAMP at `restartHint`
(session-fresh = INITIAL). The `!active` tick path hard-resets to DISABLED; the next
ACTIVE tick re-enters RAMP at the hint (the two entry points agree: construction
covers the fresh manager, the tick covers reactivation — first-walk clamping holds
either way because `governor.tick` precedes `tickScanPhase` and no scan runs before
the SessionConfig exists; both pinned). **`restartHint` survives `hardReset` and
dies only in `reset()`** (the toggle/DISABLED paths depend on this — stated
explicitly per review).

**RAMP interval evaluation** (2 s cadence). **RAMP's qualifying differs from
ENGAGED's** (impl review MAJOR, all three lenses — the byte-vs-position
denomination): `!invalid && (deltaBytes > 0 || deltaAnswered > 0) &&
awaitingSeenThisInterval && !halt`. A warm rejoin's answers are up_to_date frames —
zero wire bytes — yet are real answered demand, and the awaiting term is a LATCH
(any mid-interval tick with a non-empty awaiting set) because the boundary-instant
pair is bimodal at ramp-sized batches (the 2 s interval is 0 mod the 5-tick fire
cycle). A NON-qualifying interval is **no observation**: it neither credits nor
resets the OPEN streak (only a qualifying-but-uncredited interval resets).
Two derived quantities: `offeredBytesPerSec ≈ deltaDeclared × sizeEwma / elapsed`
(the offer-backing input re-denominated in bytes) and the **proportional kept-up
band `measured ≥ ¾·desired`** (review: the absolute STEP/4 band was calibrated for
ENGAGED's MB/s region and degenerates to `≥ 0` at INITIAL = MIN_RATE — every
bottom-rung interval would double vacuously). Rows in order:

| Condition | Action | OPEN-streak credit |
|---|---|---|
| ping excess > 250 ms **and** `measured < ENGAGE_BELOW` (the conjunct kept VERBATIM — review: "a ramp is below it by construction" was false at the 8 MB/s ceiling, and dropping it let a 2-interval ping blip engage a fast link at an 8 MB/s anchor) **and** `desired ≥ RAMP_ENGAGE_MIN_DESIRED` (impl review MAJOR: below that rung the measured-below term is TRIVIALLY true — the ramp itself caps measured — so vanilla's own join-burst ping would engage a 100 Mbps join at a MIN-rate anchor with a ~70-90 s recovery; below the gate a congested interval falls through to the plateau snap, which tracks desired down toward measured — containment without a false engagement) | streak++; at ≥2 → ENGAGED via existing `engage(measured, excess)`. **Streak 1 is a HOLD** (review: the first excess interval must not fall through and double into detected-but-undebounced congestion). Non-excess intervals reset the pending streak (the existing `else` rule). | no |
| `vanillaBehind()` | HOLD (no cut — floor freshly learned at join, ramp rates low; the HOLD half of vanilla-first) | no |
| movement seen **and ping excess > RAMP_MOVEMENT_HOLD_EXCESS_MS (62)** (plan review MAJOR, both lenses: an UNCONDITIONAL movement hold pins every join-then-travel session at INITIAL forever — the elytra wall reborn, on a rig that hands out elytra at spawn; impl review MAJOR: `> 0` against a rolling-MIN baseline is a JITTER detector — ordinary WAN jitter sits above the session floor most intervals, so join-then-fly would still freeze the whole flight. A quarter of the engage threshold is above jitter, far below congestion) | HOLD | no |
| under-offered (`offered < ½·desired`) | HOLD — demand-limited; a converged client sits mid-ramp and resumes with demand. ROW ORDER IS LOAD-BEARING (round-3 MAJOR): this row must precede the growth rungs — evaluated after them, answered-all-asked doubled on one-column dirty-edit trickles (offered at a fraction of desired, trivially answered) and ~17 sparse intervals walked a never-proven link to capless OPEN, non-qualifying gaps preserving both streaks along the way (pinned) | no |
| **delivered-all-offered** (`offered ≥ ½·desired` and `measured ≥ ¾·offered`, while `measured < ¾·desired`) — the high-RTT rule (review HIGH: the stop-and-wait window caps offered at ~0.6-0.7×desired on ≥250 ms RTT links, so the classic band is unreachable and the session parks at INITIAL on a clean fast link; the link delivering everything asked of it IS growth evidence — doubling widens the window, which is exactly how a windowed protocol discovers capacity, and the ping conjunct + Mechanism B bound the overshoot) | `desired = min(desired × 2, CEILING)` | yes |
| kept-up (`measured ≥ ¾·desired`) | `desired = min(desired × 2, CEILING)` | yes |
| **answered-all-asked** (`deltaDeclared > 0` and `deltaAnswered ≥ ¾·deltaDeclared` — impl review MAJOR, all three lenses: a warm rejoin is answered entirely with up_to_date frames, ZERO wire bytes, and every byte-denominated rung fails structurally — the session parks at 2 col/s for the whole revalidation. Positions answered in the interval = the actuator's stop-and-wait window saturated with timely service; the answers that carried no bytes cost the link nothing, so growth is safe by construction, and it is the ONLY signal a revalidation-dominated session ever produces) | `desired = min(desired × 2, CEILING)` | yes |
| plateau (offer-backed shortfall) | `desired = clamp(5·measured/4, INITIAL, desired)` — the ONE-TIME overhang snap (review: a bare HOLD leaves desired at up to 2× capacity, a permanent standing offer; the snap bounds the overhang at 25% and never raises). Also the CONTAINMENT path for congestion below the row-1 gate: excess intervals land here and desired tracks measured down to the INITIAL floor (pinned). BYTE EVIDENCE REQUIRED (round 3): a zero-byte answer-only interval reaching this row (partial answers — growth failed) HOLDs instead of snapping — measured 0 means nothing needed bytes, and the snap wiped an earned ramp to INITIAL on a server hiccup (pinned). The never-raises min-guard is structurally redundant given the ¾ band (`measured < ¾·desired ⇒ 5·measured/4 < 15/16·desired`) — kept as defense-in-depth against band retuning | no |

**RAMP → OPEN**: `desired > ENGAGE_BELOW` on 10 consecutive CREDITED intervals
(the table's credit column — holds and plateaus don't credit; the reuse of
`DISENGAGE_RATE_INTERVALS` is the constant, not the code path) → OPEN, one INFO.
Sessions that never credit (demand-limited at the ceiling) park capped ABOVE
demand — verified harmless.

**ENGAGED**: today's `stepEngaged` byte-for-byte; its rate-disengage lands OPEN.

**Dimension change**: re-enter RAMP at `restartHint = clamp(prior/2, INITIAL,
CEILING)`; prior = the RAW `desiredBytesPerSec` field read BEFORE the hard-reset
(review: the accessor returns 0 unengaged), or `ENGAGE_BELOW` when prior phase was
OPEN. Honesty note (§7): this is a mild REGRESSION for proven-fast links vs
today's stays-uncapped hop — ~20-30 s of re-confirmation per portal trip, accepted
under the stated priority; it is strictly SAFER for governed links (today they get
uncapped for the re-engage gap).

**`adoptFrom`**: carries phase + hint + desired. The reflective
`adoptFromCoversEveryStateField…` pin's type switch gains an enum arm (review —
today it would `fail("unhandled field type")`).

**Toggle × phase table** (review, both lenses — the v1.0 "rides the active
recomposition" sentence would have UN-CAPPED an engaged congested link, the round-5
runaway shape):

| | RAMP | OPEN | ENGAGED | DISABLED |
|---|---|---|---|---|
| toggle OFF mid-session | → OPEN | unchanged | **unchanged** (it earned its state on evidence independent of the ramp) | unchanged |
| toggle ON mid-session | n/a | unchanged (no mid-play re-clamp of a working link) | unchanged | next session ramps |

The toggle governs the ENTRY phase only. Both directions pinned.

### 1.3 What deliberately does NOT change

ENGAGED's ladder + constants + INFOs; offer-backing; drain cadence; the
vanilla-first CUT (engaged-only); actuation + manual-knob composition; harness
gating (soaks untouched — pinned); the legacy-dialect exclusion (v16 sessions never
ramp). No integrated-server exemption is needed (LSS client sessions never activate
against the client's own integrated server — `ClientSessionGate`'s
`localIntegratedServer` gate; a LAN guest ramps, accepted). No cross-session
persistence (recorded follow-up).

### 1.4 Test-compatibility strategy (corrected per review)

The governor class arms slow start via a package-private flag **default OFF at the
class level** — all 30 existing `TransferRateGovernorTest` pins pass unchanged
(verified: they construct raw governors and read capless starts). **The manager
suites are NOT untouched** (review): `LodRequestManagerTickTest` + the
`ClientSessionGate` manager tests construct real managers in non-harness JVMs, and
production arming would clamp their first walks (the 24-position and
`WANT_SET_BUDGET` pins red). Their shared setup points gain one disable line, and
`productionDefaultEnablesSlowStart` (the `productionDefaultEnablesOutwardDamping`
precedent) pins the real wiring.

## 2. Wiring

- `LSSClientConfig`: `enableJoinSlowStart` default true, under the
  `enableAdaptiveTransferRate` umbrella (governor off ⇒ no ramp). Default pin in
  `ConfigValidationTest` (the `enableAdaptiveTransferRate` ship-enabled pin is the
  precedent).
- **Sodium menu row** (user direction): boolean "Slow Start on Join" on the main
  page beside the rate slider, default enabled, `OptionImpact.LOW`, receive-LODs
  dependency, plain save handler. Lang: `lss.config.join_slow_start` + `.tooltip`
  ("Start LOD downloads slowly after joining and speed up as the connection proves
  itself — keeps joining responsive on slow connections. Turn off to load LODs at
  full speed from the first second."). When `enableAdaptiveTransferRate` is false
  at menu build, pick a `.tooltip.governor_off` variant (the SeeU conditional
  precedent) noting the toggle is inert. VSS lang needs nothing (the rebrand is a
  blanket value rewrite; these strings carry no brand token — recorded so nobody
  re-derives it).
- `LodRequestManager`: arm at construction from config; diag `governed=` gains
  phases (`ramp@<KB/s>`, `open`, `engaged@<KB/s>`, `off`).

## 3. Alternatives considered (verdicts recorded)

Defer-until-vanilla-ready (binary; absorbed as the vanilla HOLD row). Server-side
join ramp (server can't see the link; client fix works against every v17+ server;
backstop covers old clients). Backstop/yield/pacing alone (reactive or
queue-shaped; the AUTO-ceiling structural finding stands). Lower server caps
(punishes fast clients). Per-server persisted capacity (deferred follow-up).

## 4. Tests

`TransferRateGovernorTest` (armed governors): the full §1.2 row table incl. the
credit column; the kept-VERBATIM engage conjunct + streak-1-holds; the
delivered-all-offered growth rule (an RTT-shaped offered≈0.65·desired session must
reach OPEN); the ping-gated movement hold (clean-ping movement grows; excess
movement holds); the plateau snap (never raises, bounds overhang); INITIAL/CEILING
clamps; pre-sample seed; dimension hint incl. the raw-field read + OPEN-prior;
`adoptFrom` carry + the enum arm of the reflective pin (DETERMINISTIC — impl
review: comparing an enum field against its default is vacuous when src randomizes
to the default; the arm probes a fresh-governor baseline); toggle × phase table
both directions; DISABLED semantics; kill-switch-off = OPEN start bit-identical.
Impl-review additions: warm-rejoin byte-free growth (zero bytes, answered demand →
doubles), the awaiting latch (mid-interval demand qualifies; a demand-free interval
with kept-up bytes does NOT), congestion below the engage gate (never engages;
snaps to the INITIAL floor), the sub-62 ms jitter arm on the movement hold, the
N−1/streak-survives-idle restructure of the OPEN test, and the production pin's
NEGATIVE arm (toggle off ⇒ uncapped first walk — without it a hardcoded-true
supplier passes). Manager: production-arming pin, first-walk clamped, diag phases,
manager-suite disable lines. Full T1 both platforms + T2 + release_check as the
merge gate.

## 5. Live validation (scoped per review)

1. **1 Mbps arm + control**: join through the throttle proxy on branch vs main vs a
   `receiveServerLods=false` control (the attribution baseline — vanilla's own
   spawn burst dominates join ping regardless of LSS, so the honest expectation is
   "branch ≈ vanilla-only", never "no spike"). Expect: clean baseline seed, ramp
   engages near the knee, no post-join input-lag window.
2. **100 kbps arm**: NOT a ramp test (MIN_RATE is 5× that link). Join at 1 Mbps
   then reconnect through 100 kbps (the documented keepalive-safe procedure);
   expectation = Mechanism B cuts / transport yield engages; slow start is inert
   during the pre-LSS login phase and the plan claims nothing there.
3. **Fast-path regression** (test-server rig): `governed=` walks ramp→open in
   ~35 s; elytra-from-join (the spawnkit case) must NOT park — the ping-gated
   movement hold is the specific check.
4. **Modrinth rig** (user-driven): real-WAN join, `/lss diag` receipts.

## 6. Docs & rollout obligations (enumerated per review)

- CLAUDE.md: the Mechanism A paragraph (sessions now START in RAMP), the
  client-config bullet list (`enableJoinSlowStart`), the governor test-blob
  sentence, the Sodium page mention.
- Release-notes ledger: a player-facing item (join behavior change, default on,
  where the toggle lives).
- The pause: jar-affecting client work by explicit user direction — §4b's pinned
  jar hash goes STALE at merge; the re-arm package must re-pin from post-merge
  main, and the §4b checklist gains client-side receipts (Sodium row present,
  `governed=ramp@…`→`open` on a rig join). The rig SERVER needs nothing.

## 7. Risks (honesty items added per review)

- Plateau park keeps desired ≤ 1.25× measured post-snap (was up to 2×) — bounded
  standing offer, never worse than today's uncapped posture.
- Doubling overshoot: ≤ 1 interval before ping evidence accumulates; Mechanism B
  backstops.
- Dimension-change re-cap is a real fast-link regression (~20-30 s/hop), accepted
  under the stated priority.
- A moving session on a link with permanent small positive excess (>0 but <250 ms)
  holds ramp growth while moving — conservative by design; it converges when the
  player pauses.
- Warm rejoin: demand-limited under-offer HOLD, demand lower still — no perceived
  cost (pinned).
- Kill-switch drift: default pin + production-arming pin (+ its negative arm).
- **Below-gate sawtooth is the documented operating mode** on links slower than
  RAMP_ENGAGE_MIN_DESIRED: the ramp cycles double → snap-to-1.25×measured (a
  ~1.25×/2.5× sawtooth) without ever engaging — bounded, self-correcting, and
  Mechanism B backstops real bloat. Not a defect; do not "fix" it into an engage.
- **ENGAGED keeps the boundary-instant awaiting pair** (`awaitingAtStart &&
  awaitingSize > 0`): the same bimodal-sampling shape the RAMP latch fixes exists
  there as a MIN-rate latent (an engaged session whose boundary instants land
  answered can starve qualifying intervals). Pre-existing, NOT re-scoped here —
  recorded as a follow-up; RAMP consumes the latch, ENGAGED is untouched.
- **Answered-count inflation (round-3 PLAUSIBLE, accepted)**: `recordUpToDate`
  also counts answers for positions the tracker no longer tracks, so a server
  draining a superseded backlog can inflate deltaAnswered past the interval's own
  asks and earn a doubling the current asks didn't — direction-up, bounded by the
  engage/plateau containment and the under-offer gate; the plan's "any response
  type" language accepts it.
- **High-RTT offered inflation**: `offered` converts deltaDeclared through the size
  EWMA, so when the EWMA lags a regime change toward small columns the
  delivered-all-offered rung can overstate the offer and grow on a marginal link —
  bounded by the ping row above it and the plateau snap after it.
- **RAMP-parked sessions and `WantSetBudgetInvariantTest`**: a ramped client
  declares far below `WANT_SET_BUDGET` (the governed budget clamp), so prose
  elsewhere saying the client "declares the constant budget per scan" is qualified
  by the governor's cap — the static inequality itself is an upper bound and holds
  unchanged.
- **Tier 3 runs ARMED** (deliberate): the client gametest joins over loopback where
  the ramp earns rate immediately (answered + kept-up every interval); it ran green
  3× armed. Keeping it armed makes Tier 3 a standing integration receipt for the
  ramp's non-interference rather than exempting it (the soak/benchmark harness
  property gate is unchanged and pinned).

## 8. Review round records

### Round 3 — post-merge full-feature review (2026-08-14, Fable reviewer, part of the
### v0.11.0 changes-since-#158 wave)

One MAJOR: the implementation evaluated the growth rungs BEFORE the under-offer
hold, inverting the §1.2 row order — the fold's answered-all-asked rung then
doubled on one-column dirty-edit trickle intervals and ~17 sparse trivially-
answered intervals walked a never-proven link to capless OPEN (simulated; the
warm rejoin never needed the inverted order — its actuator-clamped declarations
put offered ≈ desired). FIXED by reordering to the spec + a trickle pin. One
MINOR, fold-exposed: a PARTIALLY answered zero-byte interval (server hiccup
mid-revalidation) fell to the plateau snap with measured = 0 and wiped the ramp
to INITIAL — FIXED with the plateau's byte-evidence guard (measured ≤ 0 HOLDs)
+ pin. Recorded-not-fixed: the answered-count inflation PLAUSIBLE (§7). Also in
the round's scope, verified clean: streak/engage lifecycles, the transition
matrix incl. adoptFrom completeness, negative-delta impossibility
(RequestMetrics totals are manager-lifetime monotonic), label/locale, the
build-time tooltip resolution (no menu row can flip the governor mid-session).

### Implementation review (v1.1 → v1.2, 2026-08-14, 3 Opus reviewers — all MERGE WITH FIXES)

Three CONVERGENT MAJORs (each found by ≥2 lenses independently): (A) the
byte-vs-position denomination — every growth rung was wire-byte-denominated, so a
warm rejoin (all up_to_date, zero bytes) parked at 2 col/s for the whole
revalidation → the answered-all-asked rung + rampQualifying accepting
`deltaAnswered > 0` + the awaiting LATCH replacing the bimodal boundary-instant
pair; (B) RAMP nullifies the measured-below engage discriminator — below ~2 MB/s
the ramp itself caps measured, so the verbatim conjunct is trivially true and
vanilla's join-burst ping engages fast links at a MIN anchor → the
`RAMP_ENGAGE_MIN_DESIRED` gate with plateau-snap containment below it; (C) the
movement hold's `excess > 0` against a rolling-MIN baseline is a jitter detector
(join-then-fly still froze) → the 62 ms threshold. Minors folded: non-qualifying
intervals must not reset the OPEN streak; the reflective-pin enum arm was vacuous
when the randomized source landed on the default (deterministic fresh-value probe);
the production pin needed its negative arm; the VSS-safe lang tooltip (no config
filename); plateau min-guard redundancy recorded; the below-gate sawtooth, ENGAGED
awaiting latent, high-RTT offered inflation, WantSetBudget prose qualification, and
the Tier-3-runs-armed decision all recorded in §7.

### Plan review (v1.0 → v1.1, 2026-08-14, 2 reviewers)

Governor-semantics lens (MERGE WITH FIXES): movement-hold park MAJOR (→ ping-gated
hold), engage-conjunct false premise (→ kept verbatim), debounce fall-through (→
streak-1 holds), absolute band degenerate at low rungs (→ ¾ proportional),
manager-suite compat claim false (→ owned edits + production pin), toggle
runaway (→ phase table), INITIAL→MIN_RATE, entry/hint lifetime + reflective-pin
enum arm + plateau honesty (all folded). Product/ops lens (MERGE WITH FIXES):
RTT park HIGH (→ delivered-all-offered rule), movement park HIGH (converged),
INITIAL HIGH (converged), toggle semantics MEDIUM (converged), validation arms
scoped + control arm, docs/§4b obligations enumerated, Sodium governor-off
tooltip + VSS-lang note, dimension-change regression admitted.
