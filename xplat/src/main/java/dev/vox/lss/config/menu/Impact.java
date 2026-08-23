package dev.vox.lss.config.menu;

/**
 * Performance-impact tag shown in an option's tooltip. The constant NAMES mirror
 * Sodium's {@code OptionImpact} enum on BOTH generations (0.6/0.7 internal and 0.8+
 * public API carry exactly LOW/MEDIUM/HIGH/VARIES), so each renderer maps by name —
 * the catalog stays Sodium-free and a renamed Sodium constant fails at the renderer,
 * contained, not in shared code.
 */
public enum Impact {
    LOW, MEDIUM, HIGH, VARIES
}
