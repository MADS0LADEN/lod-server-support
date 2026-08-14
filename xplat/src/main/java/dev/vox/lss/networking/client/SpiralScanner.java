package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Expanding Chebyshev ring scanner that produces the client's want-set: every position it
 * still wants, closest-first, written straight into {@link LodRequestManager}'s send buffers
 * and shipped whole in the same tick. Owns the scan policy — the adaptive cadence (a 20-tick
 * fallback plus the completion-triggered fast re-scan, see {@link #fastRescanDue}) and the
 * budget with its queue-pressure scale (the vanilla-load scale is retired: server-side
 * priority/throttling owns that protection under v17).
 *
 * <p>The scan does NOT suppress in-flight positions: under want-set semantics the server may
 * silently supersede any not-yet-admitted ask, and the periodic re-declare is the only thing
 * that heals it. An awaited position is therefore an ordinary unsatisfied want-set member,
 * which also means it blocks ring confirmation until its data actually lands. The adaptive
 * fast trigger reads the awaiting-set SIZE as a cadence input — it never filters what the
 * walk declares.
 */
class SpiralScanner {

    /**
     * Adaptive cadence (docs/planning/adaptive-scan-cadence-design.md): minimum ticks
     * between consecutive fires — 5 ticks = 250 ms, capping the fast cadence at 4 Hz. Bounds
     * the worst-case C2S declaration rate (and its ~12.8 KB/batch upstream cost) and the
     * render-thread walk rate.
     */
    static final int FAST_RESCAN_MIN_INTERVAL_TICKS = 5;
    /**
     * Fast fires require ≥95% of the last declared batch answered:
     * {@code outstanding <= lastSentCount / 20}. Integer math — declares under 20 degenerate
     * to "outstanding == 0", the strictest (correct) form for tiny tails. Also bounds the
     * redundant in-flight re-asks a fast batch can carry at 5%, and makes straggler chatter
     * self-limiting: a fast walk that declares only the stragglers shrinks the next
     * threshold 20-fold (geometric tightening).
     */
    static final int FAST_RESCAN_OUTSTANDING_DIVISOR = 20;
    /**
     * Fast fires require MILD downstream pressure at most: each pipe signal below 1/4 of its
     * halt threshold (decode queue &lt; 1500 columns AND &lt; 48 MiB queued bytes — the byte
     * halt is the one that binds for real terrain columns — and consumer ingest backlog
     * &lt; 1536 sections at the production constants). Deliberately proportional, NOT strict
     * zero: a
     * received column leaves the awaiting set and enters the decode queue in the same
     * network-handler statement pair, so at the instant outstanding reaches 5% the queue
     * holds that batch's peak — a strict-zero gate would suppress the fast path in exactly
     * the data-bearing warm-backfill case it exists for. Under a sustained decode
     * bottleneck the cadence bang-bangs around this line, i.e. rate-matches the decoder;
     * deeper pressure (including the tick recovering out of a backpressure halt) stays at
     * the 1 Hz fallback where the budget taper + halt own regulation.
     */
    static final int FAST_RESCAN_PRESSURE_DIVISOR = 4;
    /**
     * Fast fires require the NEXT walk to be cheap: at most this many ring positions
     * examined (see {@link #predictedWalkCost()}). Replaces the original
     * {@code confirmedRing > 0} term, which was a PROXY for walk cost and — because
     * {@link #recenter()} zeroes the prefix on every chunk-boundary crossing while nothing
     * re-derives it until the next walk — measured movement instead. At 33 blocks/s
     * crossings run 2.76 Hz against 1 Hz scans, so the proxy was structurally inert for the
     * whole of any sustained flight: the elytra trace of 2026-08-01 measured 2–3 Hz standing
     * still and exactly 1.000 s gaps for 23 consecutive seconds of flight
     * (docs/planning/elytra-chunk-wall-investigation-2026-08-01.md §8.6.3).
     *
     * <p>Calibration: a walk to ring R costs {@code 4R(R+1)} — NOT 4R² — so the measured
     * flight walk (frontier ring 75) is 22,800 and a full walk at the default 256 LOD
     * distance is 263,168. 65,536 gives the flight case ~2.9× headroom while refusing the
     * warm full-disc walk by 4×. The budget is a FRAME budget, not a per-second one: an
     * admitted walk spends its whole cost inside one client tick (~65k × 50–100 ns ≈
     * 3–7 ms), and the 250 ms floor lets that happen at most 4×/s.
     *
     * <p>Refusing the expensive case is deliberate policy, not a limitation: a disc already
     * satisfied out to the LOD distance is both expensive to walk AND has little left to
     * fetch, so the 1 Hz fallback is right there. It is never a regression — movement rides
     * 1 Hz unconditionally today.
     */
    static final int FAST_RESCAN_MAX_WALK_COST = 65_536;

    private SessionConfigS2CPayload sessionConfig;

    private int confirmedRing = 0;
    private int scanRing = 0;
    /**
     * The vanilla-view exclusion radius the last walk ran with, -1 = no walk yet. A SHRINK
     * (render distance lowered) turns positions inside the old exclusion — which confirmed
     * their rings without ever being declared — into LOD-needing positions below the
     * confirmed prefix, structurally unreachable until movement; the walk resets the prefix
     * once per shrink (see {@link #scan}). A grow needs nothing: newly excluded positions
     * are skipped without breaking confirmation.
     */
    private int lastExclusionRadius = -1;
    /**
     * Did the last walk stop early because the budget filled? Decides which end
     * {@link #predictedWalkCost()} measures to: a truncated walk stops at {@link #scanRing},
     * an untruncated one iterates every ring out to the LOD distance. Starts false so a
     * never-walked scanner predicts the full disc — fail-closed.
     */
    private boolean lastWalkTruncated = false;
    private int scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1; // starts at max so first scan fires immediately on join
    private int missingVanillaChunks = Integer.MAX_VALUE;

    // --- Adaptive-cadence state (see fastRescanDue) ---
    /**
     * The armed state: the count of the last want-set batch the manager DECLARED (tracker
     * replaced + send attempted). Written only by {@link #noteDeclared} from
     * {@link LodRequestManager#tickScanPhase} beside {@code tracker.replaceWith} — so
     * "lastSentCount == what the awaiting set was replaced with" holds by construction —
     * and re-zeroed by the send-failure catch, {@code disconnect()}, {@link #reset()} and
     * {@link #resetScanCounter()}. 0 = disarmed: a converged client (0-count walk) must
     * fall back to the 1 Hz re-walk, never a 250 ms walk loop, and a dying connection must
     * not retry at 4 Hz. Rigs that drive {@link #maybeScan} directly without
     * {@code tickScanPhase} never arm and see pre-adaptive behavior bit-identically.
     */
    private int lastSentCount;
    /** The awaiting-set size ({@code tracker::size} in production; main-client-thread only).
     *  Null = fast path off (bare test rigs). */
    private IntSupplier outstandingSupplier;
    /** Config seam ({@code enableAdaptiveScanCadence} kill switch) — injectable so tests
     *  don't mutate the global CONFIG (the ingestBacklogSupplier pattern). */
    BooleanSupplier adaptiveCadenceEnabled =
            () -> LSSClientConfig.CONFIG.enableAdaptiveScanCadence;
    /**
     * Manual column-rate cap seam (docs/planning/client-column-rate-cap-design.md), the
     * {@link #adaptiveCadenceEnabled} pattern so tests don't mutate the global CONFIG.
     * {@code <= 0} = off, bit-identical to pre-cap behavior. A positive R clamps the
     * want-set budget to {@code min(WANT_SET_BUDGET, R)} (bounds the burst any one
     * declaration can trigger) and adds the size-weighted spacing gate to
     * {@link #fastRescanDue} (bounds the sustained rate: after declaring N positions the
     * next FAST fire waits at least {@code 20*N/R} ticks — each batch pre-pays its own
     * interval, a stateless token bucket, so rate {@code <= R/sec} by construction). The
     * 20-tick fallback is deliberately not consulted here: re-declaration is the want-set's
     * only self-heal and must stay un-gateable, and with the budget clamped to R the
     * fallback alone stays {@code <= R/sec} anyway.
     */
    IntSupplier columnRateCap = () -> LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
    /**
     * The BUDGET-CLAMP half of the cap seam (the transfer governor's seam split —
     * adaptive-transfer-rate-plan.md review M2). Defaults to whatever
     * {@link #columnRateCap} supplies, so the manual knob keeps its shipped
     * single-value shape (budget = R, spacing = R → 1 Hz full batches). The governed
     * path supplies {@code ceil(R/4)} here while {@link #columnRateCap} carries R:
     * the spacing gate then equilibrates at the 5-tick floor — 4 Hz quarter-batches,
     * burst ≈ a quarter-second of the governed rate instead of a full second.
     */
    IntSupplier columnBurstCap = () -> this.columnRateCap.getAsInt();
    private boolean lastScanWasFast;
    private long fastScans; // session-scoped diagnostic counter (reset() zeroes it)
    /** Ticks the rate cap's spacing gate refused when every OTHER fast-fire condition had
     *  passed — "the knob is binding" evidence for weak-client reports (surfaced in the
     *  client diag Budget line). Session-scoped like {@link #fastScans}; reset() zeroes it. */
    private long rateGated;

    // Last scan budget tracking
    private int lastBudget;
    private int lastQueued;

    // Cached Voxy view distance — refreshed every 20th getEffectiveLodDistance()
    // INVOCATION, and the walk is not the dominant caller: getPruneDistance() reads it per
    // received column and per movement crossing, so the effective window is sub-second
    // whenever columns are arriving (pinned by voxyDistanceRefreshIsInvocationCountBased…).
    // The lookup is a cheap MethodHandle call, so fresher is fine.
    private int cachedVoxyDistance = -1; // -1 = not present
    private int voxyDistanceStaleness = 0;

    /** Set once per session, alongside {@link #reset()}. */
    void setConfig(SessionConfigS2CPayload sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

    /**
     * Advance the scan cadence and, when it fires with a nonzero budget, walk the rings
     * and write the complete want-set (closest-first) into {@code posOut}/{@code tsOut}.
     *
     * @param missingVanilla evaluated only on PERIODIC fires (diagnostics only — fast fires
     *        keep the last value rather than 4×-ing an O((2·vd+1)²) hasChunk sweep)
     * @return -1 when no walk happened this tick (cadence not fired); otherwise the
     *         number of want-set entries written
     *         (0 = walked and found nothing — the converged case; the caller must then
     *         send NOTHING but still replace its awaiting set)
     */
    int maybeScan(int playerCx, int playerCz, int viewDistance,
                  int columnQueueSize, int columnQueueHaltThreshold,
                  long columnQueueBytes, long columnQueueByteHaltThreshold,
                  int ingestBacklogSections, int ingestBacklogHaltThreshold,
                  IntSupplier missingVanilla,
                  ColumnStateMap columns,
                  long[] posOut, long[] tsOut) {
        int ticksSinceFire = ++this.scanTickCounter;
        boolean fast = ticksSinceFire < LSSConstants.TICKS_PER_SECOND;
        if (fast && !fastRescanDue(ticksSinceFire, playerCx, playerCz, viewDistance, columns,
                columnQueueSize, columnQueueHaltThreshold,
                columnQueueBytes, columnQueueByteHaltThreshold,
                ingestBacklogSections, ingestBacklogHaltThreshold)) {
            return -1;
        }
        // Either fire resets the counter: the 20-tick fallback measures from the LAST batch
        // ("more than 1 s since the last declaration ⇒ fire regardless"). A position a fast
        // walk omitted (budget/center shift under movement) waits at most
        // 2*TICKS_PER_SECOND - 1 = 39 ticks for its re-declaration when ONE fast fire
        // intervenes; a chain of budget-truncated fast walks can extend that, all
        // self-healing by re-declaration.
        this.scanTickCounter = 0;

        // "Last" can be arbitrarily old under SUSTAINED fast cadence (a long warm backfill
        // never takes the periodic branch) — acceptable for a diagnostic-only stat.
        if (!fast) this.missingVanillaChunks = missingVanilla.getAsInt();

        // Compute scan budget: base × pressure-scale. The base is
        // the ONE want-set budget — a constant; no client budget derives from any server cap
        // (server-owned generation). Raising it buys nothing: the window self-throttles to
        // the serve rate (as heads resolve they classify SATISFIED and drop out), so a bigger
        // window only inflates duplicate-skip traffic. WantSetBudgetInvariantTest pins it
        // above the worst-case in-flight set with frontier headroom and inside one wire batch.
        //
        // Two independent pressure factors, composed by MIN (not multiplied — both gauge the
        // same downstream pipe, so multiplying would double-count): LSS's own decode queue,
        // and the consumer-reported ingest backlog (issue #71 — Voxy's unbounded ingest queue
        // is invisible to the decode-queue signal; <=0 means no signal and leaves the budget
        // untouched). Linear taper against each signal's halt threshold: the budget shrinks
        // until arrivals match the consumer's real drain rate — a proportional controller
        // whose equilibrium IS rate-matching (docs/planning/ingest-backpressure-design.md).
        int budget = LSSConstants.WANT_SET_BUDGET;
        // Column-rate cap, burst half (client-column-rate-cap-design.md; the burst/
        // sustained SEAM SPLIT is adaptive-transfer-rate-plan.md review M2): at most
        // burstCap columns outstanding, so at most ~burstCap can arrive inside one
        // declaration interval. MIN-composes with the pressure taper below — the scale
        // applies to the already-clamped base, which is <= both, the same composition
        // rule as the taper's own two factors. The sustained-rate half lives in
        // fastRescanDue's spacing gate (columnRateCap).
        int burstCap = this.columnBurstCap.getAsInt();
        if (burstCap > 0) {
            budget = Math.min(budget, burstCap);
        }
        float scale = 1f;
        if (columnQueueSize > 0) {
            scale = Math.min(scale, 1f - (float) columnQueueSize / columnQueueHaltThreshold);
        }
        if (ingestBacklogSections > 0) {
            scale = Math.min(scale, 1f - (float) ingestBacklogSections / ingestBacklogHaltThreshold);
        }
        if (scale < 1f) {
            budget = Math.max(1, Math.round(budget * Math.max(0f, scale)));
        }
        // The vanilla-load budget scale is GONE (2026-07-17, user call): it was client-side
        // triage of SERVER resources — v17 moved all of that server-side (BACKGROUND/LOW
        // read+gen priority, the adaptive throttle, headroom gates), and its only observable
        // effect was silently stopping LOD during fast travel, the same class of starvation
        // as the removed movement cadence debounce. missingVanillaChunks is still counted
        // for /lss diag and the trace — as a diagnostic, not a lever.
        // The want-set must fit one wire batch: replace semantics tear across frames.
        budget = Math.min(budget, Math.min(LSSConstants.MAX_BATCH_CHUNK_REQUESTS, posOut.length));

        if (budget <= 0) return -1;

        // Counted after the budget guard: a non-walk must not read as a fast scan (the
        // guard is unreachable in production — the taper floors at 1 and the buffers are
        // batch-cap length — but a zero-length test buffer reaches it).
        this.lastScanWasFast = fast;
        if (fast) this.fastScans++;

        return scan(playerCx, playerCz, viewDistance, columns, posOut, tsOut, budget);
    }

    /**
     * The adaptive-cadence fast trigger (docs/planning/adaptive-scan-cadence-design.md):
     * between 20-tick fallback fires, fire early — at most every
     * {@link #FAST_RESCAN_MIN_INTERVAL_TICKS} — once the last declared batch is ≥95%
     * answered and the downstream pipeline is at most mildly loaded. Conditions cheap-first;
     * the supplier read comes last so non-firing ticks stay O(1).
     *
     * <p>The v16 gate is non-optional, not merely polite: besides the legacy server's real
     * rate limiter, Tier B's onColumnNotGenerated removes a bounced position from the
     * awaiting set before its transient-heal early return, so every legacy gen-slot bounce
     * drops outstanding — a v16 session would fast-fire on exactly that churn loop and
     * hammer the old server's gen slots at 4 Hz. ClientSessionGate admits only the current
     * and the v16 protocol versions, so {@code != 16} is exact today and stays conservative
     * if an intermediate legacy dialect ever lands.
     *
     * <p>The walk-cost gate has two halves, because the prefix resets in two tempos. The
     * {@link #predictedWalkCost()} term sees the SYNCHRONOUS invalidations — movement
     * ({@link #recenter}) and dirty re-opens ({@link #resetConfirmedRing}) zero
     * {@code confirmedRing} before the next tick's predicate, so the prediction becomes the
     * full from-ring-0 re-walk those force. The {@code hasActionableRetries} term sees the
     * IN-WALK one: an actionable retry mark resets the prefix inside {@code scan()} (after
     * this predicate already ran) and the walk always re-derives {@code confirmedRing >= 1}
     * (ring 0 is empty), so no prefix-derived value can gate it. Either way a zero-prefix
     * walk re-walks from ring 0 (the render-thread-hitch shape documented at the 2048
     * ceiling above) — but only walks that are actually EXPENSIVE stay at the 1 Hz
     * fallback now, rather than every reset regardless of cost.
     *
     * <p>The predecessor of the cost term was {@code confirmedRing > 0}, which conflated
     * "the prefix was invalidated" with "the walk is expensive". Those coincide for a
     * stationary client and diverge completely for a moving one, where the prefix is
     * invalidated ~3×/s and the resulting walk still costs ~22,800 iterations (~0.9 ms,
     * fps flat at 60 in the measured trace). See {@link #FAST_RESCAN_MAX_WALK_COST}. This
     * reverses adaptive-scan-cadence-design.md §5.5 — a review-round decision taken on an
     * ANALYTIC cost estimate; the trace measured it.
     *
     * <p>Caller contract: the halt thresholds should be positive (production passes the
     * constants). A zero threshold cannot throw — the divisions are by the constant
     * {@link #FAST_RESCAN_PRESSURE_DIVISOR}, not by the threshold — it simply fails closed
     * on all three pipes, which is the safe direction. (The javadoc previously claimed a
     * divide-by-zero here; the only threshold-denominated division is the taper in
     * {@code maybeScan}, and that yields {@code -Infinity} → a budget of 1, also no throw.)
     */
    private boolean fastRescanDue(int ticksSinceFire, int playerCx, int playerCz, int viewDistance,
                                  ColumnStateMap columns,
                                  int columnQueueSize, int columnQueueHaltThreshold,
                                  long columnQueueBytes, long columnQueueByteHaltThreshold,
                                  int ingestBacklogSections, int ingestBacklogHaltThreshold) {
        if (ticksSinceFire < FAST_RESCAN_MIN_INTERVAL_TICKS) return false;
        if (!this.adaptiveCadenceEnabled.getAsBoolean()) return false;
        if (this.lastSentCount <= 0) return false; // disarmed: converged, send-failed, or never declared
        if (this.outstandingSupplier == null) return false;
        if (this.sessionConfig == null
                || this.sessionConfig.protocolVersion() == LSSConstants.V16_COMPAT_PROTOCOL_VERSION) {
            return false;
        }
        if (predictedWalkCost() > FAST_RESCAN_MAX_WALK_COST) return false;
        // The F1 shrink reset is an IN-WALK prefix invalidation like hasActionableRetries
        // below: scan() zeroes the prefix AFTER this predicate evaluated predictedWalkCost
        // off the still-high confirmedRing, so without this rung the shrink tick could ride
        // a fast fire straight into the full from-ring-0 walk the cost gate exists to
        // refuse (three-lens review, correctness MINOR — dynamic-view-distance servers
        // shrink repeatedly under load).
        if (this.lastExclusionRadius >= 0 && viewDistance < this.lastExclusionRadius) return false;
        if (columnQueueSize >= columnQueueHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR) return false;
        if (columnQueueBytes >= columnQueueByteHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR) return false;
        if (ingestBacklogSections >= ingestBacklogHaltThreshold / FAST_RESCAN_PRESSURE_DIVISOR) return false;
        if (columns.hasActionableRetries(playerCx, playerCz, viewDistance)) return false;
        if (this.outstandingSupplier.getAsInt()
                > this.lastSentCount / FAST_RESCAN_OUTSTANDING_DIVISOR) {
            return false;
        }
        // Manual column-rate cap, sustained half: the last batch of N positions pre-pays a
        // 20*N/R-tick interval before the next FAST fire (the 5-tick floor above still binds
        // for small tails, so the converging-tail sparkle survives; a full 800 batch at
        // R=3200 spaces to exactly the floor — today's behavior IS the R=3200 point). LAST
        // in the ladder deliberately: rateGated then counts exactly the ticks where the cap
        // was the binding refusal, and the supplier read costs only near-fire ticks. Long
        // math is belt-and-braces (max real product ~2M fits int comfortably).
        int cap = this.columnRateCap.getAsInt();
        if (cap > 0 && (long) ticksSinceFire * cap
                < (long) LSSConstants.TICKS_PER_SECOND * this.lastSentCount) {
            this.rateGated++;
            return false;
        }
        return true;
    }

    /**
     * Ring positions the NEXT walk will examine, from the two fields that already describe
     * it. It starts at {@link #confirmedRing} and runs to {@code s}, which is
     * {@link #scanRing} only for a budget-TRUNCATED walk; otherwise the walk iterates every
     * ring out to {@link #getEffectiveLodDistance} (see the comment in the body — predicting
     * off {@code scanRing} unconditionally under-reports by three orders of magnitude on a
     * warm disc). Ring {@code r} holds {@code 8r} positions, so the span costs
     * {@code Σ_{r=c}^{s} 8r = 4·(s(s+1) − c(c−1))} — note {@code c(c−1)}, not {@code c(c+1)}:
     * the loop starts AT {@code confirmedRing}. Getting that term wrong once already cost a
     * mis-sized budget constant.
     *
     * <p><b>Coverage limit — deliberate, and load-bearing for what the release notes may
     * claim.</b> {@link #recenter} zeroes {@code confirmedRing} on every chunk crossing and
     * nothing re-derives it until the next walk, so under sustained movement {@code c} is 0
     * and this reduces to {@code 4·s(s+1)}. Against
     * {@link #FAST_RESCAN_MAX_WALK_COST} = 65536 that admits {@code s ≤ 127}
     * (4·127·128 = 65024) and refuses {@code s = 128} (4·128·129 = 66048). So on the shipped
     * {@code lodDistanceChunks} = 256 the fast cadence covers rings 0–127 — 65024 of the
     * disc's 263168 positions, about a quarter — and a MOVING client falls back to 1 Hz once
     * the frontier passes ring 128. Stationary clients are unaffected ({@code confirmedRing}
     * survives, so the span stays narrow at any depth). This is a partial fix to the elytra
     * chunk wall, not a complete one, and it is the conservative direction the investigation
     * argued for: docs/planning/elytra-chunk-wall-investigation-2026-08-01.md warns that
     * lifting the flight regime toward the stationary 2–3 Hz would put it at 50–75 MB/s and
     * walk back toward the wall. Raising the constant only moves the cliff; extending the
     * coverage means decoupling the prefix from {@code recenter}, which is a separate design
     * change with that throughput consequence attached.
     *
     * <p>Deliberately a PREDICTION, not a measurement of the last walk. The two differ
     * exactly where this gate matters: after {@link #recenter} the next walk restarts at
     * ring 0 while the last one started at the frontier, so a remembered cost would admit
     * one full-price walk after every prefix collapse — and would split two
     * identically-shaped events, letting movement resets run fast while
     * {@code hasActionableRetries} resets (also "next walk starts at ring 0") stay at 1 Hz.
     *
     * <p>Costs nothing in the walk itself: no counter in the hot loop. The long math and
     * the saturation are belt-and-braces — {@code scanRing} is clamped to the LOD distance,
     * so the worst real product (~16.8M at the 2048 ceiling) fits an int comfortably.
     */
    int predictedWalkCost() {
        if (this.sessionConfig == null) return Integer.MAX_VALUE; // fail closed
        // WHERE the walk stops is not scanRing. scan()'s ONLY early exit is the budget
        // `break outer`; without it the loop runs all the way to lodDistance, and scanRing
        // is merely the outermost ring that QUEUED something. Those coincide only for a
        // truncated walk. On a warm disc — the shipped server's own regime — a moving
        // client finds work solely in the trailing view-edge crescents near ring
        // ~viewDistance, so scanRing stays tiny while the walk still iterates every
        // satisfied ring out to lodDistance. Predicting off scanRing there under-reports by
        // three orders of magnitude and admits exactly the walk this gate exists to refuse.
        int s = this.lastWalkTruncated ? this.scanRing : getEffectiveLodDistance();
        int c = this.confirmedRing;
        if (s < c) return 0;
        // Σ 8r for r in [c, s] — the loop starts AT confirmedRing, so the lower term is
        // c(c-1), not c(c+1).
        long cost = 4L * ((long) s * (s + 1) - (long) c * (c - 1));
        return cost <= 0 ? 0 : (int) Math.min(cost, Integer.MAX_VALUE);
    }

    /**
     * Arm/disarm the fast cadence with the count the manager just declared (awaiting set
     * replaced + send attempted). See {@link #lastSentCount} for the contract; called with
     * 0 by the converged walk, the send-failure catch, and {@code disconnect()}.
     */
    void noteDeclared(int count) {
        this.lastSentCount = count;
    }

    /** Production: {@code tracker::size}. Absent (null) in bare rigs ⇒ fast path off. */
    void setOutstandingSupplier(IntSupplier supplier) {
        this.outstandingSupplier = supplier;
    }

    /**
     * Scans expanding Chebyshev rings for positions that need requesting.
     * Skips fully-confirmed rings (all positions satisfied) without spending budget,
     * and continues across multiple rings until budget is exhausted.
     */
    private int scan(int playerCx, int playerCz, int viewDistance,
                     ColumnStateMap columns,
                     long[] posOut, long[] tsOut, int budget) {
        int exclusionRadius = viewDistance;
        int lodDistance = getEffectiveLodDistance();

        int count = 0;

        int[] chunkCoords = new int[2];
        int localScanRing = -1;
        int queued = 0;
        boolean truncated = false;

        // A SHRUNK exclusion radius (render distance lowered) resets the prefix once: the
        // rings between the new and old radius confirmed while vanilla rendered them and
        // would otherwise never be walked again for a stationary player (2026-08-05 review
        // F1). Cheap — fires once per change, and the shrink walk has real work to declare.
        if (this.lastExclusionRadius >= 0 && exclusionRadius < this.lastExclusionRadius) {
            this.confirmedRing = 0;
        }
        this.lastExclusionRadius = exclusionRadius;

        // Only an ACTIONABLE retry mark (outside the vanilla-view exclusion) resets the
        // confirmed ring. A mark whose position slipped INSIDE the exclusion after it was
        // set is unconsumable — an excluded position is never declared, so no terminal
        // answer ever consumes it — and letting it reset the ring forced a full-distance
        // re-walk EVERY scan for as long as the player lingered (negligible at the default
        // distance, a render-thread hitch per scan at the 2048 ceiling). The parked mark
        // stays; movement recenters the walk from ring 0 anyway, so once the exclusion
        // moves off the position the mark is reachable again. (A mark beyond a SHRUNK
        // lodDistance is a separate, deliberately-unhandled flavor — it still resets the
        // ring every scan, bounded by the smaller walk it forces. An exclusion-radius
        // shrink is handled above.)
        if (columns.hasActionableRetries(playerCx, playerCz, exclusionRadius)) {
            this.confirmedRing = 0;
        }

        int localConfirmedRing = this.confirmedRing;

        outer:
        for (int r = localConfirmedRing; r <= lodDistance; r++) {
            boolean ringFullySatisfied = true;
            int ringSize = 8 * r;
            for (int i = 0; i < ringSize; i++) {
                if (count >= budget) { ringFullySatisfied = false; truncated = true; break outer; }

                ringIndexToCoord(r, i, playerCx, playerCz, chunkCoords);
                int cx = chunkCoords[0];
                int cz = chunkCoords[1];

                long packed = PositionUtil.packPosition(cx, cz);

                // Exclude chunks vanilla RENDERS, replicating its own view-distance test
                // (ChunkTrackingView.isInViewDistance: a 1-chunk-buffered Euclidean radius). The
                // render SQUARE's corners fall OUTSIDE this rounded view — e.g. at viewDistance 12
                // the corner (12,12) is buffered-distance 11^2+11^2=242 vs 12^2=144 — so vanilla
                // never renders them, and the old Chebyshev exclusion (max(|dx|,|dz|) <= vd) left
                // them blank until the player moved. They now fall through to LOD. Like an in-flight
                // skip, an excluded (in-view) chunk does NOT break ring confirmation. Loop-safe:
                // DirtyContentFilter suppresses metadata-only (inhabitedTime) re-saves of a served
                // corner, so re-serving one cannot revive the old re-request loop.
                if (isVanillaRendered(cx, cz, playerCx, playerCz, exclusionRadius)) continue;

                // No in-flight suppression: re-declaration is load-bearing. The server may
                // silently supersede any not-yet-admitted ask; only the 1 Hz re-declare heals
                // that. An awaited position is unsatisfied, so it blocks ring confirmation
                // until its data actually arrives — confirmedRing lags the frontier by the
                // in-flight window, and satisfied positions skip free, so the walk stays cheap.
                long ts = columns.classify(packed);
                if (ts == ColumnStateMap.SATISFIED) continue;

                ringFullySatisfied = false;
                posOut[count] = packed;
                tsOut[count] = ts;
                count++;
                queued++;
                if (localScanRing < r) localScanRing = r;
            }

            // Contiguous prefix only: confirming a satisfied OUTER ring while an inner ring still
            // has unsatisfied positions (an uncovered corner hole) would start every later scan
            // past the inner ring — a permanent LOD hole for a stationary player.
            if (ringFullySatisfied && localConfirmedRing == r) {
                localConfirmedRing = r + 1;
            }
        }

        this.confirmedRing = localConfirmedRing;
        this.scanRing = localScanRing >= 0 ? localScanRing : localConfirmedRing;
        this.lastWalkTruncated = truncated;
        this.lastBudget = budget;
        this.lastQueued = queued;

        return count;
    }

    /**
     * Vanilla's own view-distance test (ChunkTrackingView.isInViewDistance): a 1-chunk-
     * buffered Euclidean radius. Shared by the ring walk's exclusion skip and
     * {@link ColumnStateMap#hasActionableRetries} so the two tests cannot drift.
     */
    static boolean isVanillaRendered(int cx, int cz, int playerCx, int playerCz, int exclusionRadius) {
        int adx = Math.max(0, Math.abs(cx - playerCx) - 1);
        int adz = Math.max(0, Math.abs(cz - playerCz) - 1);
        return (long) adx * adx + (long) adz * adz < (long) exclusionRadius * exclusionRadius;
    }

    /**
     * Maps linear index {@code i} (0 to 8r-1) to the chunk coordinates of the
     * i-th border chunk in Chebyshev ring {@code r}. Four edges, 2r points each,
     * clockwise from top-left.
     */
    static void ringIndexToCoord(int r, int i, int centerX, int centerZ, int[] out) {
        int edge = i / (2 * r);
        int pos = i % (2 * r);
        switch (edge) {
            case 0 -> { out[0] = centerX - r + pos; out[1] = centerZ - r; }
            case 1 -> { out[0] = centerX + r;       out[1] = centerZ - r + pos; }
            case 2 -> { out[0] = centerX + r - pos; out[1] = centerZ + r; }
            case 3 -> { out[0] = centerX - r;       out[1] = centerZ + r - pos; }
        }
    }

    void reset() {
        this.confirmedRing = 0;
        this.scanRing = 0;
        this.lastExclusionRadius = -1; // next session re-anchors; no spurious shrink reset
        this.lastWalkTruncated = false; // fresh session predicts the full disc — fail closed
        this.scanTickCounter = LSSConstants.TICKS_PER_SECOND - 1;
        this.missingVanillaChunks = Integer.MAX_VALUE;
        this.cachedVoxyDistance = -1;
        this.voxyDistanceStaleness = 0;
        this.lastSentCount = 0; // disarm the fast cadence with the rest of the session state
        this.lastScanWasFast = false;
        this.fastScans = 0;
        this.rateGated = 0;
    }

    void resetScanCounter() {
        this.confirmedRing = 0;
        this.scanTickCounter = 0;
        // Disarm too: this is the deliberate post-dimension-change 20-tick wait, and the
        // fresh dimension's empty awaiting set would otherwise satisfy the fast trigger
        // trivially against a stale armed count.
        this.lastSentCount = 0;
    }

    /** Movement re-center: re-walk from ring 0 at the new center WITHOUT touching the
     *  cadence. The pre-v17 movement path used {@link #resetScanCounter} (a debounce),
     *  which starved scanning — and with it re-declaration, the want-set's only healer —
     *  for as long as the player crossed a chunk boundary more often than every 20 ticks:
     *  sustained creative flight stopped LOD generation entirely. Under latest-wins
     *  replace semantics a moving client declaring on schedule is the DESIGNED behavior
     *  (stale asks are superseded and re-declared); yielding to vanilla's own chunk
     *  loading during fast travel is SERVER-SIDE read/generation priority's job (the
     *  client-side vanilla-load budget scale is retired), not the cadence's. The confirmed-ring reset stays: the confirmed prefix was computed for
     *  the OLD center, and keeping it would skip never-scanned rings at the new one. */
    void recenter() {
        this.confirmedRing = 0;
    }

    /**
     * Force the next scan to re-walk from the innermost ring (cheaply skipping already-satisfied
     * positions) WITHOUT resetting the scan-tick cadence. Used when a position BELOW the confirmed
     * ring became requestable again while the ring confirmed past it — a dirty mark landing at a
     * terminal outcome (the stale-crossing path): only a re-walk re-reaches it. Unlike
     * {@link #resetScanCounter} this leaves the cadence alone (a steady trickle of terminal
     * answers would otherwise debounce scans back indefinitely).
     */
    void resetConfirmedRing() {
        this.confirmedRing = 0;
    }


    int getEffectiveLodDistance() {
        int serverDistance = this.sessionConfig.lodDistanceChunks();
        int clientDistance = LSSClientConfig.CONFIG.lodDistanceChunks;
        int effective;
        if (clientDistance > 0) {
            effective = Math.min(clientDistance, serverDistance);
        } else {
            effective = serverDistance;
        }
        int voxyDist = getCachedVoxyDistance();
        if (voxyDist > 0 && voxyDist < effective) {
            effective = voxyDist;
        }
        // Defensive clamp: a legitimate server clamps its advertised distance to
        // [MIN,MAX]_LOD_DISTANCE, but the SessionConfig decoder does not, so a hostile or
        // broken server could send a huge value that overflows getPruneDistance() negative
        // (effective + LOD_DISTANCE_BUFFER) and makes isOutOfRange refuse every column,
        // busy-looping the request loop. Clamp to the same ceiling the server enforces.
        return Math.min(effective, LSSConstants.MAX_LOD_DISTANCE);
    }

    private int getCachedVoxyDistance() {
        if (++this.voxyDistanceStaleness >= LSSConstants.TICKS_PER_SECOND) {
            this.voxyDistanceStaleness = 0;
            var voxyDistance = ModCompat.getVoxyViewDistanceChunks();
            this.cachedVoxyDistance = voxyDistance.isPresent() ? voxyDistance.getAsInt() : -1;
        }
        return this.cachedVoxyDistance;
    }

    int getPruneDistance() {
        return getEffectiveLodDistance() + LSSConstants.LOD_DISTANCE_BUFFER;
    }

    // --- Getters ---

    int getConfirmedRing() { return this.confirmedRing; }
    int getScanRing() { return this.scanRing; }
    int getMissingVanillaChunks() { return this.missingVanillaChunks; }
    int getLastBudget() { return this.lastBudget; }
    int getLastQueued() { return this.lastQueued; }
    boolean wasLastScanFast() { return this.lastScanWasFast; }
    long getFastScans() { return this.fastScans; }
    long getRateGated() { return this.rateGated; }
}
