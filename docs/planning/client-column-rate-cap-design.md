# Client column-rate cap — manual override for weak clients (design)

Status: IMPLEMENTED 2026-08-05 (feat/client-column-rate-cap), one-subagent review round
(no MAJORs; the five MINORs — seam-binding pin, rateGated-placement pin, floor-at-1 and
v16 cap twins, doc/comment drift — fixed in the follow-up commit). Deviations from this
doc as written:

- **No `/lss trace` field** (§5 promised one): the trace is event-based and `rateGated`
  increments on non-fire ticks, so a trace field would need delta plumbing the design
  never specified; the scan event already carries `budget` (shows the clamp binding) and
  `fast`. The diag Budget line (`rate_cap=`/`rate_gated=`) and the benchmark exporter's
  `scan.rate_gated` carry the observability instead.
- **§7's send-failure/disconnect disarm-under-cap cases are not separately pinned**: both
  funnel through the same `noteDeclared(0)`/`reset()` paths the converged-disarm test
  covers.
- The production seam binding is pinned in `SpiralScannerTest`
  (`theDefaultSeamReadsTheProductionConfigField`), not `LodRequestManagerTickTest` as
  §7's preamble assumed — the cap seam is scanner-level; the manager never touches it.

## 1. Why

Issue #71's ingest backpressure (the decode-queue + `pendingIngestBacklog` taper) is
automatic and signal-driven. It regulates only what its two signals can see: LSS's own
decode queue, and whatever backlog a consumer *reports*. If a weak client's pressure shows
up somewhere else — frame time, GC, a Voxy build without the `getTaskCount` chain (the
bridge's probe reads no-signal and deliberately never costs the bridge), a choked
connection — the taper never fires and the client is fed at full rate. This feature is the
manual override for exactly that case: a user-visible dial that bounds how fast the server
streams LOD columns at them, independent of whether the automatic mechanisms detect
anything. (Prior art: the DH server plugin's client-advertised
`client_lod_request_concurrency_limit` — same idea, but theirs needs wire negotiation;
ours is free, see §3.)

Explicitly an *insurance* knob: if live weak-client reports show users needing it, that is
evidence the #71 taper missed a signal, and the taper should be fixed too. The knob must
not become the excuse not to.

## 2. Why a naive want-set budget slider is NOT the mechanism

The obvious implementation — clamp `WANT_SET_BUDGET` (800) by a config value — does not
do what the slider promises, because the adaptive scan cadence compensates:

- Sustained arrivals ≈ **budget × declaration cadence**. A batch is declared, the server
  drains it, ≥95% answered arms the fast re-scan, the next batch fires after as little as
  250 ms.
- Shrink the budget and batches complete *sooner*, so the fast path fires *more*: budget
  200 at 4 Hz is the same ~800 columns/sec as budget 800 at 1 Hz. Over most of the dial's
  range the user would see no change at all — a knob that lies.
- The smaller-batch → faster-completion feedback also means the dial's effect would be
  wildly nonlinear: nothing … nothing … cliff.

The quantity the user actually wants to bound is **columns per second**. So the slider is
denominated in columns/sec, and the mechanism derives *both* the budget and the fast-fire
spacing from it.

## 3. Mechanism — rate-anchored: budget clamp + size-weighted fast-fire spacing

New client config: **`lodColumnsPerSecondLimit`**, int, **default 0 = unlimited**
(bit-identical current behavior). Nonzero R (clamped, §6) enables two derived effects in
`SpiralScanner` — client-only, no wire change, no server knowledge, works against every
server including v16/v18 compat sessions:

**(a) Budget clamp — bounds the burst.**
In the budget computation (`SpiralScanner.maybeScan`, currently
`int budget = LSSConstants.WANT_SET_BUDGET;`):

```java
int budget = LSSConstants.WANT_SET_BUDGET;
if (cap > 0) budget = Math.min(budget, cap);
// existing #71 pressure scale applies AFTER, unchanged
```

The cap composes with the #71 taper by MIN (scale applied to the already-clamped base is
≤ both), same composition rule the taper's two factors already use. At most R columns can
be outstanding, so at most ~R can arrive inside any one declaration interval — the burst a
1 Hz fallback declaration can trigger is bounded, not just the sustained rate.

**(b) Size-weighted fast-fire spacing — bounds the sustained rate.**
In `fastRescanDue`, one additional gate beside the existing
`FAST_RESCAN_MIN_INTERVAL_TICKS` check:

```java
if (cap > 0 && ticksSinceFire * cap < TICKS_PER_SECOND * lastSentCount) return false;
```

i.e. after declaring N positions, the next FAST fire waits at least `20·N/R` ticks — each
batch pre-pays its own interval. This is a stateless token bucket: rate = ΣNᵢ/Σgapᵢ ≤ R/sec
by construction. The existing 5-tick floor and every other fast-path gate (pressure ¼-gates,
predicted walk cost, arming ladder) still apply on top — the cap is an additional ceiling,
never a replacement for the automatic regulation.

Properties that make this the right shape:

- **The 1 Hz fallback is untouched.** Re-declaration is the sole self-heal for silent
  server-side drops; nothing may ever suppress it. Only *fast* fires are spaced. With
  budget ≤ R, the fallback alone also stays ≤ R/sec, so the bound holds globally.
- **Converging-tail sparkle survives.** A tail batch of 10 positions at R=100 spaces to
  2 ticks → the 5-tick floor binds, still 4 Hz. Only full-size batches get pushed back
  toward 1 Hz. The thing the adaptive cadence exists for is preserved; the thing the cap
  exists for (sustained full-rate flood) is bounded.
- **Continuity with today.** Current max sustained = 800 × 4 Hz = 3200/sec. At R=3200 the
  spacing is `20·800/3200 = 5` ticks — exactly the existing floor. Current behavior IS the
  R=3200 point of this family; the dial extends it downward instead of bolting on a
  second mechanism. (We still bypass both effects entirely at cap=0 so the default path is
  bit-identical, per repo convention.)
- **Honest accounting.** A fast fire only happens when ≥95% of the last batch was answered,
  so `lastSentCount` ≈ new work; charging the ≤5% stragglers over-counts slightly —
  conservative in the right direction. Arrivals ≤ declarations (each declared position
  yields at most one column), so columns/sec ≤ R is an upper bound; superseded drops make
  the realized rate lower.

### Alternatives considered

| | Mechanism | Verdict |
|---|---|---|
| A | Budget clamp only | Rejected — cadence compensates, dial is a lie over most of its range (§2) |
| B | Budget clamp + hard-disable adaptive cadence when set | Works but blunt: kills the converging-tail fast path that costs nothing, and couples two settings invisibly. Fallback if (c)'s spacing gate proves finicky |
| C | **Budget clamp + size-weighted fast-fire spacing** | **Chosen** — provable ≤R/sec, tails stay fast, R=3200 ≡ today, one new comparison in an existing gate |
| D | Stateful token bucket | Same steady state as C with strictly more state; C is its stateless equivalent |

## 4. Sodium slider

Mirror the `lod_distance` pattern in `LSSConfigMenu` exactly:

- `builder.createIntegerOption(Identifier.parse("lss:column_rate_limit"))`, own group on
  the existing page, after the distance group.
- `Range(0, 3200, 50)`; `setDefaultValue(0)`; value formatter renders 0 as
  `lss.config.column_rate_limit.unlimited` ("Unlimited") — the `lod_distance`
  "Server Default" pattern.
- **AMENDED 2026-08-14 (granularity request — a user wanted ~20 col/s, unreachable
  with step 50):** the slider is now CURVED — the option int is an index into
  `RateSliderStops.STOPS` (0=off; 10..100 by 10, ..500 by 25, ..1000 by 50, ..3200
  by 100, ~59 stops), value formatter + binding map index↔rate, hand-edited
  off-curve values display snapped to the nearest stop and are rewritten only if
  the user moves this slider. `RateSliderStops` is Sodium-import-free so the
  Tier 1 curve pin (`ConfigValidationTest.rateSliderCurveRoundTripsTheClamp…`)
  can classload it; the round-trip invariant (every nonzero stop survives
  validate() unchanged) is pinned there.
- `OptionImpact.LOW` (client-side pacing, not render cost), binding to
  `cfg.lodColumnsPerSecondLimit`, `setStorageHandler(save)`, and the same
  `setEnabledProvider` gate on `lss:receive_server_lods`.
- Slider top = 3200 because that is where the mechanism provably no-ops (§3); no point
  rendering dead range. Hand-edited larger values are legal and inert.
- Lang keys in `assets/lss/lang/en_us.json`: name ("Max LOD Download Rate"), tooltip
  (suggested: "Caps how many LOD columns per second the server streams to you. Lower this
  if receiving LODs stutters your game or saturates your connection. 0 = unlimited."),
  and the "Unlimited" label.
- VSS branding: zero work — the screen already brands from the jar's own descriptor.
- Live-apply: the scanner reads the cap through a supplier each evaluation (§5), so a
  slider change takes effect on the next scan, no reconnect. Lowering mid-session is safe
  (next walk declares less); raising is too (the spacing gate re-evaluates per tick).

## 5. Touch points

- `fabric/.../config/LSSClientConfig.java` — `public int lodColumnsPerSecondLimit = 0;`
  + `validate()` clamp (§6). Comment block in the repo's style: what it bounds, that it is
  the manual override for #71's automatic taper, kill-value 0.
- `fabric/.../networking/client/SpiralScanner.java` — an injectable
  `IntSupplier columnRateCap` seam defaulting to
  `() -> LSSClientConfig.CONFIG.lodColumnsPerSecondLimit` (the existing
  `adaptiveCadenceEnabled` pattern, so tests don't mutate the global CONFIG); the two
  mechanism edits (§3a in the budget computation, §3b in `fastRescanDue`); a
  session-scoped `rateGated` counter beside `fastScans` counting spacing-gate refusals.
- Client diag/trace: surface the active cap and `rate_gated` in the existing client
  diagnostics line + `/lss trace` fields, so a weak-client report shows whether the knob
  is set and firing.
- `fabric/.../config/LSSConfigMenu.java` — the slider (§4).
- `assets/lss/lang/en_us.json` — three keys.
- No server, wire, Paper, or soak-schema changes. `WANT_SET_BUDGET` and
  `WantSetBudgetInvariantTest` untouched.

## 6. Clamps, floors, and documented degradation

- `validate()`: 0 stays 0; nonzero clamps to `[50, 100_000]`. Floor 50 keeps the scanner
  functional (a sub-50 cap starves the frontier to near-wedge cadence for no plausible
  use); the high ceiling is inert but harmless.
  **AMENDED 2026-08-14: floor lowered to 10** — "no plausible use below 50" was
  falsified by a real request (a deliberate ~20 col/s trickle on a constrained
  link), and the machinery is proven far lower (the transfer governor drives the
  same actuators to 1 col/s). Single-digit rates stay clamped up to 10: that is
  the genuinely near-wedge zone.
- **Below R = 776** (`SYNC_ON_LOAD_SLOT_CAP + MAX_CONCURRENT_GENERATIONS +
  WANT_SET_FRONTIER_RESERVE`) the declared want-set no longer dominates the server's
  worst-case in-flight set — the invariant the constant 800 was sized for. Consequence is
  degradation, not breakage: the frontier advances behind its own awaited positions across
  more scans. Awaited positions are ordinary want-set members and are always re-declared,
  so nothing wedges. Document in the config comment; do not "fix".
- v16 server sessions: the fast path is already excluded there, so only the budget clamp
  (§3a) has effect — declarations shrink, cadence stays 1 Hz. Fine; note in the comment.
- Interaction with the backpressure halt + edge-triggered empty clear: unchanged — the
  halt fires on its own thresholds regardless of the cap.

## 7. Tests

Tier 1 additions (`SpiralScannerTest` + `LodRequestManagerTickTest` where the arming is
production-wired):

1. **cap=0 bit-identity** — with the supplier returning 0, budget and cadence decisions
   are identical to today across the existing scenario matrix (the taper tests re-run
   green untouched is most of this pin already).
2. **Budget MIN composition** — cap below the taper result wins; taper below the cap wins;
   floor-at-1 preserved.
3. **Spacing rule** — after a full batch of N at cap R, a fast fire at
   `ceil(20N/R) − 1` ticks is refused and at `ceil(20N/R)` admitted (drive via
   `noteDeclared` + tick stepping); a small tail batch floors at
   `FAST_RESCAN_MIN_INTERVAL_TICKS`; R=3200 with N=800 admits at exactly 5 ticks (the
   continuity pin).
4. **Fallback sanctity** — at any cap, the 20-tick periodic fire happens regardless of
   `lastSentCount` (the self-heal must be un-gateable). This is the one test that guards
   the design's safety property; label it accordingly.
5. **Disarm ladder unchanged** — send-failure/converged/disconnect zeroing still disarms
   under a nonzero cap; v16 exclusion absolute.
6. **Config validation** — 0 preserved, nonzero clamped both ends, GSON round-trip,
   absent-key default 0.
7. `rate_gated` counts refusals and resets with the reset family.

No Tier 2/3 or soak changes: default-off, client-local. (Optional follow-up if we ever
soak it: a scenario config forcing a low cap and asserting the received-rate ceiling —
not part of this change.)

## 8. Release notes item (draft)

```
### Configuration

- **Max LOD Download Rate slider (client)** — New client option (also in the Sodium
  settings screen) capping how many LOD columns per second the server streams to you.
  Lower it if receiving LODs stutters your game or saturates your connection. Default
  unlimited; automatic backpressure is unchanged and still applies on top.
```

## 9. Out of scope, recorded so it isn't re-derived

- **No server-side clamp.** A client asking for *less* is strictly less server load;
  there is nothing to negotiate (contrast DHS, whose limit is a server-granted credit and
  needs the `RemotePlayerConfigMessage` round-trip).
- **No bytes/sec denomination.** Columns vary ~5–50 KB; a byte cap would need the client
  to predict serve sizes it can't know pre-decode. Columns/sec is the unit the scanner
  actually controls, and the unit the #71 work established as the decode-cost proxy.
- **Not a replacement for fixing taper gaps.** If a live report shows this knob rescuing
  a client the taper should have caught, file the missing signal against #71's mechanism.
