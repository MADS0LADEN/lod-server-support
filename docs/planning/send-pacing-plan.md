# Server-side send pacing — spread the burst, never govern the rate — plan

**Status: IMPLEMENTED (v3 — the 3-Opus implementation round's clamp)** (2026-08-13, the
adaptive-transfer-rate program's shelved follow-up; companion to
`adaptive-transfer-rate-plan.md`). User direction: spreading the client's
requested work over time a bit is good enough to stop spikes from totally
blocking vanilla messages — it does not need to be perfect, it must NOT
artificially rate-limit send throughput (rate ownership is the CLIENT's, via
want-set sizing), and it needs only a very rough target. All options
considered below, including the user's own alternative (pace on the current
send queue length + send rate instead of inferring from want-set size) —
which, refined, is the recommendation. **v2 (both review verdicts folded):
v1's evidence-arming was deleted wholesale — its pending-bytes term was
arithmetically dead (the netty gauge caps at the 64 KiB high-water while
writable), its netty-only remainder missed the first tick of exactly the
store-warm rejoin/teleport waves the plan opens with, and successful pacing
destroyed its own evidence (the disarm-dump sawtooth). The replacement floors
the drain at the operator's own per-tick refill share, which achieves v1's
goals without any arming state at all — see §3 and the review log.**

## 1. The role this fills (the four-mechanism synthesis)

After the adaptive-transfer-rate round, slow-link protection has three owners
and one hole:

- The **client transfer governor** owns the RATE on congested slow links —
  but it engages on ping excess, which needs a 2 s interval plus the 30 s
  tab-latency refresh. A one-shot spike on a HEALTHY link never engages it
  (correctly — nothing is congested until the spike itself).
- The **yield gate** owns sustained backpressure — but it is edge-late by
  one tick: it can only react AFTER a write made the channel unwritable.
  Whatever the first tick dumped is already in netty/kernel/middle-box
  queues, head-of-line ahead of vanilla.
- The **ping backstop** owns the severe class, coarsely, at a 5 s cadence.
- **The hole**: the per-player bandwidth bucket banks up to allocation/4
  (~6.25 MB raw at the default 25 MB/s cap), and a resolution wave (cold
  join, warm rejoin, teleport) ships the whole bank in one or two ticks.
  On a modest healthy link (say 10 Mbps — never slow enough to engage the
  governor before the spike, fast enough that yield rarely arms) that is
  ~5 s of link time dumped ahead of vanilla's next keepalive/chunk packet:
  the "actions freeze for seconds after joining" class.

Send pacing fills exactly that hole: **bound the single-tick dump amplitude
so the yield gate takes over with a bounded queue in flight**. It is a
smoother, not a governor — at every equilibrium its drain rate equals the
arrival rate, so throughput is never capped; only the SHAPE of a spike
changes (one cliff → a short slope).

Non-goals, explicitly: no rate target derived from any estimator, no
throughput ceiling, no interaction with the want-set protocol, no wire
change, no per-client tuning.

## 2. Options considered

**A. Want-set-inferred rate (the originally shelved idea).** Observe each
player's declared batch sizes and inter-batch cadence; smooth sends to
roughly match (batch bytes over the observed declaration interval).
- For: aligns the spread window with the client's actual consumption
  cadence; zero wire change (inference only).
- Against: a want-set declaration measures DEMAND, not deliverable work —
  batches are REPLACEMENTS (a re-declaration of 800 unanswered positions is
  not 800 columns of imminent traffic), churn phases re-declare the same
  positions at 1-4 Hz, and the cadence itself is adaptive and
  pressure-dependent, so the inferred rate needs an estimator with guards
  for every phase — and this program's graveyard is full of estimators
  (three falsified in one day). It also needs per-dialect handling (the v16
  shim's synthetic 1 Hz declarer, the legacy drip-feed). Everything the
  inference would tell us, the send queue already knows better: the queue
  IS the ground truth of "work about to ship".
- Verdict: REJECTED — strictly dominated by B once the drain horizon is
  chosen to align with the client cadence floor (§4).

**B. Queue-proportional drain (the user's alternative, refined).** Per tick,
the column flush ships at most `max(FLOOR, queuedWireBytes / HORIZON)` —
drain whatever is queued over ~HORIZON ticks, with a floor so small queues
ship immediately.
- For: no estimator, no inference, no wire coupling, works for every client
  old or new. Self-scaling: a spike spreads, a trickle passes untouched.
  Never caps throughput — at equilibrium drain = arrival by construction
  (the queue settles where `Q/HORIZON = arrivalRate`). The machinery is a
  five-line in-loop budget in `flushSendQueue`, a shape we already had
  (the deleted AUTO in-loop budget) and know composes with the yield gate,
  the presence gate, and the limiter.
- Against (and the v2 fix): a NAIVE always-on version with a small static
  floor adds ~HORIZON ticks of delivery latency to every sustained flow,
  which would slow the 4 Hz warm-backfill loop — an artificial throughput
  reduction at the system level, violating the brief. v1 fixed this with
  evidence-arming (pace only after channel pressure), which the review round
  falsified three ways (dead pending term, post-dump evidence, the
  disarm-dump sawtooth — see §7). v2's fix: FLOOR THE DRAIN AT THE REFILL
  SHARE (allocation/20) — the budget can never pace below the operator's
  configured rate, so throughput neutrality is structural and no arming is
  needed at all.
- Verdict: **CHOSEN**, refill-floored (§3).

**G. Bank clamp (shrink `PlayerBandwidthTracker`'s burst divisor / cap the
bank — the review round's n10).** Bounds every dump including the first with
zero new machinery. Absorbed INTO the chosen design rather than adopted raw:
the refill-share floor IS the bank bound, expressed inside the pacer where it
composes with Q/HORIZON (backlogs may still use the bank as a slope), the
kill switch, and the diag receipt — instead of silently changing the
limiter's constants for every consumer.

**C. Send-rate smoothing (per-tick budget = k × recent send-rate EWMA).**
The user's "pace based on send rate" reading. Rejected: self-referential
cold-start (idle → average 0 → floor-only ramp) makes resumption after any
gap an artificial rate limit — exactly the forbidden failure; and it is
another rate estimator on the path where estimators keep dying.

**D. Deadline spreading (stamp each enqueued payload send-not-before,
spread across the expected declaration interval).** Rejected: needs the
batch boundary/interval (drags option A's inference back in), complicates
the priority queue, and buys nothing over B's memoryless per-tick division.

**E. Constant payloads-per-tick cap.** Rejected outright: a fixed k×20/s
column ceiling IS an artificial rate limit.

**F. In-loop writability checks (let netty's writability flag stop the
send loop mid-tick).** Tempting — the kernel becomes the pacer — but the
load-bearing objection stands on its own arithmetic: vanilla sets no water
marks, so netty's defaults (32/64 KiB) apply, and an intra-tick writability
check caps per-tick writes near the 64 KiB watermark — an artificial
~1.3 MB/s ceiling on links that could take megabytes (netty's async flush
makes the flip routine mid-burst even on fast links). The AUTO-ceiling
falsification record's gauge-SEMANTICS half (rounds 1-2) applies as a
supporting caution, no further. Rejected; the ENTRY-check yield gate stays
the only writability consumer.

## 3. The chosen design: refill-floored proportional drain (always on)

All in `AbstractPlayerRequestState.flushSendQueue` (common — both platforms),
column-payload lane only (BatchResponse/far-player lanes are tiny and
latency-sensitive; they already ride separate paths). No arming, no evidence,
no probe input — the budget is a pure function of the send queue and the
allocation the flush already receives:

- **The budget (v3):** every tick with a non-empty queue,
  `paceBudget = clamp(queuedRawBytes / PACE_HORIZON_TICKS, share, PACE_MAX_BURST_SHARES × share)`
  where `share = allocationBytes / TICKS_PER_SECOND` — checked in-loop before
  each send EXCEPT the first (the one-payload presence gate: a legal
  oversized column ships whole — so a tick's true write is budget + one
  crossing payload — and at the degenerate budget of 0 the gate keeps one
  payload per tick flowing). Leftover stays queued (ordinary retention;
  nothing dropped, nothing bounced). `PACE_HORIZON_TICKS = 10`,
  `PACE_MAX_BURST_SHARES = 2` — the impl round's clamp, resolving a genuine
  tension: the integration lens showed the unbounded Q/10 term MEETS THE
  BANK on real-terrain waves (Q ≥ 2.5×allocation — an 800-column terrain
  wave is ~52 MB raw) and unbounds the very first tick, while the
  correctness lens proved the same term is LOAD-BEARING below 20 TPS (the
  floor is tick-denominated, the limiter wall-clock-denominated — on a slow
  server the queue term is what restores cap-rate delivery). Two shares
  covers full rate down to ~10 TPS; below that pacing under-delivers on an
  already-degraded server (queue retained by the router, never dropped).
- **Why the refill-share floor is the whole trick** (the v2 insight, from the
  review round's triangle): `allocation/20` is the per-tick share of the
  operator's CONFIGURED cap — the declared intent for sustained rate. A
  budget floored there **cannot pace any flow below the cap rate, ever** —
  sustained-throughput neutrality is structural, not equilibrium math. What
  it removes is exactly and only the BANK dump: the bandwidth bucket banks
  allocation/4 (five refill ticks), so today an idle gap plus a resolution
  wave ships ~5 s of link time in one tick; floored pacing ships the same
  wave at one refill share per tick — and since the bank IS five refill
  shares, **no bank-sized wave is ever stretched beyond ~5 ticks**, which is
  the client's own fast-fire floor (§4). The `Q/HORIZON` term lets genuinely
  oversized backlogs (Q > allocation/2) drain ABOVE the refill share,
  exponentially decaying toward it — the bank still serves catch-up, just as
  a slope instead of a cliff.
- **First-tick coverage** (v1's fatal corner, closed; v3 numbers): the
  budget needs no evidence, so every wave including the first is bounded —
  at ONE refill share (~1.31 MB raw at the default cap) for bank-sized
  waves (the flagship store-warm case at soak-like column sizes), and at
  TWO shares for deep real-terrain backlogs (the clamp ceiling). The kernel
  send buffer below the gauge (~0.5-0.7 MB measured on the rig path) still
  absorbs its own depth at memory speed — the honest bound is **~0.5-1.3 s
  at 10 Mbps for bank-sized waves, ~2× that for clamp-ceiling deep
  backlogs** (kernel depth + up to two shares wire before yield engages;
  the kernel term is the irreducible floor of ANY server-side mechanism in
  this family — the companion program's structural finding); versus the old
  ~3-5 s dump, a 2.5-4× improvement across the range.
- **Denomination: RAW bytes** (`QueuedPayload.estimatedBytes`), matching the
  limiter and the allocation the floor derives from. Wire ≤ raw, so the
  socket-facing amplitude is bounded a fortiori; no raw/wire mixing anywhere
  in the formula. `queuedRawBytes` is a lazy per-tick sum over the send queue
  (≤ `sendQueueLimitPerPlayer` entries, main-thread, only when non-empty —
  the yield byte-integral precedent; zero drift risk, no conservation test
  needed — the review round preferred this over a running counter).
- **Composition order** (top of flush unchanged): fixed ceiling entry gate →
  yield gate (unwritable ticks book `yielded=`, the budget never evaluates) →
  pace budget bounds the writable tick's send loop → bandwidth limiter
  charges per payload as today. The pingf-cut allocation composes
  automatically (v3): BOTH clamp bounds derive from the CUT allocation, so a
  backstop cut paces harder in the same direction (the budget itself scales
  down — the v2 "limiter stays the authority" scenario dissolved with the
  clamp, since the budget can no longer exceed 2 cut-shares while the bank
  is 5); the limiter still charges per payload, and `paced=` honestly books
  only budget-stopped ticks. The
  starvation-floor tick is structurally exempt (it breaks after exactly one
  send, and the budget skips the first payload) — an explicit guard
  documents it.
- **Observability:** per-player `paced=` counter (ticks where the pace budget
  stopped a PARTIAL flush — a mechanism counter beside `deferred=`/`yielded=`
  at line end, never a loss signal; one more `PlayerDiag` field + compat ctor
  + golden re-pins) AND a service-scoped `service.paced_ticks` twin exported
  by both platforms' soak exporters (impl round: inertness is empirical, so a
  moved guard-soak baseline needs in-recording attribution; the yield
  counters' survive-teardown rule).
- **Config:** `enableSendPacing` (server, default true) — a `/lsslod set`
  boolean row (the `enablePingBackstop` precedent; the rig A/B lever), filed
  in check_soak.py's `SERVER_CONFIG_BOOL_KEYS` same-commit (done — the
  review round also found `enablePingBackstop` MISFILED in the int set and
  fixed it). `PACE_HORIZON_TICKS` stays a constant.

## 4. Interaction with the fast want-set cadence (worked through, v2)

- **The bank/floor alignment is the load-bearing identity**: bank =
  allocation/4 = 5 × refill share, and the client's fast re-scan floor is
  5 ticks. Any wave the bank could have dumped in one tick now ships in ≤ 5
  ticks — at or inside the client's own minimum re-scan period, so the
  ≥95%-answered fast trigger fires on the same schedule it would have. The
  cadence-loss concern that killed the naive always-on drain does not apply
  to the floored version, on ANY link speed: the pacer never delivers slower
  than the cap, and the cap was already the limiter's law.
- **Sub-cap links** (the honest scope the v1 draft overclaimed): a link
  slower than the cap (e.g. 100 Mbps against the 25 MB/s default) queues at
  the SOCKET regardless of pacing — delivery there is the link's honest
  cost, the yield gate holds what netty can't take, and pacing's refill-share
  ticks simply stop LSS from deepening the dump beyond one share per tick.
- **Governed sessions (4 Hz quarter-batches):** a governed burst is
  ~desired/4 ≈ 100-250 KB — under one refill share, shipped tick-1: inert.
  No double-throttling; and the m7 trace from the review round closes safely:
  if pacing ever slowed a governed session's delivery, the governor's
  offer-backing reads the under-offer and FREEZES (never ratchets), and the
  refill floor clears any governed quarter-batch within 1-3 ticks so the
  cadence recovers immediately. The coupling invariant, pinned as a STATIC
  inequality over compiled defaults (delta round — the floor's allocation is
  a runtime variable, so a runtime phrasing is unpinnable):
  `(defaultPerPlayerCapBytes/TICKS_PER_SECOND) × FAST_RESCAN_MIN_INTERVAL_TICKS
  ≥ ENGAGE_BELOW_BYTES_PER_SEC/4` (~6× headroom at defaults). Documented
  NON-guarantee: under a deep pingf cut or heavy global dilution the share
  shrinks below a quarter-batch and the cadence slips — the degrade path is
  the governor's offer-backing FREEZE (never a ratchet), healed by B's
  recovery.
- **The 1 Hz fallback** is untouched by construction (it is time-based, not
  delivery-based) — the want-set's self-heal never waits on the pacer.
- **v16/legacy sessions:** no interaction — pacing is below the dialect
  layer entirely (it shapes the send queue, whatever filled it).

## 5. Test plan

- T1 (common flush suite, the TransportYieldFlushTest pattern): the budget
  truth table (refill floor binds when Q < HORIZON × share; Q/HORIZON binds
  above; empty queue = no evaluation), the presence gate (oversized payload
  ships whole), leftover retained not dropped, the starvation-floor-tick
  exemption guard, `paced=` counts budget-stopped PARTIAL ticks only,
  MIN-composition with the limiter (whichever binds first stops the loop —
  incl. a pingf-cut allocation shrinking the floor, AND the Q/HORIZON-above-
  the-cut-share case where the limiter stays the binding authority), RAW
  denomination pin,
  kill switch OFF = bit-identical flush, short overloads pin pacing off
  (S-9a), the m7 constants-coupling pin (a governed quarter-batch at the
  engage boundary clears within the fast-fire floor at the floored budget).
- Contract pins (the ChannelAccessorContractTest pattern): both platforms
  pass the config gate into the flush; the diag builder reads the live
  `paced=` counter; `PlayerDiag` golden re-pins (full line + a value-branch
  pin); config default-ON pins in both suites; the registry row (containsAll
  + apply test); the move-tracer boot-row echo (`enableSendPacing` joins
  `enablePingBackstop` — same partition-the-collections rationale).
- Guard soaks: fresh-backfill + disk-saturation + rate-limit-storm +
  **store-second-join** (delta round: the store-warm wave is the bank-burst
  shape where loopback pacing actually BINDS) + **bandwidth-throttle** (impl
  round, both lenses independently: the smallest-allocation scenario — the
  one where the FLOOR term binds and whose queue_full/delivery premises are
  the ones per-tick shaping could move). **RUN 2026-08-13, ALL FIVE PASS
  (0 violations, 0 warnings) with in-recording attribution via
  `service.paced_ticks`: bandwidth-throttle BOUND (paced_ticks=3 — the
  floor term fired on the tiny allocation exactly as predicted, and no law
  moved); the other four inert (=0 — store-second-join's superflat columns
  are too small to outrun a default refill share, so the store-warm BINDING
  coverage on loopback comes from bandwidth-throttle plus the deterministic
  T1 five-tick wave pin; real-terrain binding is the rig live gate's job).**
  Inertness is NOT structural in v2 (the budget binds exactly where the bank
  used to burst — wave-completion ticks stretch from 1 to ≤5 ticks): the
  expectation is UNCHANGED verdicts because every law is conservation- or
  quiescence-based at 5 s scale and the churn ceilings carry 2× headroom —
  but a moved baseline is a FINDING to diagnose, not a re-baseline.
- Live gate note (2026-08-13): on the combined build's 4 Mbps session the
  governor held the rate and the pacer never fired (`paced=0` — correct:
  the client-owned rate leaves no burst to shape). The pacer's OWN live
  case (governor off, medium link, store-warm join) remains open below.
- Live gate (the rig, proxy at a MEDIUM rate ~4-10 Mbps): client governor
  kill-switched off to isolate the pacer; the SPECIFIC measurement is the
  store-warm rejoin first seconds (v1's uncovered corner): expect the join
  freeze bounded at ~the kernel-buffer floor (~0.5-1 s) instead of ~5 s,
  `paced=` climbing through the wave, ping recovering within a few seconds;
  then governor back on to confirm no double-throttle (`paced=` ~flat while
  governed). A/B via `/lsslod set enableSendPacing`.

## 6. Open questions (decide at implementation)

- Whether `paced=` earns a soak-exporter field (schema + allowlist) or stays
  diag-only. Lean: diag-only until a scenario needs it.
- Whether the Q/HORIZON term should also bound how much of the BANK a
  backlog may consume per tick beyond the floor (today: limiter still allows
  bank+refill; the budget max() lets Q/10 exceed the floor for huge queues).
  Lean: keep — it is the "bank as slope" half of the design.

## 7. Review log

**2-Fable plan review (2026-08-13), both IMPLEMENT WITH FIXES — folded as v2:**

*Control lens*: M1 v1's "every subsequent spike is paced" was false — arming
evidence decays across exactly the minutes-long calm that separates the spike
events, netty evidence is structurally post-dump (probe read precedes writes),
and the store-warm rejoin/teleport wave (the growing flagship case) dumped its
whole bank on tick 1 unpaced; kernel-absorbed waves (< socket buffer) never
generate evidence at all. M2 the 256 KB pending arming term was DEAD CODE —
`OutboundBufferMath.pendingBytes` is arithmetically capped at the 64 KiB
high-water while writable; values above it are representable only after
NOT_WRITABLE already fired. M3 successful pacing destroyed its own evidence
(disarm-with-nonempty-queue → ~0.2 Hz dump/re-arm sawtooth on mid-band
links). ALL THREE resolved by v2's arming deletion: the refill-share floor
needs no evidence, covers every tick of every wave, and cannot flap. m4
honest numbers folded (the ~0.5-1 s kernel floor at 10 Mbps is irreducible
by any mechanism in this family). m5/m6 overclaims re-scoped (§4). m7 the
governor-starvation trace closes safely via offer-backing; the constants-
coupling invariant is now a planned pin. n10's bank-clamp alternative is
absorbed INTO v2 (the refill floor IS the bank bound, expressed inside the
pacer where it composes with Q/HORIZON and the kill switch instead of
touching the limiter's constants). n11 option F's rejection re-grounded on
the watermark-ceiling argument.

*Integration lens*: MAJOR-1 same dead-term finding (independent arithmetic —
convergent). MAJOR-2 `enablePingBackstop` found MISFILED in check_soak.py's
INT key set (a bool pin would have failed --validate) — fixed same-branch,
with `enableSendPacing` filed correctly beside it. MINOR-1 the UNKNOWN-
writability arming rule became moot in v2 (the pacer consumes no probe
input at all). MINOR-2 the full config/diag surface enumerated (registry
containsAll + apply test, the fourth PlayerDiag compat ctor + golden lines,
the boot-row echo with the check_move_trace REQUIRED-KEYS exclusion noted).
MINOR-3 the lazy per-tick sum chosen over the running counter (zero drift
risk; cost negligible). MINOR-4 moot in v2 (no arming state to place across
the early returns); the surgery-placement, counter-mutation, composition,
and column-lane-only claims all verified TRUE against the live tree.

**Delta round (control lens on v2) — IMPLEMENT WITH FIXES, folded:** the
refill-share floor confirmed scale-invariant against the bank at every
runtime allocation; M1/M2/M3 closed by construction; loopback law sweep
found nothing that moves (pacing retains, never drops; slower delivery only
LOWERS supersession churn — the safe side of every ceiling). Fixes: the
guard soaks gain store-second-join (the one loopback surface where pacing
binds), the m7 pin reformulated as a static defaults inequality with the
pingf-cut degrade documented, the Q/10-during-cut limiter-bound property
made explicit (+T1 case), the first-tick bound honesty-widened to
~0.5-1.3 s, §2.F re-grounded on the watermark arithmetic.


**3-Opus implementation review (2026-08-13), all MERGE WITH FIXES — folded
as v3:**

*Integration lens*: MAJOR — the unbounded `max(share, Q/10)` let deep
real-terrain backlogs (Q ≥ 2.5×allocation; 800 columns × 64 KB ≈ 52 MB) push
the budget to the bank, unbounding the first tick the mechanism exists to
bound; every headline claim carried the unstated `Q < 2.5×alloc` condition.
MINORs: no soak-visible pacing signal (the guard protocol needs
attribution), per-player-only counter, the every-tick O(queue) walk's
overstated precedent, floor-tick double walk, the budget+one-payload
overshoot, three promised pins missing, guard soaks unrun.

*Correctness lens*: production verified correct point-by-point; the 6.25 MB
spike trace reproduces the 5-tick slope EXACTLY (paced=4, limiter never
binds). MINOR — the floor is TICK-denominated while the limiter is
wall-clock-denominated: below 20 TPS the floor under-delivers and the Q/10
term is what restores cap rate (load-bearing, so it must not be deleted).
The m7 pin's units verified coherent but drift-unsafe (the hardcoded /4
would RELAX if the fast-fire floor widened).

*Test-adequacy lens*: MAJOR — the RAW-denomination pin was VACUOUS: the
test helper hit the 4-arg QueuedPayload compat ctor (third arg bound to
submissionOrder, wire silently = raw), so the one mutant the pin exists to
catch stayed green. MINORs: the m7 units rewrite, two wall-clock
token-boundary expectations (Tier-1 flake bait — no CI retry), the
presence-gate and floor-guard pins vacuous at any budget > 0, no multi-tick
slope pin.

**The v3 resolution**: `clamp(Q/HORIZON, share, 2×share)` — the ceiling
bounds deep-backlog first ticks at 2 shares while preserving the low-TPS
compensation to ~10 TPS (the two lenses' tension resolved); the suite
rewritten on the state's injected nano clock (deterministic, no sleeps)
with the canonical-ctor fix, the deep-backlog clamp pin, the 5-tick wave
pin, the budget-0 presence pin, the boot-echo + 5-arg pins, and the
units-carried m7 pin; `service.paced_ticks` exported by both platforms
(contract file updated); honest bounds written into §3.
