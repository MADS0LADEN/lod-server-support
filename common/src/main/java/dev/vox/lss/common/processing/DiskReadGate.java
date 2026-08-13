package dev.vox.lss.common.processing;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Expensive-path concurrency gate for disk-read serves
 * (docs/planning/disk-read-concurrency-gate-plan.md): at most {@code capacity} permits
 * may be held across the region-read → inflate → parse → transcode phase. Sits at the
 * store-miss boundary in {@link AbstractChunkDiskReader#readAndDeliver} — store hits
 * never touch it. A refused acquire PARKS the read in the reader's bounded wait list
 * (drained on release — see the reader's {@code gateParked} comment for the measured
 * starvation the pure bounce caused); park overflow delivers the {@code
 * saturated}-flavor silent drop, healed by re-declaration. Nothing ever blocks on the
 * gate itself.
 *
 * <p>All methods are any-thread (pool workers acquire/release concurrently; CAS loop,
 * never a lock). Since Amendment 2 the SUSTAINED-pressure answer lives ABOVE this
 * class: the router consults {@code AbstractChunkDiskReader.gateSaturated()} (permits
 * exhausted AND the park plus permit-less in-flight work at its bound) and RETAINS
 * entries instead of submitting them into a certain overflow — park overflow is race
 * armor only. The park's capacity relation ({@code gateParkCapacity >= queueCapacity},
 * reader ctor) is load-bearing for that predicate's K = pool no-op proof: do not
 * shrink the park as a "tightening". {@code capacity} is a volatile read at every {@link #tryAcquire} —
 * boot-configured in v0.11.0 stage B, runtime-mutable via {@code /lsslod set} from
 * stage C (R-2). <b>Lowering semantics</b>: in-use may transiently exceed a lowered
 * capacity until held permits drain — new acquisitions refuse meanwhile, {@link #release}
 * never drives the counter negative, and no permit sticks. Pinned by
 * {@code DiskReadGateTest}.
 */
public final class DiskReadGate {

    private volatile int capacity;
    private final AtomicInteger inUse = new AtomicInteger();

    public DiskReadGate(int capacity) {
        this.capacity = capacity;
    }

    /** Acquire a permit, or false without blocking. The caller owns the gated-counter
     *  bookkeeping (DiskReaderDiagnostics.recordGated at the one bounce site) — the gate
     *  itself stays a pure permit counter. */
    public boolean tryAcquire() {
        while (true) {
            int cap = this.capacity; // volatile — a runtime lowering binds mid-loop
            int cur = this.inUse.get();
            if (cur >= cap) return false;
            if (this.inUse.compareAndSet(cur, cur + 1)) return true;
        }
    }

    /** Release a held permit. Callers pair this with a successful {@link #tryAcquire}
     *  in try/finally; the floor guard keeps a double-release bug from wedging the
     *  counter below zero (visible as a too-open gate, never a stuck-shut one). */
    public void release() {
        this.inUse.updateAndGet(cur -> Math.max(0, cur - 1));
    }

    /** Reconfigure K. Raising binds immediately; lowering lets held permits drain (see
     *  the class contract). Stage B calls this once at service init (post-store-attach);
     *  stage C adds the runtime path. The ≥1 floor is stage-C insurance (review B-3): a
     *  zero capacity would park every miss with no holder ever releasing to drain them —
     *  a permanent strand, not a tight gate. Note a capacity RAISE does not proactively
     *  kick a drain — parked work waits for the next release/park event. Stage C
     *  (/lsslod set maxConcurrentDiskReads) ACCEPTS this: under any read traffic the
     *  next release arrives within milliseconds, and an idle reader has nothing parked
     *  to drain — instant-raise machinery would guard a state that cannot matter. */
    public void updateCapacity(int newCapacity) {
        this.capacity = Math.max(1, newCapacity);
    }

    public int capacity() {
        return this.capacity;
    }

    /** In-use permit gauge — diag-line only, deliberately NEVER exported as a snapshot
     *  field (a nonzero gauge in SERVER_DRAINS would kill quiescent windows while the
     *  gate is legitimately busy — the store.queue trap). */
    public int inUse() {
        return this.inUse.get();
    }
}
