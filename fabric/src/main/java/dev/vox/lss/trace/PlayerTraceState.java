package dev.vox.lss.trace;

import dev.vox.lss.common.processing.ChannelPressureProbe;

/**
 * Per-player tracer state (gap clock, 5 Hz ring, arm timer, probe) — created lazily,
 * swept on disconnect (Fable F2-9: trivial rates today, but a weeks-long campaign must
 * not leak by construction). Server-thread-owned; the registry map is the only shared
 * structure.
 */
final class PlayerTraceState {

    /** "Event in the last 30 s" arm window (§1.5). */
    static final long EVENT_ARM_MILLIS = 30_000;

    private final GapClock gapClock = new GapClock();
    private final FlightRing ring = new FlightRing();
    private ChannelPressureProbe probe;
    private long armedUntilMs;

    GapClock gapClock() {
        return gapClock;
    }

    FlightRing ring() {
        return ring;
    }

    ChannelPressureProbe probe() {
        return probe;
    }

    void setProbe(ChannelPressureProbe probe) {
        this.probe = probe;
    }

    void armFromEvent(long nowMs) {
        armedUntilMs = nowMs + EVENT_ARM_MILLIS;
    }

    boolean eventArmed(long nowMs) {
        return nowMs < armedUntilMs;
    }
}
