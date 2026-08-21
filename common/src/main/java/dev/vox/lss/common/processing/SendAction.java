package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import java.util.UUID;

/**
 * Actions produced by the processing thread, consumed by the main thread.
 * These represent packets that must be sent from the main thread. Requests are
 * addressed by packed chunk position (the protocol's request key).
 *
 * <p>Each action carries the state it was produced for: a dimension change
 * re-registers the same UUID with a fresh state, and actions produced for the
 * old session must not be delivered into the new one.
 */
public sealed interface SendAction {
    UUID playerUuid();
    long packedPosition();
    /** State the action was produced for; drained only while still the live session. */
    AbstractPlayerRequestState<?> producerState();

    byte responseType();

    /**
     * {@code stampSecond}/{@code stampDimension}: the stamped-up_to_date claim
     * (stamped-up-to-date-plan.md §9.1) — the server-clock second at which a
     * COMPARE-BACKED rung verified the client's copy current, or -1/null for the
     * rungs that must never stamp (the done-bit rung, whose honesty rests on a
     * range-filtered invalidation channel — stamping it re-opens the F2 permanent
     * ghost-terrain hole — and the cannot-improve flavors, where the server holds
     * fresher bytes it declined to ship). The dimension travels WITH the claim
     * (never resolved at flush): overworld/End coords overlap, and a mislabeled
     * frame would fabricate freshness in the wrong dimension.
     */
    record ColumnUpToDate(UUID playerUuid, long packedPosition,
                          AbstractPlayerRequestState<?> producerState,
                          long stampSecond, String stampDimension) implements SendAction {
        /** A stamp claim without its dimension is malformed — normalize to the
         *  never-stamp shape rather than let a producer bug ship a mislabeled claim
         *  (3-Opus fold: the canonical ctor is public by record rules, so it must be
         *  safe to misuse). */
        public ColumnUpToDate {
            if (stampSecond > 0 && stampDimension == null) {
                stampSecond = -1L;
            }
        }
        /** The never-stamp form — every producer that is not a compare-backed rung.
         *  PUBLIC (unlike the record's implicit canonical): the safe default an
         *  out-of-package producer should reach first. */
        public ColumnUpToDate(UUID playerUuid, long packedPosition,
                              AbstractPlayerRequestState<?> producerState) {
            this(playerUuid, packedPosition, producerState, -1L, null);
        }
        @Override public byte responseType() { return LSSConstants.RESPONSE_UP_TO_DATE; }
    }

    record ColumnNotGenerated(UUID playerUuid, long packedPosition,
                              AbstractPlayerRequestState<?> producerState) implements SendAction {
        @Override public byte responseType() { return LSSConstants.RESPONSE_NOT_GENERATED; }
    }
}
