# Server-side send pacing — spread the burst, never govern the rate — plan

**Status: PLANNED, unreviewed** (2026-08-13, the adaptive-transfer-rate program's
shelved follow-up, resolved into a concrete design; companion to
`adaptive-transfer-rate-plan.md`). User direction: spreading the client's
requested work over time a bit is good enough to stop spikes from totally
blocking vanilla messages — it does not need to be perfect, it must NOT
artificially rate-limit send throughput (rate ownership is the CLIENT's, via
want-set sizing), and it needs only a very rough target. All options
considered below, including the user's own alternative (pace on the current
send queue length + send rate instead of inferring from want-set size) —
which, refined, is the recommendation.

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
- Against (and the fix): a NAIVE always-on version adds ~HORIZON ticks of
  delivery latency to every sustained flow (equilibrium queue = HORIZON ×
  arrival rate; each payload waits ~HORIZON ticks), which would slow the
  4 Hz warm-backfill loop — an artificial throughput reduction at the
  system level, violating the brief. Fixed by EVIDENCE-ARMING (§3): pacing
  applies only when the channel has recently shown pressure, so fast links
  never pay it.
- Verdict: **CHOSEN**, with evidence-arming.

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
send loop mid-tick).** Tempting — the kernel becomes the pacer — but
netty's flush is async: a burst of writes can flip writability transiently
even on fast links, capping per-tick writes near the 64 KiB watermark on
links that could take megabytes. Unpredictable, and it is precisely the
netty-gauge-driven pacing family the AUTO ceiling's falsifications closed.
Rejected; the ENTRY-check yield gate stays the only writability consumer.

## 3. The chosen design: evidence-armed proportional drain

All in `AbstractPlayerRequestState.flushSendQueue` (common — both
platforms), column-payload lane only (BatchResponse/far-player lanes are
tiny and latency-sensitive; they already ride separate paths).

- **Arming (the fast-link exemption, and the CI-inertness property):**
  per-player countdown `paceArmedTicks`, set to `PACE_ARM_WINDOW_TICKS`
  (100 ≈ 5 s) whenever this tick's probe snapshot reads NOT_WRITABLE **or**
  `pendingBytes > PACE_ARM_PENDING_BYTES` (256 KB — catches modest links
  whose writability rarely flips but whose socket queue visibly grows),
  decremented otherwise. Pacing applies only while `paceArmedTicks > 0`.
  A genuinely fast link never arms and never pays a tick of latency; a
  modest link arms on the first sign of pressure and stays armed while
  pressure recurs. Loopback never goes unwritable and pending reads ~0
  (both already pinned for the yield gate), so **harness inertness is
  structural** — same property, same pin pattern.
  Known accepted corner: the very FIRST spike of a session lands before
  any evidence exists and dumps like today; every subsequent spike is
  paced. "Does not need to be perfect."
- **The budget:** while armed, this tick's column flush writes at most
  `paceBudget = max(PACE_FLOOR_BYTES, queuedWireBytes / PACE_HORIZON_TICKS)`
  wire bytes — checked in-loop before each send EXCEPT the first (the
  one-payload presence gate: a legal oversized column ships whole, the
  next flush waits — the deleted AUTO budget's proven shape). Leftover
  stays queued (ordinary retention; nothing is dropped, nothing bounces).
  `PACE_HORIZON_TICKS = 10` (~500 ms), `PACE_FLOOR_BYTES = 96 KB`.
- **Denomination:** WIRE bytes (`QueuedPayload.wireBytes`) — this
  mechanism is about the socket, unlike the limiter's deliberate
  raw-byte denomination. `queuedWireBytes` is a running counter maintained
  at add/poll/prune/drop (an O(queue) per-tick sum would also work — the
  yield byte-integral precedent — but the counter is cheaper and exact).
- **Composition order** (top of flush unchanged): fixed ceiling entry gate
  → yield gate (unwritable ticks still book `yielded=`, pacing never
  evaluates) → pace budget bounds the writable tick's loop → bandwidth
  limiter charges per payload as today. The pingf-cut allocation and the
  pace budget MIN-compose implicitly (the loop stops at whichever binds
  first). The starvation floor path is exempt (a floor tick ships exactly
  one payload by contract already).
- **Observability:** per-player `paced=` counter (ticks where the pace
  budget stopped a PARTIAL flush — a mechanism counter like `yielded=`,
  never a loss signal), rendered in the per-player diag line after
  `pingf=`. One more `PlayerDiag` field + golden re-pin.
- **Config:** `enableSendPacing` (server, default true) — a `/lsslod set`
  boolean row (the `enablePingBackstop` precedent; the rig A/B lever).
  The three constants stay constants; no numeric knobs.

## 4. Interaction with the fast want-set cadence (worked through)

- **Governed sessions (4 Hz quarter-batches):** a governed burst is
  ~desired/4 ≈ 100-150 KB — at or under one floor quantum, so pacing ships
  it in 1-2 ticks: inert. No double-throttling of the governor's loop (the
  governor owns rate; pacing sees only what the governor already shaped).
- **Ungoverned warm backfill on a fast link (the 4 Hz showcase):** never
  ARMS (no pressure evidence), so the full bank still ships at once and
  the ≥95%-answered fast trigger fires exactly as today. This is the case
  a naive always-on drain would have slowed ~2-4×; evidence-arming is what
  keeps the brief's "never artificially rate-limit" true.
- **Ungoverned spike on a modest link (the target case):** first pressure
  evidence arms pacing; a 6.25 MB bank thereafter ships ≤ max(96 KB,
  Q/10) ≈ 640 KB on the worst tick, decaying exponentially — and after
  tick one the yield gate holds the rest, so total in-flight ahead of
  vanilla is bounded at ~one paced tick (~0.5 s of a 10 Mbps link) instead
  of ~5 s. The client's 1 Hz fallback re-declares regardless; the fast
  cadence degrades only as much as delivery actually slows — on a link
  this size that is the honest cost of the link, not the pacer.
- **The cadence-floor alignment:** HORIZON (10 ticks) spans two client
  fast-fire floors (5 ticks) — a paced batch is still mostly delivered
  within one client re-scan period, so the loop never starves; and because
  the floor exempts sub-96 KB/tick flows, steady paced delivery adds zero
  latency below ~1.9 MB/s.
- **v16/legacy sessions:** no interaction — pacing is below the dialect
  layer entirely (it shapes the send queue, whatever filled it).

## 5. Test plan

- T1 (common flush suite, the TransportYieldFlushTest pattern): budget
  math (floor binds small queues, Q/HORIZON binds big ones), the presence
  gate (oversized payload ships whole), leftover retained not dropped,
  arming truth table (unwritable arms, pending>threshold arms, decrement
  disarms, never-pressured never paces — the CI-inertness pin), yield
  composes (unwritable ticks book yielded= and never evaluate the budget),
  the floor-tick exemption, `paced=` counts partial stops only, the
  queuedWireBytes counter's add/poll/prune/drop conservation, kill switch.
- Contract pins: both platforms pass the config gate into the flush (the
  ChannelAccessorContractTest pattern); `paced=` diag golden; config
  default-ON pins both suites; registry row.
- Guard soaks: fresh-backfill + disk-saturation + rate-limit-storm —
  expected UNCHANGED baselines (structural inertness via the arming
  evidence; a moved baseline is a finding, not a re-baseline).
- Live gate (the rig, proxy at a MEDIUM rate ~4-10 Mbps): client governor
  kill-switched off to isolate the pacer; join and watch the first-spike
  dump (unpaced, accepted) then subsequent waves paced (`paced=` climbing,
  ping bounded, no multi-second action freezes); then governor back on to
  confirm no double-throttle (governed rate unchanged, `paced=` ~flat).

## 6. Open questions (decide at implementation)

- Whether `paced=` earns a place in the soak exporter (schema addition +
  check_soak allowlist) or stays diag-only. Lean: diag-only until a
  scenario needs it.
- Whether the arming pending-threshold (256 KB) should scale with the
  fixed ceiling when an operator sets one. Lean: no — constants until
  evidence.
- Whether the first-spike-ever corner deserves closing (pre-arm pacing for
  the first N ticks after registration?). Lean: no — it re-introduces
  latency on fast-link joins, the exact tradeoff evidence-arming exists to
  avoid.
