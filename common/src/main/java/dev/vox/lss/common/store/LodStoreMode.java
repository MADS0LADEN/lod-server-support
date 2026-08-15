package dev.vox.lss.common.store;

import java.util.Locale;

/**
 * The {@code lodStore} config switch (docs/planning/lod-store-implementation-plan.md §1):
 * {@code off} (no store — the kill switch every phase gate A/Bs against) and
 * {@code full} (the SQLite disk store).
 *
 * <p><b>There is no MEMORY constant anymore</b> — {@code "memory"} was retired as a
 * user-facing mode 2026-08-02 (normalizes to {@code off} like any unrecognized word),
 * and the in-memory degrade TIER itself was deleted 2026-08-13 (user decision): a
 * failed SQLite init now runs store-less, and the diag token reports {@code store=unavailable}
 * — what is actually running. Rationale for the retirement: at its 64 MB budget the
 * tier held ~6% of one player's disc under random eviction while zstd-compressing
 * every deposit, and the Phase 2 A/B had already deleted it from {@code full} mode for
 * costing +14.6% CPU/col to save 5 µs against a 2.4 ms NBT path.
 *
 * <p>Since the 2026-08-08 config rework (user decision) the store is ON BY DEFAULT and
 * {@code "on"} is the canonical spelling of {@link #FULL} ({@code "full"} stays accepted
 * forever). Unknown values still normalize to {@link #OFF} with the same rationale as
 * before, direction-adjusted: a typo now silently DISABLES a default feature rather
 * than silently enabling a storage engine — predictable either way, and the boot's
 * config echo names the effective mode. Pinned by {@code LodStoreModeTest}.
 */
public enum LodStoreMode {
    OFF, FULL;

    public static LodStoreMode normalize(String value) {
        if (value == null) return OFF;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            // NOTE: no "memory" case — see the MEMORY javadoc. A config that still says
            // "memory" lands on OFF and validate() rewrites the file to "off".
            case "full", "on" -> FULL;
            default -> OFF;
        };
    }

    /** The canonical config-file spelling ({@code FULL} writes back as {@code "on"} —
     *  the 2026-08-08 rework's user-facing name; {@code "full"} remains a read alias). */
    public String configValue() {
        return this == FULL ? "on" : name().toLowerCase(Locale.ROOT);
    }
}
