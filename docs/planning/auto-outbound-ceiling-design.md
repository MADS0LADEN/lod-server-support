# Auto outbound ceiling — per-player latency-bounded LOD sending — design

**Status: SUPERSEDED AND DELETED** (2026-08-13, same day — see
`adaptive-transfer-rate-plan.md`). Three consecutive live falsifications on the
4 Mbps rig (async-write phantom drain → kernel-buffer absorption → vanilla
write interleaving starving the sample ring), then the structural finding that
ends the approach rather than the estimator: bounding netty-queue DEPTH cannot
deliver low latency at all — the kernel send buffer and middle boxes sit BELOW
the gauge and stay full whenever the sender writes at link rate. Latency comes
from pacing UNDER capacity. The AUTO machinery was deleted; the operator-FIXED
ceiling, the 64 KB floor, the `set` row (0 = off again), and the `ceil=` token
survive. This document stays as the falsification record.

**Original status line:** DESIGN v2 (2026-08-13, the v0.11.0 pause's
found-feature loop; user-directed from the live 4 Mbps throttled-link session).
v1's review round (2 reviewers: control lens → REDESIGN, blast-radius lens →
IMPLEMENT WITH FIXES) is folded in below; §Review-round log records what
changed and why.

## The problem (measured live 2026-08-13, premise CORRECTED by review)

Through a 4 Mbps throttled proxy, every player action lagged ~5 s with LSS
enabled while vanilla-only was normal; `yielded=1254` proved the transport-yield
gate WAS engaging. v1 blamed a "2 MB vanilla watermark" — **that was a misread**:
the diag `obuf=478.0 KB/2.0 MB` second field is the SESSION HIGH-WATER of the
pending gauge (`DiagnosticsFormatter` renders `outboundPending/outboundHighWater`),
not the channel watermark. Vanilla sets no `WriteBufferWaterMark`, so netty's
defaults apply — **high water 64 KiB** (`OutboundBufferMath`, and the yield
plan's own v2 finding).

The REAL mechanism is the **banked-token burst oscillation** (yield plan §1.2,
which names the fix as "the v3 lever"):

1. While the yield gate holds (channel unwritable), the per-player bandwidth
   bucket BANKS tokens — up to cap/4 ≈ **6.25 MB** at the shipped 25 MB/s
   (`PlayerBandwidthTracker`, BURST_DIVISOR).
2. The tick the channel dips writable, the flush loop's ONLY in-loop bound is
   that bucket: it writes the banked megabytes in ONE tick (the observed 2 MB
   `obuf` peak was queue-content-limited, not watermark-limited).
3. Those megabytes sit ahead of every subsequent vanilla packet in the ordered
   stream: 2-6 MB at 500 KB/s = the measured 4-12 s head-of-line delay, then a
   long unwritable drain, then the next burst. The yield gate bounds GROWTH per
   burst cycle; nothing bounds the burst AMPLITUDE — that is the pathology.

`outboundBufferCeilingKB` cannot help as shipped for two reasons: its 4096 KB
minimum clamp is far above any useful bound, and it is an ENTRY-ONLY gate — the
one open tick still admits the whole banked burst past it.

## Decision (v2): per-player AUTO ceiling enforced as an IN-LOOP write budget

    computed = OUTBOUND_TARGET_LATENCY_MS (250) x drain_rate_ewma
    computed >= 2 MB  =>  AUTO DISARMED this tick (status quo; yield backstop)
    else ceiling_bytes = max(64 KB, computed)

The 2 MB constant is a DISARM THRESHOLD, not a clamp (round-2 fix): a clamped
ceiling would become a silent ~40 MB/s wire-throughput governor on healthy
fast clients whenever an operator raises the bandwidth cap past ~40 MB/s, and
the EWMA's self-inflation property cannot climb past a clamp — a larger
constant only moves the cliff. Disarming instead costs nothing on slow links
(their computed ceilings are far below 2 MB), keeps fast links at today's
behavior, and makes `ceil=off` for the fast-link case literally true. Note the
fast-vs-slow comparison is WIRE-denominated on the budget side and
RAW-denominated on the bandwidth cap side — at shipped defaults the cap binds
first a fortiori.

**Actuator — the v3 lever, per-payload**: at flush entry compute
`budget = max(0, ceiling − pending)`; inside the send loop stop as soon as
`wire_written_this_flush >= budget`, with a **one-payload presence gate** —
if `budget > 0`, at least one payload may ship even when it exceeds the budget
(a legal ~2 MB raw v18 worst-case column must never wedge behind a 64 KB
budget; the bandwidth tracker's one-payload-debt precedent). This is what makes
"the queue is refilled to the ceiling every tick" TRUE: the standing queue
converges to ≈ ceiling with no sawtooth, and the banked-token burst amplitude
is bounded at ceiling + one payload.

- Slow link (4 Mbps): EWMA → ~500 KB/s → ceiling ~125 KB → standing LOD queue
  ~250 ms; the link still runs at full rate (budget = ceiling − pending is
  replenished every tick as the queue drains).
- Fast link (gigabit): computed ceiling clamps at 2 MB; pending ~0 at each
  probe read → budget ~2 MB/tick = 40 MB/s > the 25 MB/s bandwidth cap → the
  cap binds first, the ceiling never does. Differentiation lives in the
  formula, not a per-channel cap.
- **Operator-FIXED ceilings keep today's exact entry-gate-only, no-floor
  semantics** (F2-7 preserved verbatim) — AUTO and fixed differ in gating
  mechanics BY DESIGN, and the flush must know which mode it is in (plumbed
  explicitly, not inferred; see §Plumbing).

### Why not AIMD (unchanged from v1, both reviewers endorsed)

(1) the signal disappears when the mechanism works — with the ceiling binding,
yield ~never fires, so "no yield" stops meaning "headroom"; (2) AIMD re-learns
headroom only by up-probing, and every probe on a slow link rebuilds the
multi-second queue it exists to prevent; (3) capacity is directly observable at
the flush site's existing once-per-tick probe read — measurement needs no
probing and never hurts the player to learn. AIMD is for unobservable capacity
(TCP's position, `AdaptiveReadThrottle`'s position); this is not that.

## The drain-rate estimator (v1 core + review amendments)

    drained_sample = pending_prev - pending_now       (PURE-DRAIN intervals only:
                                                       written == 0 since the last
                                                       probe — rounds 3-4)
    rate = windowed MEDIAN of the last 41 valid samples (armed at 15 — round 4)

- Fed at the existing SINGLE per-tick probe read in `flushSendQueue`
  (per-player flush-thread-confined; the diag renders cached volatiles, and the
  move tracer uses its own probe instance — no second reader can desync the
  differencing).
- **Per-flush wire accumulator**: the loop already computes `wireBytes()` per
  send; a per-flush sum is RESET AT THE PROBE READ (not flush end — four paths
  return early after the read: ceiling hold, prune-emptied, yield hold,
  send-failure).
- **Busy-period guard**: sample only when `pending_prev > 0`. **Negative
  guard**: skip `drained < 0` (other writers grew the queue between reads).
  **Signal guard**: a −1 (no-signal) read poisons BOTH the current and the
  NEXT sample (`pending_prev` unusable). Writable/unwritable branch straddles
  are legitimate samples (the unwritable branch reads absolute depth).
- Mixed traffic UNDER-counts drain (LSS counts only its own written bytes) →
  ceiling errs small → conservative — **except v18-RAW sessions**, where
  `wireBytes` is pre-deflate and vanilla's connection deflate compresses raw
  sections well (~1.5-3x), so `written` OVER-counts and the ceiling oversizes
  by that factor. Accepted and documented (no haircut factor): the 2 MB
  absolute cap bounds the damage to today's burst amplitude, and codec-1
  sessions (the fleet) are ~1:1 through deflate. On the ACTUATOR side the
  same over-count stops the loop EARLIER (budget consumed faster) —
  conservative there.
- **Windowed MEDIAN over wall-clock samples** (round 4; the round-1/2 EWMA and
  the round-3 written-inclusive sample are both RETIRED — see the review log:
  async netty writes poisoned written-inclusive samples, and the kernel socket
  buffer's burst absorption poisons a bounded MINORITY of even pure-drain
  samples with 10-30 MB/s readings that any mean-family estimator integrates
  into a permanent bias; the median ignores them). Stability: on a slow link
  hold ticks dominate and sample continuously; in converged partial-flush
  states samples pause and the ceiling FREEZES at full throughput (degradation
  re-samples via the holds it creates; improvement recovers via the
  fast-streak ring clear). A multi-second hitch contributes one low sample —
  one ring slot.
- **Optimistic start**: no valid sample → no AUTO ceiling (today's behavior).
  The cap-paced-below-link-rate session never samples (pending always 0) —
  and has no queue, hence no latency to bound: benign by construction.
- Send-failure ticks may half-enter a payload into netty — excluded outright by
  the pure-drain gate (the tick wrote).

## Config semantics (`outboundBufferCeilingKB`)

| value | old meaning | new meaning |
|---|---|---|
| 0 (default) | OFF | **AUTO** |
| explicit 64..262144 | clamped 4096..262144, fixed entry-gate ceiling | fixed ceiling, exact old semantics (entry-gate, no floor), min re-clamp 4096 → **64** |
| 262144 | fixed 256 MB (inert) | the documented OFF idiom (never binds; fixed mode — the estimator does not run and its state is poisoned across the mode flip) |

- **Runtime kill switch (review MAJOR)**: `outboundBufferCeilingKB` joins the
  `/lsslod set` registry — `set outboundBufferCeilingKB 262144` is the live
  disarm; `set outboundBufferCeilingKB 0` returns to AUTO. (Negatives keep
  normalizing to 0=AUTO; no −1 sentinel — one disarm, the set row.)
- Explicit-0-to-disarm files cannot be distinguished from default files
  (fresh files write the key; not `@HiddenFromFile`) — accepted BECAUSE the
  set row exists as the first-class disarm.
- Machine-written 4096 from a pre-upgrade `/lsslod set` round-trip persists as
  an inert fixed ceiling — one release-note line ("set 0 or delete the key for
  AUTO") covers it.

## The AUTO floor (liveness; own counter — review MAJOR)

AUTO ceiling holds get their OWN consecutive-held counter (they cannot ride
`yieldNoSendTicks`: the two governors hold in different channel states and a
shared counter admits both spurious resets and shadowed holds). At 100
consecutive AUTO-held ticks, exactly ONE payload ships (allowed into an
unwritable channel — the yield floor's precedent). Reset rules (round-2 fix —
the v2 blanket send-success-only rule over-corrected): BOTH floor counters
reset (a) where a payload actually LEAVES (send-success), and (b) when the
send queue is EMPTY at flush entry (nothing withheld = trivially not
starving; without this, sparse single-tick holds on a healthy link accumulate
across idle gaps and fire a spurious floor send). A refused-send /
zero-allocation tick resets NOTHING — that interleave was the round-1
unbounded-starvation finding. Worst-case silence under two independent
counters is ~199 ticks (~10 s) when alternating governor stretches each stay
under 100 — bounded, accepted, stated here because v1's shared-counter shape
bounded it at 100. Fixed ceilings keep no-floor (F2-7).

## Plumbing

- The flush signature carries the AUTO state explicitly (mode + derived
  ceiling), not inferred from config: the service call sites
  (`RequestProcessingService` / Paper twin) currently pass
  `config.outboundBufferCeilingKB * 1024L`; under AUTO they pass the
  per-player derived value + mode. `ChannelAccessorContractTest`'s source-regex
  pin on the exact old text is SUPERSEDED by a successor pin on the new wiring
  (both platforms, same intent: a revert-to-constant must not ship green).
- Estimator + ceiling state live on the player state (flush-thread-confined
  working state; the rendered `ceil=` value published VOLATILE — Paper reads
  diag gauges off-pump). Dies with the state on dimension change → the EWMA
  cold-starts and a slow link eats one today-shaped burst per dimension trip
  (documented; network-identity carry rejected as overkill).

## What does NOT change

Yield gate (backstop, unchanged incl. its floor and counters); bandwidth caps
(the rate guard — note the in-loop budget composes with, never replaces, the
limiter); issue #71 ingest backpressure (slow decoders); wire; store; router;
want-set. `deferred=` counts only WHOLE-TICK holds
(budget == 0 at entry), never budget-stopped partial flushes — today's
"withheld work" meaning (round-2 nit). Its MEANING still flips from "operator
red flag" to "the mechanism working" on slow links; every doc that calls
nonzero `deferred=` a red flag is updated, and `ceil=` disambiguates (value ⇒
AUTO; `off` + deferred>0 ⇒ operator-fixed ceiling).

**Ordering (implementation decision, logged):** the AUTO budget evaluates
AFTER the yield gate — an unwritable tick books `yielded=` (yield semantics
and every existing yield pin unchanged), and AUTO binds only on WRITABLE
ticks, which is exactly the burst window the v3 lever exists to bound. The
operator-FIXED ceiling keeps its pinned ceiling-FIRST order (F2-7 exact
semantics). Attribution stays clean: yielded = the channel said stop,
deferred = LSS said stop.

## Known limitations

- **Proxy blind spot** (inherited from yield): behind Velocity/Bungee the
  channel drains at LAN speed → estimator sees a fast link → best-effort.
- **Kernel send buffer**: the bound covers netty pending only; SO_SNDBUF adds
  ≈ path-BDP of genuine in-flight depth. Accepted.
- **Oversized single payloads**: the latency bound degrades to
  `max(ceiling, payload)/drain` — a legal ~2 MB raw v18 column at 4 Mbps is
  ~4 s for that one payload (rare; the presence gate ships it whole).
- **Dimension-change retrain** (above).
- **CI-inertness (re-argued)**: the structural argument is the NO-SAMPLE path —
  loopback pending is ~always 0 at probe reads → the estimator never trains →
  optimistic start → no AUTO ceiling. Even a stray trained sample reads a huge
  loopback drain rate → ceiling at the 2 MB cap with pending ~0 → budget
  ~2 MB/tick = 40 MB/s → the bandwidth cap binds first. Both paths pinned in
  T1; soaks/gametests provably unaffected (no scenario/benchmark/gametest
  config sets the key; `deferred` is exported nowhere; verified by the
  blast-radius review).

## Observability

Per-player diag line gains `ceil=<bytes>|off` after `obuf=`: `off` = AUTO
untrained or disarmed; ANY fixed ceiling — the 262144 OFF idiom included —
renders its byte value (honest: it IS a fixed ceiling, however inert). Diag-only — never
exported (loopback makes it meaningless in soaks). The move tracer's boot-row
config echo keeps the raw key value (0 now meaning AUTO — noted, not changed).

## Registrations (same-commit; blast-radius review's sweep)

1. `LSSConstants`: MIN 4096→64 with the RATIONALE COMMENT REWRITTEN (the old
   one — "well above one legal maximum-size column" — is superseded by the
   presence gate); new `OUTBOUND_AUTO_CEILING_MAX_KB = 2048`,
   `OUTBOUND_TARGET_LATENCY_MS = 250`.
2. `PaperConfigValidationTest`'s floor assert (`MIN * 1024 > MAX_SECTIONS_SIZE`)
   INVERTS — rewritten to the new rationale + name/message updates; Fabric
   config-suite floor table comment; both suites gain 0=AUTO-default,
   min-re-clamp, OFF-idiom pins.
3. `RuntimeSettings`: the new `outboundBufferCeilingKB` row (parse/clamp/apply
   notes; re-uses validate()'s clamp helper per the R-2 rule).
4. `ChannelAccessorContractTest`: successor wiring pin, both platforms.
5. `TransportYieldFlushTest`: the F2-7 pin splits (fixed-no-floor unchanged +
   AUTO-floor + budget/presence-gate + estimator truth table + both
   CI-inertness paths + send-success-only floor resets).
6. `DiagnosticsFormatterTest` golden (`ceil=` token; Paper command goldens
   verified UNAFFECTED — they run with no players connected).
7. Docs: CLAUDE.md transport-deference bullet (four now-false claims) + the
   TransportYieldFlushTest description line; `ServerConfigBase` javadoc;
   config-defaults review §8.2 erratum; `flight-cadence-and-transport-
   backpressure-plan.md` clamp table + default-OFF decision (edit with
   back-pointer); `moved-wrongly-investigation-2026-08-06.md` M2 note;
   `check_soak.py` key comment ("0 = off" → AUTO).
8. Release notes: items in all three tag drafts + the Modrinth variant
   (Configuration: AUTO default + the set row + the "set 0 for AUTO" upgrade
   line; the notes are pause-final, so this rides the same amendment
   convention as the yield flip). v0.11.0-progress.md decisions-log pair (the
   user decision: this design, 2026-08-13). Stage-G scope: the R-7 full-tree
   delta-ports carry AUTO to the support lines unchanged.

## Test plan

T1 as §Registrations 2/4/5/6 (estimator truth table via scripted probe +
injected clock; budget arithmetic incl. presence gate; floor counters + resets;
clamp table; CI-inertness both paths). One no-op soak guard (fresh-backfill).
Live gate: the 4 Mbps throttled proxy session. **The healthy-slow-link
signature UNDER SHIPPED DEFAULTS (post-hoc review MINOR-1 — the earlier
deferred-climbing expectation was geometrically impossible): every armed
ceiling ≥ the 64 KB floor = netty's high-water mark, and a writable-tick probe
never reads above the watermark, so budget-0 whole-tick holds are unreachable —
holds book `yielded=`, and `deferred=` stays ~0.** Acceptance: near-vanilla
action latency (tab ping in the hundreds of ms, not seconds), `ceil=` ~100-150
KB, `yielded=` climbing steadily, `deferred=` ~0, `obuf=` peak bounded
~ceiling + one payload OUTSIDE vanilla burst windows (respawn//tp bursts are
channel-wide and legitimately exceed it). The budget's work has NO dedicated
counter — it shows as the bounded obuf peak and the restored latency.
(`deferred=` climbs only with yield disabled or an operator-FIXED ceiling.)

## Review-round log

- **Round 1 (2026-08-13, 2 reviewers).** Control lens: REDESIGN — v1's two
  physical premises were wrong: (a) the "2 MB watermark" was a misread of the
  `obuf=` high-water field (real netty high water: 64 KiB), which made v1's
  clamp degenerate ([64K,64K]) and the feature inert-or-redundant with yield;
  (b) the per-flush ENTRY gate cannot bound latency — the banked-token burst
  (cap/4 ≈ 6.25 MB) rides through the one open tick; the actuator must be the
  in-loop per-payload budget (the yield plan's pre-registered v3 lever) with a
  one-payload presence gate. Also: constant 2 MB cap (not watermark-sourced),
  per-flush written accumulator reset at the probe read, −1 poisons the next
  sample, v18-RAW over-count breaks v1's "always conservative" claim
  (documented), floor resets must be send-success-only (the else-branch reset
  admits an unbounded-starvation interleave), AUTO/fixed mode must be plumbed,
  dimension-change cold-start documented, CI-inertness re-argued via the
  no-sample path. Estimator core verified sound and self-stabilizing in both
  regimes. Blast-radius lens: IMPLEMENT WITH FIXES — within-tick budget
  (independently converged with control MAJOR 2), the `/lsslod set` kill
  switch, the ChannelAccessorContractTest pin, the inverted Paper floor
  assert, the full docs/notes sweep, `deferred=` meaning flip, volatile
  `ceil=` gauge, Paper goldens verified unaffected. ALL folded into this v2.
- **Post-hoc whole-feature round (2026-08-13, 2 Fable reviewers, user-ordered —
  reviewed the round-3 tree, reconciled against round 4):** verdicts FIX
  FORWARD ×2, nothing blocking. Mechanism lens: pure-drain sampling proven
  one-sided (no over-read constructible), the streak un-fakeable, the composed
  yield+AUTO default regime verified convergent by tick-walk (~180-320 ms
  standing queue on the target link) — and the HEADLINE: the acceptance
  signature was corrected (deferred=0 under defaults; see §Test plan).
  Integration lens: its MAJOR (unbounded EWMA doubling on fast links) was
  already structurally resolved by round 4's ring-clear; remaining minors
  folded same-branch: non-AUTO ticks poison the estimator (the mode-flip junk
  sample), a broken probe (-1) imposes NO budget (no-signal fail-open), the
  floor tick no longer books deferred=, the composed-regime and ceil=-value
  pins added, the design body de-staled (this fold), the Fabric config-suite
  comment fixed. Registration audit: complete; fixed-ceiling semantics
  byte-identical; the live-rig cap restore verified residue-free.

- **Round 4 (2026-08-13, SECOND live falsification — same session):** the
  round-3 build still measured `ceil=1.2 MB` (user ping 6000 ms). Pure-drain
  samples were themselves poisoned: **netty's pending gauge measures drain into
  the KERNEL socket buffer, not the network** — after each burst window the
  kernel has accumulated room and absorbs hundreds of KB at memory speed, so a
  bounded MINORITY of hold-tick samples read 10-30 MB/s and ANY mean-family
  estimator (the EWMA) integrates them into a permanent multi-MB/s bias. FIX:
  the estimator is now a WINDOWED MEDIAN (ring of the last 41 valid pure-drain
  samples, armed at 15) — most hold ticks drain at true network pace, so the
  median ignores the spike minority entirely. The fast-streak up-recovery now
  CLEARS the ring (graceful retrain) instead of doubling a scalar. The EWMA
  and its tau are retired.

- **Round 3 (2026-08-13, LIVE FALSIFICATION on the rig — the acceptance gate
  earning its keep):** the first deployed build measured `ceil=1.5 MB,
  deferred=0, yielded=668` on the 4 Mbps session — the EWMA trained to ~6 MB/s
  on a 500 KB/s link and the ceiling never bound (user-observed: vanilla
  updates arriving in 3-5 s clumps = 1.5 MB / 500 KB/s). ROOT CAUSE all three
  review rounds missed: netty writes are ASYNC — bytes handed to the event
  loop are not yet reflected in the pending gauge, so a burst tick's
  written-inclusive sample (`prev + written − now`) reads phantom multi-MB/s
  drain (~28 MB/s spikes live). FIX: the estimator samples ONLY PURE-DRAIN
  intervals (`written == 0` since the last probe: `drained = prev − now`, no
  written term — hold ticks dominate on exactly the links the ceiling serves),
  plus a bounded fast-streak UP-recovery (40 consecutive intervals of "wrote
  ≥ 32 KB, gauge still ≤ 4 KB" double the EWMA — pure-drain sampling alone
  cannot observe an improved link, and visibility lag cannot sustain a 2 s
  streak). The stability §'s "samples flow while the ceiling binds" claim is
  amended: in converged partial-flush states samples pause and the ceiling
  freezes (degradation re-samples via holds; improvement recovers via the
  streak). Confirmed live: capping the player's bandwidth to 0.4 MB/s (the
  burst-bank experiment) collapsed the latency to normal — the injection
  amplitude was the entire effect.

- **Round 2 (2026-08-13, delta review by the round-1 control reviewer):**
  IMPLEMENT WITH FIXES. Fixes folded: the 2 MB constant is a DISARM threshold,
  not a clamp (a clamp silently governs fast clients under a raised bandwidth
  cap; self-inflation cannot pass a clamp — resolves the v2-fresh
  ceil=off-vs-clamp inconsistency); floor resets rescoped (queue-empty resets
  restored, refused-send ticks still never reset); `deferred=` counts only
  whole-tick holds; wire-vs-raw units named in the fast-link argument; the
  v18 actuator-side conservatism clause; the ~199-tick two-counter silence
  bound stated; the obuf acceptance criterion scoped to non-vanilla-burst
  windows. Implementation decision logged post-round: AUTO evaluates AFTER
  the yield gate (yield pins and unwritable semantics unchanged; AUTO bounds
  the writable-tick burst window, which is the v3 lever's whole target);
  fixed ceilings keep ceiling-first (F2-7).
