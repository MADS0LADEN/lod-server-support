package dev.vox.lss.common.processing;

/**
 * An in-flight request tracked by the processing thread. The {@code heldSlot} records
 * which admission slot this entry occupies; slot occupancy is derived by counting
 * pending entries, so adding/removing a pending entry IS the acquire/release.
 *
 * <p>{@code clientTimestamp} is the client's declared stamp for the position, carried
 * through admission so the DELIVERY side can compare it against result freshness
 * (region-summary-sync-plan.md P1): a store hit whose stored acquisition stamp is
 * {@code <=} the client's, or a header-fresh result whose save second is strictly below
 * it, answers {@code up_to_date} instead of re-sending bytes. {@link #claimsData()}
 * stays the derived boolean every all-air/clearing decision reads: {@code > 0} means a
 * resync — the client already holds a column for this position.
 */
public record PendingRequest(int cx, int cz, SlotType heldSlot, long clientTimestamp) {

    /** True when the requesting client sent {@code clientTimestamp > 0} (a resync — it
     *  already holds a column there). Read at delivery to decide whether an all-air
     *  resolution sends a clearing 0-section column or a cheap {@code up_to_date}. */
    public boolean claimsData() {
        return this.clientTimestamp > 0;
    }
}
