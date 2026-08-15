# Join slow start for the client transfer governor — implementation plan

**v1.1 — 2026-08-14. Status: reviewed (2-reviewer round folded; §8 records it). User
decisions: join latency beats LOD fill speed; toggle in client config AND the Sodium
menu, default enabled.**

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

**RAMP interval evaluation** (2 s cadence; the existing qualifying definition).
Two derived quantities: `offeredBytesPerSec ≈ deltaDeclared × sizeEwma / elapsed`
(the offer-backing input re-denominated in bytes) and the **proportional kept-up
band `measured ≥ ¾·desired`** (review: the absolute STEP/4 band was calibrated for
ENGAGED's MB/s region and degenerates to `≥ 0` at INITIAL = MIN_RATE — every
bottom-rung interval would double vacuously). Rows in order:

| Condition | Action | OPEN-streak credit |
|---|---|---|
| ping excess > 250 ms **and** `measured < ENGAGE_BELOW` (the conjunct kept VERBATIM — review: "a ramp is below it by construction" was false at the 8 MB/s ceiling, and dropping it let a 2-interval ping blip engage a fast link at an 8 MB/s anchor) | streak++; at ≥2 → ENGAGED via existing `engage(measured, excess)`. **Streak 1 is a HOLD** (review: the first excess interval must not fall through and double into detected-but-undebounced congestion). Non-excess intervals reset the pending streak (the existing `else` rule). | no |
| `vanillaBehind()` | HOLD (no cut — floor freshly learned at join, ramp rates low; the HOLD half of vanilla-first) | no |
| movement seen **and ping excess > 0** (review MAJOR, both lenses: an UNCONDITIONAL movement hold pins every join-then-travel session at INITIAL forever — the elytra wall reborn, on a rig that hands out elytra at spawn. A clean-ping moving interval grows: that is today's uncapped behavior, which is safe absent congestion; only movement WITH any positive excess holds — the climb must not probe into vanilla's burst window while the link already shows strain) | HOLD | no |
| under-offered (`offered < ½·desired`) | HOLD — demand-limited; a converged client sits mid-ramp and resumes with demand | no |
| **delivered-all-offered** (`offered ≥ ½·desired` and `measured ≥ ¾·offered`, while `measured < ¾·desired`) — the high-RTT rule (review HIGH: the stop-and-wait window caps offered at ~0.6-0.7×desired on ≥250 ms RTT links, so the classic band is unreachable and the session parks at INITIAL on a clean fast link; the link delivering everything asked of it IS growth evidence — doubling widens the window, which is exactly how a windowed protocol discovers capacity, and the ping conjunct + Mechanism B bound the overshoot) | `desired = min(desired × 2, CEILING)` | yes |
| kept-up (`measured ≥ ¾·desired`) | `desired = min(desired × 2, CEILING)` | yes |
| plateau (offer-backed shortfall, ping normal) | `desired = clamp(5·measured/4, INITIAL, desired)` — the ONE-TIME overhang snap (review: a bare HOLD leaves desired at up to 2× capacity, a permanent standing offer; the snap bounds the overhang at 25% and never raises) | no |

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
`adoptFrom` carry + the enum arm of the reflective pin; toggle × phase table both
directions; DISABLED semantics; kill-switch-off = OPEN start bit-identical.
Manager: production-arming pin, first-walk clamped, diag phases, manager-suite
disable lines. Full T1 both platforms + T2 + release_check as the merge gate.

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
- Kill-switch drift: default pin + production-arming pin.

## 8. Review round record (v1.0 → v1.1, 2026-08-14, 2 reviewers)

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
