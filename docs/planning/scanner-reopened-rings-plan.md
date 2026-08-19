# Plan: reopened-ring scanning — the client scan walk stops costing O(disc) on every reset

**Status:** IMPLEMENTED 2026-08-18 (fix/scanner-reopened-rings; plan v1.1 — 2-Fable
reviewed, both SOUND-WITH-CHANGES, all findings folded) · client-side only, no wire change · 26.2/main first, backport
after live confirmation · companion to `gen-frontier-acquisition-anchor-plan.md`
(the server-side twin of the same design flaw: "a reset costs O(disc)")

**Review decisions (v1.1):** the crescent band's lower edge is Euclidean-derived
(MAJOR-1×2, convergent); the fast-gate prediction uses the reopened lower bound +
fail-closed truncation (MAJOR-2×2); `fastRescanDue` keeps its actionable-retries
hold rung verbatim (MAJOR-3 option a — zero cadence-pin churn); the pin/doc-debt
audit is §9 (MAJOR-4); a client kill switch IS adopted (`enableScanPrefixRetention`,
default true — both reviewers argued for it: the failure class is silent orphaning
no conservation law can see, and the field A/B wants a no-jar-swap lever).

## 1. Problem

The client's 1 Hz scan walk runs synchronously on the render thread
(`ClientNetGlue.onEndClientTick` → `LodRequestManager.tick` → `SpiralScanner.scan`).
The walk skips the contiguous confirmed-ring prefix, so it is cheap in steady state —
but four events collapse the prefix to 0, and the next walk then iterates the ENTIRE
disc (one `ColumnStateMap.classify` hash probe per position; ~1.05M at distance 512)
in a single tick:

1. `recenter()` — every chunk-boundary crossing (`LodRequestManager:449`). At sprint
   speed that is every ~2.5-3 s.
2. `resetConfirmedRing()` — a dirty mark below the prefix
   (`LodRequestManager:702/714`).
3. `hasActionableRetries` — while any actionable retry mark exists, EVERY scan
   restarts from ring 0 (`SpiralScanner.scan` head).
4. Exclusion-radius shrink (once per change — fine, stays).

Measured (spark profile `6HZTTXT5pn`, 18 s, moving on a ~300k-column received disc):
**552 ms in the scan path on the render thread**, with `LongOpenHashSet.contains` the
#2 self-time frame in the whole profile (392 ms) — the classify probes of full-disc
walks. One walk ≈ 30-90 ms ≈ 6-18 dropped frames at 200 fps, at chunk-crossing
cadence while moving and dirty-broadcast cadence (10 s) while parked. This is the
"few dropped frames every 2-3 s" stutter, reproduced locally, and it explains every
detail of the field report (issue-adjacent, 7thWardLord): onset once the disc fills,
fixed interval, `lodDistanceChunks` reduction helping, either-side LSS disable
curing it, small test servers never reproducing it.

## 2. Design: reopened rings instead of prefix collapse

Replace "reset the prefix to 0" with "reopen exactly the rings that need
re-walking", tracked in a fixed **ring bitset** (`long[33]` — rings 0..2048 inclusive;
`MAX_LOD_DISTANCE` = 2048 is itself a walkable ring; no allocation, ascending
iteration by bit scan).

The walk's coverage becomes: **reopened rings below the prefix, plus the normal
frontier walk from `confirmedRing` outward**. The prefix invariant generalizes from
"every ring < confirmedRing is satisfied-or-excluded" to "… except rings whose bit
is set, which the walk still visits every scan until they re-confirm".

Per reset event:

- **Chunk crossing** (`recenter(dx, dz)`, chebyshev delta `d`, almost always 1):
  - `confirmedRing = max(0, confirmedRing - d)`. Soundness: a ring-r position around
    the NEW center is at distance ≤ r+d from the OLD center, so rings
    `< confirmedRing - d` were all walked-satisfied or excluded under the old center.
  - Reopen the **trailing-crescent band**: rings
    `[max(0, floor((R - d) / sqrt(2)) - 1), R + d]` where `R` =
    `exclusionRadiusAtLastScan` (skip entirely while it is -1 — before the first
    walk nothing was ever confirmed). LOWER EDGE IS EUCLIDEAN-DERIVED (both
    reviewers' convergent MAJOR): `isVanillaRendered` is a buffered EUCLIDEAN disc,
    so positions exiting it on a crossing span Chebyshev rings from ~R/√2 up to
    R+d — the naive `[R-d-1, R+d]` band misses the entire diagonal-trailing
    portion (verified counter-example at R=16: an exit at ring 13 below a [14,17]
    band = permanent hole; divergence begins at R≈14 and grows ~0.29R). The wide
    band is ~0.3R rings × 8r ≈ 2.5k classify calls per crossing at R=32 — still
    noise against the 1M eliminated. These contain every position that just left
    vanilla's view circle and needs first-time LOD service; the oracle test must
    sweep viewDistance ≥ 16 with axis AND diagonal multi-crossing sequences, since
    small discs structurally cannot expose the geometry.
  - Shift existing reopened bits by the movement: a reopened ring r maps to rings
    `[r-d, r+d]` around the new center — set that range (d=1 in practice, so each
    bit becomes 3). Overflow safety below.
- **Dirty mark below the prefix** (`LodRequestManager:702/714` — the position is in
  hand): reopen `chebyshev(pos, center)`'s ring only. A dirty batch of N positions
  reopens ≤ N rings, typically 1-3.
- **Actionable retry marks**: `ColumnStateMap.hasActionableRetries` (which already
  iterates the `retry` set with the vanilla-view exclusion test each scan) changes
  from returning a boolean to reporting the actionable marks' RINGS (callback or
  small out-set); the scanner reopens those rings instead of zeroing. Parked
  (excluded) marks behave exactly as today: not actionable, nothing reopened; when
  the exclusion moves off them, the per-scan recomputation reports them and their
  ring reopens. The F1-family "parked mark must not force per-scan full walks" pin
  is not just preserved — the residual full-walk it still allowed (an actionable
  mark = ring 0 restart every scan until consumed) is fixed too.
- **Unchanged full resets**: session `reset()`, dimension `resetScanCounter()`, and
  the exclusion-radius shrink keep prefix-to-0 semantics (rare, correct).

**Walk changes** (`SpiralScanner.scan`): iterate `r` ascending over
`reopenedBits ∩ [0, confirmedRing)` then `[confirmedRing, lodDistance]`. A reopened
ring that walks fully-satisfied clears its bit; one with unsatisfied positions keeps
it (it stays covered every scan — this preserves "re-declaration is the single
self-healing mechanism" for crescent/dirty positions whose answers get dropped:
an awaited position blocks its ring's re-confirmation exactly as it blocks prefix
advance today). Frontier rings keep the contiguous-prefix advance rule verbatim.

**Overflow valve**: if the bitset would exceed **64 set bits**, clear it and set
`confirmedRing = 0` — today's behavior, as the conservative fallback. Pathological
only (a dirty storm touching >64 distinct rings).

## 3. The elytra regime is preserved — via the reopened lower bound, in BOTH windows

`predictedWalkCost()` currently reproduces the documented flight tradeoff in two
windows: crossing→fire (the from-0 prediction after `recenter()` zeroes the prefix)
AND fire→next-crossing (the in-flight trailing-crescent ring keeps the re-derived
prefix LOW ≈ viewDistance, so predictions stay expensive between crossings too).
The review found a flag alone patches only the first window. The v1.1 mechanism
covers both:

- **Prediction lower bound**: `c_eff = min(confirmedRing, lowestSetReopenedBit)`,
  and the span term runs `[c_eff, s]`. In flight the crescent band keeps a bit near
  ring ~R/√2, so the prediction stays expensive past the cliff exactly as today's
  low re-derived prefix does. Deliberately an OVER-estimate (it prices the whole
  span, not just set bits): conservative refusal is the shipped regime. This also
  keeps stationary dirty-below-prefix at 1 Hz (today's behavior — the reset made
  predictions expensive; the low bit now does), so no cadence behavior change is
  introduced at all.
- **Fail-closed truncation**: when the last walk budget-broke at a ring BELOW
  `confirmedRing` (`truncatedBelowPrefix`), the `s = scanRing` branch is
  unsound (the next walk still iterates the frontier interval); predict with
  `s = lodDistance` instead.
- **`recenteredSinceLastFire`** (set by `recenter()`, cleared when a scan FIRES —
  fires-not-declares: a 0-count walked fire clears it, converged clients fire 1 Hz
  walks so it cannot stick): while set, the prediction is the bare from-zero
  formula, preserving the exact-equality calibration pin
  (`walkCostCalibrationAdmitsTheMeasuredFlightWalkAndRefusesTheWarmFullDisc`).
- **`fastRescanDue`'s actionable-retries hold rung stays verbatim** (it is the
  SECOND `hasActionableRetries` call site the draft missed): the boolean form
  remains at that rung (implemented over the new ring-collect internally), so
  `actionableRetryMarksHoldFastFiresLikeAnyPrefixInvalidation` and the cadence pins
  pass unmodified.

## 4. Implementation

All in `xplat` client code; no server, no wire, no config key.

- `LSSClientConfig`: **kill switch** `enableScanPrefixRetention` (default true).
  False = the reset methods keep today's prefix-to-0 semantics (delegation, not a
  parallel implementation) — the field A/B lever and the silent-orphan safety net.
  ConfigValidationTest default + round-trip pins.
- `SpiralScanner`:
  - `long[33] reopenedRings` (rings 0..2048 — `MAX_LOD_DISTANCE` = 2048 is itself a
    walkable ring; the draft's `long[32]` off-by-one is a review finding) + bit
    helpers (set/clear/count/nextSetBit, shift-by-delta), `recenteredSinceLastFire`,
    `truncatedBelowPrefix`.
  - `reopenRing` clamps to the CURRENT effective lodDistance at set time, and the
    scan head clears bits above it (the distance is dynamic — stale high bits would
    otherwise never clear and could trip the valve every scan; this also changes
    the shrunk-lodDistance retry flavor from "full walk each scan" to "no walk,
    mark unconsumable" — strictly better, DECLARED here since the old comment
    called the full-walk flavor deliberate).
  - ALL full-reset paths (`reset()`, `resetScanCounter()`, the exclusion-shrink
    reset, the d ≥ 8 teleport fallback, the overflow valve) explicitly clear the
    bitset AND `recenteredSinceLastFire`/`truncatedBelowPrefix`.
  - A budget break before/inside a reopened ring does NOT clear that ring's bit
    (only a fully-walked, fully-satisfied ring clears; applies to bits ≥
    `confirmedRing` covered by the frontier interval too).
  - `recenter()` → `recenter(int d)` (the manager computes the chebyshev crossing
    delta it already knows): prefix decrement + crescent band + bit shift.
  - `reopenRing(int r)` for the manager's dirty path and the retry-mark report.
  - `scan()`: two-interval iteration; per-ring confirm/clear as above;
    `hasActionableRetries` call site switches to the ring-reporting form.
  - `predictedWalkCost()`: movement-conservative term + reopened sum.
  - Diag: expose reopened-bit count (`/lss` client diag Scan line, e.g.
    `reopened=N`) — the live observability for this mechanism.
- `ColumnStateMap`: adds `collectActionableRetryRings(playerCx, playerCz,
  exclusionRadius, IntConsumer)` (same iteration, same exclusion test); the boolean
  `hasActionableRetries` STAYS for `fastRescanDue`'s hold rung (see §3) and is
  reimplemented over the collect. `scan()`'s call site switches to ring-reopening.
- `LodRequestManager`: crossing delta into `recenter(d)` (d ≥ 8 → full-reset
  fallback; teleports already ride the prune hysteresis at the same magnitude); the
  two `resetConfirmedRing()` dirty sites compute the mark's ring and call
  `reopenRing` (positions are in scope at both); `resetConfirmedRing()` itself is
  retired (its last caller converts); dimension/session paths untouched.
- Client diag + soak surface: the Scan diag line and the client soak snapshot gain
  `reopened=` (bit count) beside `confirmed=` — the plan's §6 R4 claim was WRONG
  (client snapshots DO carry `scan.confirmed` and check_soak's fresh-backfill check
  reads `confirmed > 24`; that check survives since confirmed only rises for the
  stationary soak client, but the export makes acceptance machine-checkable).

Estimated diff: ~150 lines production, mostly in `SpiralScanner`.

## 5. Tests

New Tier 1 (SpiralScannerTest + LodRequestManagerTickTest, using a counting
`ColumnStateMap` seam so walk cost is ASSERTABLE):

1. **The hitch pin**: converged warm disc at distance 512, one chunk crossing → the
   next walk's classify-call count is bounded (crescent band + shifted bits only;
   assert « full-disc count, e.g. < 32k), and the crescent positions are declared.
2. Prefix-transfer soundness: after crossing, no position below the new prefix is
   unsatisfied-and-unreachable (exhaustive small-disc oracle comparison against a
   from-zero walk).
3. Dirty below prefix reopens exactly that ring; ring re-confirms after the serve;
   walk cost stays bounded throughout.
4. Crescent lost-answer self-heal: declared crescent position whose answer never
   arrives keeps its ring reopened and is re-declared next scan.
5. Parked excluded retry mark: no reopened ring while parked (bounded walks — the
   F1 pin strengthened), ring reopens once the exclusion moves off it, mark
   consumable again.
6. Elytra pin: with `recenteredSinceLastFire`, `predictedWalkCost` reproduces
   today's from-zero values (the ring-127 cliff) even with a full prefix; existing
   adaptive-cadence pins run unmodified.
7. Overflow valve: >64 reopened rings → full-reset fallback, bit-identical to
   today's semantics.
8. Exclusion-shrink and dimension-change resets unchanged — and now ALSO assert
   the bitset and both flags are cleared.
9. Truncation-keeps-bit: a budget break before/inside a reopened ring leaves the
   bit set; the ring is re-walked next scan.
10. Steady-state count pin: a converged warm disc with no events walks frontier-only
    (classify-call count ≈ 0 sub-prefix visits) — bit-clearing regressions surface
    as count creep, not only via the hitch pin.
11. Flight-regime bit population: sustained crossing sequences (1/s for 60 simulated
    scans) keep the bit population well under the 64-bit valve (re-confirms drain
    it) — a silent valve trip would revert to full-disc hitches invisibly.
12. Kill switch: `enableScanPrefixRetention=false` reproduces today's reset
    semantics exactly (delegation asserted by comparing walk-start rings after each
    reset event against a control scanner).

Tier 2/3: existing gametests unchanged (their discs are small; behavior identical).
Live: repeat the spark capture on the repro rig — acceptance is the scan path
dropping from ~550 ms/18 s to noise, and no felt stutters at chunk crossings on the
warm disc.

## 9. Pin & doc-debt audit (MAJOR-4 — every test/comment that changes subject)

Tests UPDATED with dated rationale (not deleted): 
- `movementRecenterZeroesConfirmedRingKeepsCadenceAndMarks` — now asserts
  decrement + crescent band bits (cadence/marks clauses unchanged).
- `retryMarkInsideConfirmedDiscForcesRescanFromRingZero` — becomes
  "...ReopensTheMarkRing": prefix survives, the mark's ring bit is set, the mark is
  consumed on the re-walk.
- `dirtiedPositionBelowConfirmedRingIsRereachedByResetConfirmedRing` — becomes the
  reopenRing form (resetConfirmedRing is retired).
- `dirtyUnderVanillaExclusionParksUntilTheExclusionMovesOff` — the heal mechanism
  becomes the crescent band + per-scan actionable recomputation; same observable.
- `anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned` — gains MOVEMENT chaos
  (random recenter(d)/dirty/retry interleavings at viewDistance ≥ 16, the
  orphan-proof for the new design; the fixed-center form kept alongside).
Tests passing UNMODIFIED (the §3 mechanism exists for them):
`walkCostCalibration…`, `actionableRetryMarksHoldFastFires…`,
`chunkBoundaryCrossingFasterThanTheCadenceDoesNotStarveScans`, the F1
exclusion-shrink pair, CL-014's `notGeneratedPositionStaysParked…`, the
adaptive-cadence disarm-ladder pins.
Comment/doc debt: recenter()/predictedWalkCost() javadocs (the coverage-limit
block's "decoupling the prefix from recenter … throughput consequence" sentence is
amended — this plan IS that decoupling, deliberately WITHOUT the consequence),
ColumnStateMap's parked-mark comment (:495), CLAUDE.md's cadence bullet
("recenter() zeroes the confirmed prefix"), elytra doc §13 cross-reference.

## 6. Risks

- **R1 — prefix-transfer hole.** The decrement argument covers satisfied rings; the
  crescent band covers exclusion-exits. The oracle test (#2) exists to catch any
  interaction we missed (e.g., exclusion geometry vs chebyshev rings — the band is
  computed from the EUCLIDEAN exclusion's outer edge, so it is set wide by one
  ring).
- **R2 — bit-shift growth under sustained movement.** d=1 triples a bit's span per
  crossing, but reopened rings also re-confirm (clear) every scan; the 64-bit valve
  bounds the worst case at today's behavior.
- **R3 — cadence regressions.** The fast gate is deliberately unchanged
  (movement-conservative prediction); pins run unmodified.
- **R4 — diag drift.** `confirmed=` in the client diag now legitimately survives
  movement; the soak checker does not read it (client rows carry no confirmed
  field), so no harness impact.

## 7. Rollout

1. Implement + full T1/T2 on main (26.2). 2. Spark re-capture on the stutter-repro
rig (the discovery instrument is the acceptance instrument). 3. PR to main with
before/after profiles. 4. Update the repro rig's client jar (server jar unchanged —
this is client-only, but the rig rebuild ships both halves of main anyway).
5. Backport to 26.1/1.21.11/1.21.1 ONLY after live confirmation (user decision).
6. Field follow-up: point 7thWardLord at the fixed build for confirmation.

## 8. Review questions

1. The crescent band width (`±(d+1)` around the exclusion edge) is chosen
   conservatively wide; reviewers should check it against
   `isVanillaRendered`'s buffered-Euclidean geometry.
2. `recenter(d)` with d > 1 (teleport-scale crossings): the manager already prunes
   and the movement path handles teleports via `teleport-prune`; large d falls back
   to full reset above a threshold (d ≥ 8 proposed) — sanity-check the threshold.
3. Should the reopened-bit count feed the fast gate's pressure terms beyond the
   cost sum? (Plan says no — keep the gate surface minimal.)
