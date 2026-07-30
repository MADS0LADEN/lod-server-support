package dev.vox.lss.common.store;

import java.util.Locale;

/**
 * The {@code lodStore} config switch (docs/planning/lod-store-implementation-plan.md §1):
 * {@code off} (no store — the kill switch every phase gate A/Bs against), {@code memory}
 * (the Phase 1 bounded in-memory tier only), {@code full} (memory + SQLite disk store).
 *
 * <p>Unknown values normalize to {@link #OFF} — the SAFE value (unlike
 * {@code xrayObfuscation}'s normalize-to-auto, this one is deliberately safe-biased: a
 * typo must never enable a storage engine). Pinned by {@code LodStoreModeTest}.
 */
public enum LodStoreMode {
    OFF, MEMORY, FULL;

    public static LodStoreMode normalize(String value) {
        if (value == null) return OFF;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "memory" -> MEMORY;
            case "full" -> FULL;
            default -> OFF;
        };
    }

    /** The canonical config-file spelling. */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
