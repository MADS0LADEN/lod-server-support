package dev.vox.lss.networking.client;

/**
 * NeoForge far-player renderer — the fabric {@code FarPlayerRenderer}'s
 * same-FQN twin, satisfying xplat's compile coupling
 * (the xplat session-end path calls {@link #clearInstance()}).
 * <b>N-2 state: the render path is a NO-OP</b> — per the plan's precise cut
 * form (§N-3): the tracker, wire channels, and capability arm term are
 * untouched (the bit is the PREFS CARRIER); only rendering is absent. N-3
 * attempts the real renderer on the RenderLevelStageEvent/Extract pair.
 */
public final class FarPlayerRenderer {

    private FarPlayerRenderer() {
    }

    /** Session-end proxy teardown — nothing to clear while the render path is a no-op. */
    static void clearInstance() {
    }
}
