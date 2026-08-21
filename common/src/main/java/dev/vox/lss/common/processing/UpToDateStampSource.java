package dev.vox.lss.common.processing;

/**
 * The stamped-up_to_date stamping predicate (stamped-up-to-date-plan.md §9.1/§9.2 as
 * narrowed by §10.1). Consulted by exactly the TWO compare-backed up_to_date rungs —
 * the router's tscache rung and the header-fresh delivery — at DISPOSITION time; the
 * store-stamp conversion deliberately never stamps (its own delivery-honesty
 * doctrine: a re-serve hands the STORED acquisition second, so a "verified now"
 * stamp would claim more than the bytes it declined to send), and every other
 * up_to_date producer uses the never-stamp {@code ColumnUpToDate} form
 * unconditionally ({@code stampedSiteCensusHoldsTheNarrowing} pins the site count).
 *
 * <p>The stamping doctrine: a rung may be stamped only if its staleness bound at
 * disposition is provably inside {@code FRESH_CLAIM_MARGIN_SECONDS}. The platform
 * implementation answers -1 (never stamp) when the position's change is
 * MARKED-BUT-UNDRAINED in the dirty tracker or the region's save-mark latch is armed
 * (the plan review's MAJOR-2: a stamp issued inside the save-to-drain window would
 * launder invalidation latency into a permanent cross-session seal — the drain
 * window, {@code dirtyBroadcastIntervalSeconds} up to 300 s, is NOT pinned inside the
 * 15 s margin), and otherwise the server-clock epoch second "now" — the verification
 * instant, exactly the claim the up_to_date answer itself makes.
 *
 * <p>The default (tests, harnesses without the wiring) is {@link #NEVER}, which keeps
 * every existing behavior bit-identical: no stamps are produced at all.
 */
@FunctionalInterface
public interface UpToDateStampSource {

    UpToDateStampSource NEVER = (player, dimension, packedPosition) -> -1L;

    /** Server-clock epoch second to stamp this verification with, or -1 to answer
     *  the ordinary unstamped up_to_date. Called on the processing thread. The
     *  player is passed so the platform can SHORT-CIRCUIT on stamps eligibility
     *  first (3-Opus fold: on a server with no summary-requesting session — the
     *  common case today — the predicate must not put the dirty tracker's monitor,
     *  shared with the save hook, on the router's hot path for work that is thrown
     *  away). */
    long stampSecond(java.util.UUID player, String dimension, long packedPosition);
}
