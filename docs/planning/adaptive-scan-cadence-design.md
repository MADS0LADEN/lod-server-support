# Adaptive Scan Cadence — completion-triggered want-set re-declaration

> **2026-08-18 amendment (scanner-reopened-rings-plan.md — scan prefix retention):**
> the RESET MECHANISM this doc describes is retired: `resetConfirmedRing()` no longer
> exists, and `recenter(d)` DECREMENTS the confirmed prefix and reopens a ring bitset
> instead of zeroing it (kill switch `enableScanPrefixRetention=false` restores the
> semantics described here). The CADENCE CONCLUSIONS all still hold: the walk-cost gate
> replaced the `confirmedRing > 0` proxy, `predictedWalkCost` prices the movement window
> at the bare from-zero cost (so the flight regime and the ring-127 cliff are unchanged),
> and the retry half rides the verbatim `hasActionableRetries` rung. Read the mechanism
> prose below as historical.

**Status:** reviewed plan v2 (3-agent Opus review folded — §13), branch `feat/adaptive-scan-cadence` (off main)
**Date:** 2026-08-01
**Scope:** client-only (Fabric `SpiralScanner` / `LodRequestManager`). No wire change, no
protocol bump, no server behavior change on either platform (comment-only server edits).

## 1. Motivation

The want-set model declares at a fixed 1 Hz cadence (`SpiralScanner.maybeScan`,
`scanTickCounter` vs `LSSConstants.TICKS_PER_SECOND`). Throughput is therefore
**cadence-bound, not serve-bound, whenever the server is fast**: each declaration carries at
most `WANT_SET_BUDGET` (800) positions, so the hard ceiling is 800 columns/s — and on a warm
path (LOD store hits, in-memory probes, warm disk) the server drains the whole batch in a few
hundred ms, then both sides idle until the next 20-tick fire. Observed live on the store
branch: the map fills in 1 Hz spurts with the pipeline provably empty between them. A warm
rejoin at large LOD distance (~16k columns) has a ~20 s floor that is pure cadence.

The fix: **when the current declaration is nearly fully answered, declare again immediately**
(subject to a floor); the 1 Hz cadence remains as the fallback that also stays the sole
self-heal for server-side silent drops.

Because a declaration is a full want-set *replacement*, declaring more often is semantically
identical to declaring at 1 Hz. The server's latest-wins mailbox, backlog replace, and
supersession accounting absorb arbitrary declaration timing by design (review-verified
server-side: `hasPendingRequest` duplicate rung, duplicate-serve grace, miss memo, law A1
conservation — §13). This works against every deployed v17+ server with no compatibility risk.

Two distinct regimes benefit differently (review finding — be honest about which):
- **Revalidation-heavy warm rejoin** (client cache + `up_to_date` answers, no decode work):
  full 4 Hz, the biggest win.
- **Data-bearing warm backfill** (store/disk serves of real columns): the single-threaded
  decoder is the local bottleneck; the fast path pipelines the next serve against the current
  decode and settles into bang-bang rate-matching against the decode rate via the pressure
  gate (§2 cond. 7). Better than 1 Hz, less than 4 Hz — by design.

## 2. Design summary

`maybeScan` fires when EITHER:

- **Fallback (unchanged):** `scanTickCounter >= TICKS_PER_SECOND` (20 ticks), OR
- **Fast re-scan (new):** ALL of (cheap-first evaluation order):
  1. `enableAdaptiveScanCadence` is true (new client config, default true — kill switch),
  2. `scanTickCounter >= FAST_RESCAN_MIN_INTERVAL_TICKS` (5 ticks = 250 ms floor → max 4 Hz),
  3. the scanner is **armed**: `lastSentCount > 0`, set ONLY by the manager via
     `noteDeclared(count)` beside `tracker.replaceWith` (§3) — a 0-count walk, a send
     failure, `reset()`, `resetScanCounter()`, and `disconnect()` all disarm,
  4. `outstandingSupplier != null` (production wires `tracker::size`; bare test rigs are
     automatically unaffected),
  5. `sessionConfig != null && sessionConfig.protocolVersion() != V16_COMPAT_PROTOCOL_VERSION`
     — see "why the v16 gate is non-optional" below,
  6. **walk-cost gate (two halves — the implementation-review round found the retry half)**:
     `confirmedRing > 0` covers the SYNCHRONOUS prefix invalidations (movement
     (`recenter()`), dirty re-opens (`resetConfirmedRing()`) zero the field before the next
     tick's predicate), and `!columns.hasActionableRetries(...)` covers the IN-WALK one (a
     retry mark resets the prefix inside `scan()` after the field was read, and every walk
     re-derives `confirmedRing >= 1` since ring 0 is empty — the field alone structurally
     cannot gate it). A zero-prefix walk is a full from-ring-0 re-walk (the render-thread
     hitch shape the code already documents at the 2048 ceiling). Those walks stay at 1 Hz;
     only cheap frontier walks run fast,
  7. **mild-pressure gate (proportional, NOT strict zero, all THREE pipes)**:
     `columnQueueSize < columnQueueHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR &&
     columnQueueBytes < byteHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR &&
     ingestBacklogSections < ingestBacklogHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR`
     (divisor 4 ⇒ queue < 1500 columns AND < 48 MiB queued bytes AND ingest < 1536
     sections; -1 no-signal passes). The byte term was an implementation-review addition:
     the byte halt is the one that BINDS for real terrain columns (> ~32 KiB), so a
     count-only gate left the fast path open at up to 74% of the binding halt,
  8. **outstanding below threshold**: `outstanding <= lastSentCount / FAST_RESCAN_OUTSTANDING_DIVISOR`
     (divisor 20 ⇒ 5%, integer math — see §5.2).

On either fire: `scanTickCounter = 0` (the fallback measures from the last fire: "if it's
been more than 1 second since the last batch, fire regardless").

### Why these conditions

- **Floor (250 ms):** bounds worst-case server ingress and C2S upstream at 4× current and
  bounds the client walk cost. 4 Hz already collapses the inter-spurt gap from ~800 ms to
  ~200 ms; the serve rate / decode rate becomes the pacing instead of the clock.
- **Armed:** a converged client's walk returns 0 and must NOT re-walk every floor interval
  (§4.1). A failed send must not become a 4 Hz retry hammer on a dying connection (§5.4).
- **v16 gate is non-optional, not merely polite (review finding):** besides the legacy
  server's real rate limiter, Tier B's `onColumnNotGenerated` removes the position from the
  tracker *before* its transient-heal early return — every legacy gen-slot bounce drops
  `outstanding`, so a v16 session would fast-fire on exactly the churn loop and hammer the
  old server's gen slots at 4 Hz. `ClientSessionGate` admits only versions 18 and 16, so
  `!= 16` is exact today and stays conservative if an intermediate dialect ever lands.
- **Walk-cost gate (cond. 6):** review round falsified the v1 claim that fast walks are
  always cheap — `recenter()` zeroes the confirmed prefix on EVERY chunk crossing, so
  movement walks re-walk from ring 0 (up to ~4·lod² `classify()` calls; ~1M at lod 512).
  Gating on `confirmedRing > 0` keeps every zero-prefix walk at 1 Hz with immediate temporal
  semantics (the reset lands before the next tick's predicate). Residual shape (§11): a
  confirmed-but-holey prefix walk can still be moderately expensive; self-limited by §5.3's
  threshold tightening, escalation documented there.
- **Mild-pressure gate (cond. 7):** v1's strict-zero gate was reviewed as **anti-correlated
  with the trigger by construction** — `handleVoxelColumn` removes a column from the tracker
  and hands it to the decode queue in the same statement pair, so at the instant outstanding
  reaches 5% the queue holds its batch peak, and strict zero would have made the feature
  inert for data-bearing backfill (the motivating case). The ¼-of-halt allowance activates
  the fast path through normal decode depths (a few hundred columns), while deeper pressure
  returns the cadence to 1 Hz where the existing budget taper + halt own regulation. The
  emergent behavior under a sustained decode bottleneck is bang-bang around the ¼ line —
  i.e. the cadence rate-matches the decoder. A halt-recovery tick (queue ≈ halt threshold)
  is far above the line and cannot fast-fire (pinned, §9).
- **5% threshold (not 0%):** requiring full drain would let a handful of generation-bound
  stragglers pin the cadence to 1 Hz while 95% of the next ring is warm-servable. It also
  bounds redundant re-declares per fast batch at 5%, and §5.3's tightening bounds the
  straggler chatter.

## 3. Mechanics — where each piece lives

`SpiralScanner` owns the trigger policy; `LodRequestManager` owns arming (review change:
arming moved beside the tracker replace so "`lastSentCount` == the count the tracker was
replaced with" is true **by construction** across the two classes — and every rig that
drives `maybeScan` directly without `tickScanPhase`, like `LodRequestManagerTest.maybeScanOnce`,
never arms and stays bit-identical):

- New scanner fields: `int lastSentCount` (armed state, written only via
  `noteDeclared(int)`), `IntSupplier outstandingSupplier` (null ⇒ fast path off),
  `BooleanSupplier adaptiveCadenceEnabled` (defaults to reading
  `LSSClientConfig.CONFIG.enableAdaptiveScanCadence`; package-private seam so tests don't
  mutate global config — the `ingestBacklogSupplier` pattern), `boolean lastScanWasFast`,
  `long fastScans` (observability).
- `maybeScan` head:
  ```java
  int ticksSinceFire = ++this.scanTickCounter;
  boolean fast = ticksSinceFire < LSSConstants.TICKS_PER_SECOND;
  if (fast && !fastRescanDue(ticksSinceFire, columnQueueSize, columnQueueHaltThreshold,
          ingestBacklogSections, ingestBacklogHaltThreshold)) {
      return -1;
  }
  this.scanTickCounter = 0;
  // fast fires skip the missingVanilla probe (an O((2·vd+1)²) hasChunk sweep that exists
  // purely for diag/trace — keep the last periodic value instead of 4×-ing it)
  if (!fast) this.missingVanillaChunks = missingVanilla.getAsInt();
  ```
  `lastScanWasFast = fast` and `fastScans++` are set only after the `budget <= 0` guard
  (unreachable in production — the taper floors at 1 and the buffer is 1024-long — but a
  zero-length test buffer must not count a non-walk as a fast scan).
- `noteDeclared(int count)`: package-private; sets `lastSentCount = count`. Called from
  `tickScanPhase` right after `tracker.replaceWith(...)` with the scanned count, and from
  `sendRequests`' catch with 0 (the send is synchronous in the same tick, so the disarm
  lands before the next tick's predicate). `disconnect()` also calls `noteDeclared(0)`
  (defensive — the manager is normally dropped right after, but the session gate's teardown
  is deliberately self-sufficient).
- Disarm on lifecycle edges: `reset()` and `resetScanCounter()` zero `lastSentCount`
  (session config, `flushCache`, dimension change — the deliberate post-dimension-change
  20-tick wait must not be bypassed via a stale armed state against an empty tracker).
  `recenter()` does NOT disarm — movement fast-fires are instead gated by cond. 6.
- `LodRequestManager` gets a constructor (it currently has none) that wires
  `this.scanner.setOutstandingSupplier(this.tracker::size);` after field init. Both objects
  are main-client-thread only — review-verified: every tracker mutation (column receipt,
  batch responses, dirty, ingest-failure reports) arrives via `client.execute(...)` tasks on
  the same thread that ticks `maybeScan`, so no answer can land mid-tick and the supplier
  read needs no synchronization.

`InFlightTracker` code is unchanged, but its class javadoc must be rewritten: it pins
"exactly two consumers" and the cadence trigger is a third. The doc must name the
distinction explicitly — a **cadence input**, never a send filter; the walk still declares
every unsatisfied position (the deleted v16 in-flight send-suppression is NOT returning, cf.
`anyChaosInterleavingLeavesNoPositionPermanentlyOrphaned`).

`tickWithContext`'s phase order is unchanged — the backpressure halt still precedes the
scan, so halted ticks never evaluate the fast path and `scanTickCounter` stays frozen during
a halt, exactly as today.

## 4. Invariants preserved (review checklist)

1. **At convergence the client sends NOTHING.** A walk returning 0 sends nothing AND disarms
   (via `noteDeclared(0)` in `tickScanPhase`), so a converged client re-walks at exactly
   1 Hz as today. The backpressure clear remains the only producer of empty batches. Soak
   quiescence (`service.requests_received` going still) is unaffected. Live-pinned by the
   `dirty-range-filter` soak (exactly-flat `requested_total` — §9).
2. **No send-time suppression of awaited positions** — untouched; the fast path changes only
   *when* `maybeScan` fires, never what the walk declares.
3. **Per-position re-declaration bound (restated honestly — twice-corrected):** a position
   a fast walk omitted (budget/center shift under movement) waits at most
   `2*TICKS_PER_SECOND - 1` = **39 ticks** when ONE fast fire intervenes (fast fire at
   tick 19 resets the counter; the next fire ≤20 ticks later re-declares it — every fire
   runs the identical walk). A chain of budget-truncated fast walks can extend that
   further; all of it self-heals by re-declaration. Today's bound is 20 ticks; the
   regression is bounded, movement-only, and the affected position was mid-supersession
   anyway. (The v1 claim "never worse than today" was false; v2's formula
   `TICKS + FLOOR - 1` computed 24, not 39 — both fixed.)
4. **First-scan-immediate on join** — `reset()` still primes `scanTickCounter = TICKS-1`.
5. **Dimension-change debounce** — `resetScanCounter()` still delays the next scan a full
   20 ticks, and now also disarms.
6. **Backpressure halt precedence** — unchanged phase order; halt returns before the scan
   phase; the recovery tick out of a halt cannot fast-fire (cond. 7 — pinned, §9).
7. **v16 sessions are bit-identical** — cond. 5 (Tier A and Tier B both carry
   `protocolVersion() == 16`; review-verified `clampToProtocolBounds` preserves it).
8. **Kill switch restores bit-identical pre-change behavior** — `fastRescanDue`
   short-circuits on the config read; nothing else in the tick path changes.
9. **Budget policy untouched** — `WANT_SET_BUDGET`, the pressure taper, and
   `WantSetBudgetInvariantTest`'s inequality are unchanged.
10. **Anti-debounce pins (scans, narrowly — review fix):** the fast path can only *advance*
    a scan, never delay one; the 20-tick fallback is unconditional. (Per-POSITION timing is
    #3's bound, not this item — the v1 phrasing over-claimed.)

## 5. Edge cases analyzed

### 5.1 Converged-client loop (the big one)
Armed + outstanding 0 + walk returns 0 → `noteDeclared(0)` disarms → next fires at 1 Hz.
Without the disarm the client would re-walk the full spiral every 250 ms forever while idle.
Pinned by unit test (§9) and live by `dirty-range-filter` + `enabled-false` soaks.

### 5.2 Integer threshold at small N
`lastSentCount / 20` is 0 for declares < 20, so the trigger degenerates to
`outstanding == 0` — correct (strictest) behavior for tiny tails.

### 5.3 Chatter analysis — two regimes (review split)
**(a) Straggler tail:** 800 declared, 40 gen-bound stragglers outstanding → threshold 40 ⇒
fast fire → the walk re-declares the 40 (+ any new frontier). If mostly stragglers,
`lastSentCount` becomes ~40 ⇒ next threshold is 2: geometric tightening kills sustained
4 Hz chatter within one fast fire. Server cost of the re-asks: a generation-WAITING position
holds a `PendingRequest` and resolves at the `hasPendingRequest` duplicate rung (not the
memo — review correction); a position whose escalation was *refused* hits the memo rung,
which re-attempts gen admission (refusals counted `gen_order_gated`/`superseded`) — all
cheap, all existing accounting.
**(b) Active backfill:** the walk refills to `WANT_SET_BUDGET` from the frontier every fire,
so the threshold stays 40 and sustained fast cadence is the *intended* steady state, bounded
by the serve rate (answers must reach 95% before each next fire) and by cond. 7's decode
rate-matching. This is deliberate, not an excluded case.

### 5.4 Send failure
`sendRequests`' catch already empties the tracker (`replaceWith(positions, 0)`) — which
would make `outstanding == 0` and *satisfy* cond. 8. The catch's `noteDeclared(0)` closes
that: a dying connection retries at 1 Hz, exactly as today.

### 5.5 Movement (review-corrected: the most expensive case, now gated)
The movement prune can drop outstanding below threshold — but `recenter()` zeroes
`confirmedRing` on the same tick, so cond. 6 keeps every post-crossing (full re-walk) scan
at 1 Hz. Once the player settles, one periodic walk re-confirms the prefix and the fast
cadence resumes. Sustained flight therefore keeps today's exact walk-cost profile. The v1
claim that movement fast-fires are "desirable and safe" ignored walk cost; supersession
safety was and remains true.

### 5.6 Ingest-failure fast retry (accepted, bounded regression)
`onIngestFailure` removes the position from the tracker, so a failure burst can fast-trigger
a ts=-1 re-declare. For a consumer that reports ingest backlog (Voxy via the bridge),
cond. 7 suppresses the fast path under real overload. For a NON-reporting consumer
(`pendingIngestBacklog()` defaults to -1), and for the no-consumer clear path (nobody left
to report), there is no such shield: the strike cadence compresses from ~3 s to ~1 s
worst-case (RTT-bounded). The 3-strike session park still terminates every loop; we accept
the compression and state it here rather than adding a wall-clock strike floor in v1.

### 5.7 Duplicate-serve grace interplay (restated as a rate — review fix)
Crossings are a *rate* (`departure_rate × latency per scan` — the grace doc's model), so 4×
the scan rate ⇒ up to 4× `grace_skipped`/`duplicate_skips`. Both are law-A1 disposition
terms and `soak_report` mechanism counters — accounting inflation, not loss. The grace's
termination argument changes shape: at a 250 ms floor, up to two re-declares land inside the
500 ms window and the third re-resolves — termination now rests on the **structural**
property (the departure stamp is written once at send-success and never refreshed by a
re-ask), not on "the next 1 Hz declaration outlives the grace". Implementation must update
the comment at `IncomingRequestRouter.resolvedAsDuplicate` and
`docs/planning/duplicate-serve-grace.md` §termination accordingly (comment-only server-side
edits). The RTT>500ms shape stays self-suppressing: slow answers keep outstanding high.

### 5.8 RTT metric bias
`recordRequestSent` re-stamps on every re-declare (pinned last-declare semantics). Fast
re-declares refresh straggler stamps up to 4× more often, biasing their RTT samples lower —
and RTT p50/p95 become non-comparable across builds with different cadence configs.
Diagnostic-only; noted for soak/benchmark comparisons (§8).

### 5.9 Pressure-interval recovery (rewritten — v1 analyzed an unreachable path)
`budget <= 0` is unreachable in production (taper floors at `Math.max(1, …)`, buffer length
1024), so no fire is ever "consumed by the taper". The real case: the armed state persists
across a pressure-gated interval (cond. 7 failing for seconds while the decoder churns);
when pressure falls below the ¼ line the fast path resumes at the floor cadence instead of
waiting for the next full second — intended, and covered by the bang-bang analysis in §2.

## 6. Server-side impact

- Worst case 4 declarations/s/client (floor-bound), each a normal want-set replace. The
  latest-wins mailbox absorbs any rate; if the processing cadence lags, intermediate batches
  are overwritten and counted `superseded` (existing accounting, existing meaning; the
  mailbox-overwrite ratio shifts from ~1-in-20 to ~1-in-5 polls — comment sweep, §10).
- **C2S upstream cost (review addition):** a declaration is 16 bytes/entry pre-compression —
  ~12.8 KB per full batch, so worst case ~51 KB/s upstream per client vs ~12.8 KB/s today
  (zlib helps both equally). Server inbound decode scales the same way. Bounded by the
  floor; noted as a watch item (§11).
- Fast fires require ≥95% answered, so replaced backlogs are nearly empty ⇒ per-replace
  supersession stays small; redundant re-asks are ≤5%/batch and absorbed by the grace /
  `hasPendingRequest` / memo rungs (§5.3's attribution).
- During cold generation and churn scenarios, refused misses are SILENT drops — no answer
  ever removes them from the tracker, outstanding stays high, and the fast path is
  structurally inactive. (This is the load-bearing reason the soak churn ceilings hold —
  review-verified against `rate-limit-storm`'s measured 370 vs ceiling 800.)
- **A1 latent-flake exposure:** the documented per-send-event false-positive shapes (client
  transport throw, batch-frame send failure, reload window) scale with send events — ~4×
  the per-run exposure. Law A1 itself is structurally rate-invariant (each declared entry
  draws exactly one disposition). If an A1 red's imbalance equals one batch/frame, it is
  that catalog, not conservation — diagnose, don't re-investigate.
- **Folia (experimental, no 26.2 build):** the one place declaration *rate* is structurally
  visible — a fast declaration can land inside the one-tick probe hold window (~1-in-5 at
  the floor), dropping the held batch as superseded (self-healing by construction, the
  pinned `republishHeldBatch` CAS/generation-guard semantics). No change needed; noted.

## 7. Config & constants

- `LSSClientConfig.enableAdaptiveScanCadence` — **default true** (user decision 2026-08-01).
  Comment documents: completion-triggered re-scan, 4 Hz ceiling, kill switch restores the
  fixed 1 Hz cadence, no effect on v16 sessions. Client config only; no server field, no
  Paper change, nothing on the wire. `validate()` needs no clamp (boolean), so the default
  and the GSON key are pinned by `ConfigValidationTest` (§9).
- `SpiralScanner.FAST_RESCAN_MIN_INTERVAL_TICKS = 5` (250 ms floor → max 4 Hz).
- `SpiralScanner.FAST_RESCAN_OUTSTANDING_DIVISOR = 20` (5%).
- `SpiralScanner.FAST_RESCAN_PRESSURE_DIVISOR = 4` (fast cadence permitted below ¼ of each
  halt threshold; both thresholds already arrive as `maybeScan` parameters — no new
  plumbing).
  Scanner-local statics (client scan policy, like the manager's backpressure fractions).

## 8. Diagnostics & measurement

- Client trace `scan` event gains `"fast":true|false` (always emitted — review preference:
  counting fast-vs-periodic must not require inferring from absence).
- `SpiralScanner.fastScans` counter + `wasLastScanFast()`; manager passthrough
  `getFastScans()`; `/lss diag`'s Scan line gains `fast=N` (`LSSClientCommands`).
- **Acceptance criterion (review addition — the inert-gate risk needs a measurement, not a
  hope):** a live store-warm backfill (`run-fabric-store`, warm rejoin) must show
  `fastScans` climbing at multiple per second during the fill. `fastScans ≈ 0` on a warm
  backfill means a gate is suppressing the feature — investigate cond. 6/7 before shipping.
- Benchmark/soak comparison hygiene: `send_cycles`/`positions_requested` show a ~4×
  cadence artifact in A/Bs (not a regression); RTT p50/p95 are non-comparable across
  cadence configs (§5.8); `soak_report` may show new `SPIKES@` lines on the `requests` row
  from bimodal 1 Hz/4 Hz windows (lens-only, never a gate).
- No soak client-snapshot schema change (`check_soak.py` laws verified rate-invariant —
  §13; `send_cycles` is A6-monotonicity only).

## 9. Test plan

**`SpiralScannerTest` (new pins; bare rigs have no supplier and are provably unaffected —
review-verified across the whole file):**
1. Fast fire at floor: armed (via `noteDeclared`) + supplier at 0 outstanding + confirmed
   ring > 0 fires at tick 5, not ticks 1–4.
2. Fallback regardless: outstanding above threshold ⇒ fires at exactly tick 20.
3. Threshold edge: `noteDeclared(800)` ⇒ fires at outstanding 40, not 41.
4. Integer floor: `noteDeclared(19)` ⇒ threshold 0 ⇒ fires at outstanding 0, not 1.
5. Pressure gates (proportional): queue at ¼-threshold blocks, below passes; same for
   ingest backlog; -1 ingest (no signal) passes.
6. Walk-cost gate: `confirmedRing == 0` blocks the fast fire (recenter()/resetConfirmedRing()
   paths), re-confirming re-enables.
7. Unarmed: fresh scanner never fast-fires; `noteDeclared(0)` disarms (converged loop pin —
   after a 0-count walk, ticks 5..19 all return -1).
8. Kill switch seam: `adaptiveCadenceEnabled` false ⇒ periodic only.
9. v16 session (`protocolVersion 16`) ⇒ periodic only; null sessionConfig safe.
10. `reset()` / `resetScanCounter()` disarm; `recenter()` does not (its gating is cond. 6).
11. Counter reset on fast fire: after a fast fire at tick 5, the fallback fires 20 ticks
    later (the "1 s since last batch" semantic).
12. Fast fires skip the `missingVanilla` probe (supplier not invoked; last value retained).
13. Geometric tightening: `noteDeclared(30)` ⇒ threshold 1.

**`LodRequestManagerTickTest` (integration through the real tick path):**
14. End-to-end fast cycle: scan+send → answer all via `onColumnReceived`/`onColumnUpToDate`
    → next scan fires within 5–6 ticks and declares the next annulus (arming via the
    production `tickScanPhase` wiring).
15. Send-failure disarm through the production wiring: throwing `BatchSender` ⇒ no fast
    fire, next attempt at 20 ticks.
16. Production supplier wiring pin: answer only *some* positions ⇒ no fast fire; answer the
    rest ⇒ fire (guards a detached-supplier revert — the #71 wiring-pin pattern).
17. Halt precedence + recovery: armed + outstanding 0 + halted ⇒ no scan, no counter
    advance; the first tick AFTER recovery (queue just under halt) must NOT fast-fire
    (cond. 7 — this pin is load-bearing now that the gate is proportional).
18. `disconnect()` disarms.

**`ConfigValidationTest` (the repo's convention triplet — review addition):**
19. `enableAdaptiveScanCadenceDefaultsOn` (a silent default-flip must red CI).
20. `…RoundTripsThroughJson` (a key rename must not orphan saved kill-switch choices).

**Existing-test audit (review-resolved):** the 7 `advanceToOneCallBeforeScanFire` pins in
`LodRequestManagerTest` (:714, :828, :849, :870, :891, :913, :935) drive the production
scanner via `maybeScanOnce()` WITHOUT `tickScanPhase` — under v1's scanner-side arming they
would all red at call 5. Arming-in-the-manager fixes this at the root: those rigs never arm.
No `-1` assertion is weakened. While there, fix `maybeScanOnce()`'s stale javadoc ("a fired
scan declares nothing" is false at vd 64 — the corner annulus declares; cf. the
`fireScanAtOrigin` comment). `LodRequestManagerTickTest` survives as-is (review-verified:
its cadence pins never answer positions, so outstanding stays == lastSentCount > threshold).

**Gauntlet:** Tier 1 (`:fabric:test -x runGameTest -x runClientGameTest`), Tier 2
(`:fabric:runGameTest`), **Tier 3 (`:fabric:runClientGameTest` — review addition: the only
tier exercising the real client scanner end-to-end**, and where the converged-loop and
ingest-retry shapes would surface live), `:paper:test` (untouched, run once). Soak minimum:
`fresh-backfill`, `warm-rejoin`, `rate-limit-storm`, `disk-saturation`,
`generation-capacity-stress`, `dirty-broadcast`, **`dirty-range-filter`** (the exactly-flat
`requested_total` pin — the live converged-disarm proof), **`enabled-false`**,
**`teleport-prune`** (movement + cond. 6), **`clearcache-mid-session`**; full `all` if time
allows. The churn ceilings (`rate-limit-storm` superseded ≤ 800 vs measured 370) are
expected to hold via §6's silent-drop argument — if one reds, that argument failed:
re-examine before re-baselining, and any re-baseline must update the check, its selftest
fixtures, AND its derivation docstring together.

## 10. Rollout & documentation

Client-only behavior change, dark-launchable via the kill switch. Release-notes item
(user-facing: "distant terrain streams in continuously instead of once per second when the
server answers quickly"). Works against every v17+ server; v16 servers see zero change.

**Documentation debt this change creates (review-enumerated — all part of implementation):**
- `CLAUDE.md`: the "1 Hz / 20 ticks" scan-cadence assertions (want-set model bullet, flow
  paragraph, `SpiralScanner` component line — now "up to 4 Hz adaptive, 1 Hz fallback"),
  the `lss-client-config.json` key list (+`enableAdaptiveScanCadence`), the Tier-1 test
  inventory line for the new pins.
- `README.md` client-config table (if present): the new key.
- Comment sweep (wording-only): `SpiralScanner` class doc ("20-tick cadence" → adaptive),
  the Voxy-distance "once per second" comment (invocation-based, now up to 4 Hz),
  `AbstractPlayerRequestState` mailbox-overwrite ratio comment (1-in-20 → cadence-dependent),
  `ServerConfigBase` miss-memo "~1 Hz" comments, `LSSConstants.SEND_DEPARTURE_GRACE_MILLIS`
  rationale (re-derive at 250 ms floor — §5.7), `IncomingRequestRouter.resolvedAsDuplicate`
  termination comment, `docs/planning/duplicate-serve-grace.md` termination section,
  `docs/planning/miss-memo-design.md` if it states 1 Hz re-read cadence.
- Support lines: NOT backported in v1, and must be explicitly **kept-ours on the next
  support-branch merge from main** (the recurring merge set).
- The `missMemoTtlSeconds: 0` A/B baselines predate this change — cadence-driven re-read
  rates differ; note in any future memo A/B.

## 11. Watch items (live validation)

- **Feature inertness** (the review's headline risk): the §8 acceptance criterion —
  `fastScans` must climb during a live store-warm backfill. If ~0, suspect cond. 6 (prefix
  never confirmed under test conditions) or cond. 7 (decode depth above the ¼ line).
- **Residual walk cost**: a confirmed-but-holey prefix walk (straggler at ring 3, frontier
  at 200) still visits ~160k positions; §5.3's tightening bounds the rate. If live traces
  show render hitching, the escalation is a `lastWalkVisited` cost gate (count positions
  visited per walk, gate fast fires on it) — deferred from v1 as a second knob.
- **Upstream bandwidth** on many-client servers (§6's 4× C2S worst case).
- **A remote server as a walk-rate lever**: a hostile/instant-answer server can hold the
  client at max fast cadence — bounded by the floor, cond. 6, and convergence-disarm; the
  kill switch is the mitigation.
- Straggler-pinning at exactly-0% tails (§5.2): if live traces show 1 Hz pinning behind a
  few permanent stragglers with a large unserved frontier, revisit the deferred
  max(K, 5%) floor.

## 12. Non-goals (deliberate)

- No RTT-scheduled cadence (completion is the direct signal).
- No ring-confirmation trigger (subsumed by the outstanding threshold).
- No server-side hint/wire change of any kind.
- No absolute straggler floor (`max(K, 5%)`) in v1 (§11).
- No `lastWalkVisited` walk-cost gate in v1 (§11 — cond. 6 covers the reviewed shapes).
- No wall-clock ingest-failure strike floor (§5.6 — accepted bounded compression).
- No change to the backpressure halt, taper, budget, or any server-side pacing.

## 13. Review round (3× Opus, 2026-08-01 — all three: SOUND-WITH-FIXES, folded above)

- **Invariants lens:** all v17 model invariants survive; "declaring more often is
  semantically identical" verified server-side (mailbox/replace/grace/`hasPendingRequest`/
  memo/A1). MAJORs: the 7-test audit gap (§9) and the movement walk-cost falsification
  (§2 cond. 6, §5.5). Also: §5.3 rung attribution fix, grace termination restated
  structurally (§5.7), `InFlightTracker` third-consumer doc (§3), v16 gate strengthened
  (Tier B tracker-removal — §2), Folia hold note (§6), `missingVanilla` probe skip (§3).
- **Timing/edge lens:** threading contract verified (all tracker mutations main-thread via
  `client.execute`); v16 gate placement verified. MAJORs: strict-zero pressure gate would
  make the feature inert for data backfill (§2 cond. 7), movement walk cost (cond. 6),
  invariant #3 falsehood (§4.3 restated). Structural fix adopted: arming in `tickScanPhase`
  beside the tracker replace (§3). Plus: `disconnect()` disarm, sessionConfig null-guard,
  §5.9 unreachable-path rewrite.
- **Tests/ops lens:** named the 7 concrete test breaks; law A1 verified structurally
  rate-invariant; `LodRequestManagerTickTest` verified surviving. MAJORs: inert-gate
  acceptance criterion (§8), Tier 3 missing from the gauntlet (§9), `rate-limit-storm`
  ceiling evidence requirement (§9), config pin triplet (§9). Plus: doc-debt enumeration
  (§10), C2S upstream cost (§6), benchmark/RTT comparison hygiene (§8), A1 flake-exposure
  note (§6), halt-recovery pin now load-bearing (§9 #17).

## 14. Implementation review round (3× Opus on commit 6f2037b — all three: SHIP-WITH-FIXES, folded)

Shared MAJOR (all three reviewers independently): the walk-cost gate's `confirmedRing > 0`
term structurally cannot see the ACTIONABLE-RETRY prefix reset — it happens inside `scan()`
after the predicate read the field, and every walk re-derives `confirmedRing >= 1` (ring 0
is empty). An ingest-failure burst (which also drains the awaiting set, enabling the
trigger) therefore drove full from-ring-0 re-walks at 4 Hz. Fixed: `fastRescanDue` now
consults `hasActionableRetries` directly (§2 cond. 6), pinned by
`actionableRetryMarksHoldFastFiresLikeAnyPrefixInvalidation`.

Also folded:
- **Byte pipe added to the pressure gate** (§2 cond. 7) — the binding halt term for real
  terrain columns was ungated; `columnQueueBytes`/`byteHaltThreshold()` now thread through
  `tickScanPhase` → `maybeScan`, pinned by `byteQueueGateBlocksAtAQuarterOfItsHaltThreshold`.
- **`sendClearBatch` disarms** — the one declaration path bypassing `tickScanPhase` left
  "lastSentCount == what was declared" false and invited a fast re-declare storm out of a
  halt (coincidence-masked until the byte-gate case).
- **`fastScans` exported in the soak client snapshot** (`scan.fast`, additive) so the §8
  acceptance criterion is machine-checkable in soak runs.
- **Test-quality round:** the converged-disarm is now pinned through the PRODUCTION arming
  path (`convergedFastWalkDisarmsThroughTheManagerArming` — an `if (scanned > 0)` guard on
  `noteDeclared` was suite-green before); the kill switch's production config binding is
  pinned (`killSwitchBindsThroughTheProductionConfigRead`); the `resetScanCounter` disarm
  pin was de-vacuumed (its second window isolates the disarm from the prefix gate); the
  three constants are numerically pinned; manager-level adaptive rigs pin the seam ON
  (isolating them from the developer's local gitignored config file).
- Comment/doc accuracy: the 39-tick bound formula (§4.3), the Voxy-staleness comment
  (invocation-based, per its own pinning test), `missingVanilla` staleness under sustained
  fast cadence, miss-memo-design.md cadence + A/B-baseline note, the two service-file
  mailbox-comment twins, CLAUDE.md churn wording ("threshold-dependent", not "structural")
  and Tier-1 count.

Still open when this round closed (deliberate): the §9 soak gauntlet + the §8 live
`fastScans` acceptance measurement on a store-warm backfill — evidence to gather before
merge, not code changes.

## 15. Soak evidence (2026-08-01, post-fix commit)

Four load-bearing scenarios, all green on the first run:
- **`fresh-backfill`** — all laws pass; `scan.fast == 1` at end: gen-bound backfill keeps
  outstanding high and the fast path structurally inactive, exactly as designed (the
  server-side profile is unchanged where the churn ceilings were calibrated).
- **`warm-rejoin`** — all laws pass, 51/51 quiescent client windows. Run 2 (warm resync):
  `scan.fast == 3` and then FLAT across every remaining snapshot. At soak scale the whole
  warm disc is ~4 batches, so effectively every post-join scan was a fast fire (the
  acceptance criterion §8 — the feature engages in the warm case), and the flat counter
  across 51 quiescent snapshots is the live proof of the converged disarm (§5.1): no
  250 ms walk loop exists at convergence.
- **`rate-limit-storm`** — passed at `superseded == 742` vs the 800 ceiling (1 Hz-era
  measurement: 370). The plan-review M4 prediction landed: the converging tail re-declares
  at up to 4 Hz. Re-baselined per §9's rule (check + selftest fixtures + derivation
  docstring together): ceiling 1500 = ~2× the new measurement, storm peak unchanged
  (outstanding stays high there, cadence stays 1 Hz). `--selftest` 144 cases green.
- **`dirty-range-filter`** — passed: `requested_total` exactly flat through the far-edit
  suppression window (the zero-tolerance live pin of converged silence), `scan.fast == 5`.

---

## 13. AMENDMENT (2026-08-01) — cond. 6 replaced by a predicted walk-cost gate

This section supersedes cond. 6 wherever §2, §5.5, §11 and §12 describe it. Nothing else
in the design changes: the 250 ms floor, the ≥95%-answered threshold and its geometric
tightening, the ¼-halt pressure gates, the v16 exclusion, the arming/disarm family and
`enableAdaptiveScanCadence` are all untouched.

**What changed.** Cond. 6's first half was `confirmedRing > 0`. §5.5 ("Movement
(review-corrected: the most expensive case, now gated)") adopted it on the reasoning that a
post-`recenter()` walk restarts at ring 0 and is therefore expensive. That reasoning was
**analytic**; a live trace has now measured it.

**What the measurement showed** (docs/planning/elytra-chunk-wall-investigation-2026-08-01.md
§8.6.3, 26 s of elytra flight at ~33 blocks/s):

- `recenter()` fires on every chunk-boundary crossing — **2.76 Hz** against a 1 Hz scan — and
  nothing re-derives the prefix until the next *walk*. So the first crossing after each scan
  zeroed the term and the fast path was dead until the next scan.
- Result: **exactly 1.000 s scan gaps for 23 consecutive seconds of flight**, while the same
  client ran 2–3 Hz standing still. Cond. 6 was not gating expensive walks during movement;
  it was **structurally inert for the entire duration of any sustained movement**.
- The walk it was refusing costs `4·75·76 = 22,800` iterations ≈ 0.9 ms, with **fps flat at
  60** throughout. §5.5's "the most expensive case" was two orders of magnitude off for the
  regime that actually matters.

**The replacement.** `predictedWalkCost() <= FAST_RESCAN_MAX_WALK_COST` (65,536), where the
prediction is `4·(s·(s+1) − confirmedRing·(confirmedRing−1))` — the ring-sum over the span
the *next* walk will cover, from two fields the scanner already keeps. `s` is `scanRing` only
for a budget-truncated walk; otherwise it is the effective LOD distance, because an untruncated
walk iterates every satisfied ring out to it. No new state, no counter in the hot loop.

> **Coverage limit (recorded 2026-08-02, v0.9.0 review — decided: accept and document).**
> `recenter()` zeroes `confirmedRing` on every chunk crossing and nothing re-derives it until
> the next walk, so under sustained movement the prediction reduces to `4·s(s+1)`. Against the
> 65,536 budget that admits `s ≤ 127` (`4·127·128 = 65,024`) and refuses `s = 128`
> (`4·128·129 = 66,048`). On the shipped `lodDistanceChunks = 256` the fast cadence therefore
> covers rings 0–127 — 65,024 of the disc's 263,168 positions, **about a quarter** — and a
> MOVING client returns to 1 Hz once the frontier passes ring 128. Stationary clients are
> unaffected: `confirmedRing` survives, so the span stays narrow at any frontier depth.
>
> This makes the feature a **partial** fix to the elytra chunk wall. It is the conservative
> direction §8.6.3 of the elytra investigation argued for — lifting the flight regime toward
> the stationary 2–3 Hz would put it at 50–75 MB/s and walk back toward the wall — but it was
> undocumented and discontinuous at a specific ring, and the in-code rationale ("a disc already
> satisfied out to the LOD distance has little left to fetch") describes the *converged* case,
> not this one: here the interior is satisfied to the frontier with ~110k positions still
> wanted beyond it. Consequences: release notes must not claim "4 Hz while flying" without
> qualification, and raising the constant only moves the cliff — extending coverage means
> decoupling the prefix from `recenter()`, a separate design change with the throughput
> consequence above attached.

Three things this gets right that a `lastWalkVisited` counter (§11's proposed escalation,
§12's listed non-goal) would not:

1. **It predicts rather than remembers.** A remembered cost gates the next walk with the last
   walk's price, and those differ *exactly* at the movement transition — the recorded walk
   started at the frontier, the next starts at ring 0. A remembered gate admits one
   full-price walk after every prefix collapse.
2. **It keeps the two prefix-reset shapes symmetric.** `hasActionableRetries` resets the
   prefix *inside* `scan()`, so no prefix-derived value can gate it and it keeps its own
   term. A remembered cost would have let movement resets run fast while retry resets — the
   same "next walk starts at ring 0" shape — stayed at 1 Hz.
3. **It costs nothing to compute.**

**Calibration.** A walk to ring R costs `4R(R+1)`, *not* `4R²` — an error that made the
first draft of this change pick 262,144 and thereby refuse a full walk at the default
`lodDistanceChunks=256` (263,168), the live server's exact setting. 65,536 gives the
measured flight walk ~2.9× headroom and refuses the warm full-256-disc walk by 4×. The
budget is a **frame** budget, not a per-second one: an admitted walk spends its whole cost
inside one client tick (~65k × 50–100 ns ≈ 3–7 ms), and the 250 ms floor bounds that to 4×/s.

**Refusing the expensive case is deliberate policy**, now stated: a disc already satisfied
out to the LOD distance is both expensive to walk *and* has little left to fetch, so 1 Hz is
the right cadence there. This is never a regression — movement rides 1 Hz unconditionally
today.

**§12's non-goal is retired**, and §11's "escalation if live traces show render hitching" was
invoked for a different reason than its stated trigger: the trace showed **no hitching**
(fps 60 flat). The escalation is being taken because the v1 gate proved *inert*, not because
it proved *insufficient*.

Pins: `SpiralScannerTest.cheapPrefixInvalidationNoLongerHoldsTheFastPath` (the regression),
`expensivePrefixInvalidationStillRidesTheFallback` (the retained guard),
`walkCostIsPredictedForTheNextWalkNotRememberedFromTheLast` (finding 1 above),
`resetRestoresTheWalkCostPredictionWithTheRestOfTheSessionState`, and the constant in
`adaptiveCadenceConstantsArePinned`.
