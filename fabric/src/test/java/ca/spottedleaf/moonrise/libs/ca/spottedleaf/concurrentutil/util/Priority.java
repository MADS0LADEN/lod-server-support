package ca.spottedleaf.moonrise.libs.ca.spottedleaf.concurrentutil.util;

/**
 * Test stub with Moonrise-Fabric's real SHADED Priority name (unlike Paper's unshaded
 * {@code ca.spottedleaf.concurrentutil.util.Priority}) — the constant set mirrors 1.1.0.
 * The production resolver must not care about the package (it takes the class from the
 * matched method's parameter types); using the real shaded name here keeps the happy-path
 * test faithful to a live Moonrise-Fabric runtime.
 */
public enum Priority {
    COMPLETING,
    BLOCKING,
    HIGHEST,
    HIGHER,
    HIGH,
    NORMAL,
    LOW,
    LOWER,
    LOWEST,
    IDLE
}
