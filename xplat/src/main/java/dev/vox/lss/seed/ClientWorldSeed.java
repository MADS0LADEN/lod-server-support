package dev.vox.lss.seed;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.mixin.AccessorBiomeManager;
import net.minecraft.client.Minecraft;

import java.util.OptionalLong;

/**
 * Issue #1's Minecraft-facing half: build a {@link WorldSeedKey.Context} out of the live
 * client. Deliberately thin — every DECISION lives in {@link WorldSeedKey}, which is pure
 * and unit-tested; this class only reads, and is exercised in-game.
 *
 * <p>It lives in its own package rather than beside either consumer because BOTH the cache
 * key ({@code ClientNetGlue}, package {@code networking.client}) and the {@code /lss reset}
 * Voxy half ({@code VoxyCompat}, package {@code compat}) need it, and {@code compat} must
 * not start depending on {@code networking.client} for a seed read.
 *
 * <p><b>{@code liveLssSession} is never decided here.</b> Every entry point takes it from
 * its caller, because only the caller knows: the session factory knows it is building a
 * session, and {@code /lss reset} knows whether a manager is alive. Guessing it in this
 * class would mean guessing whether a Flashback playback is running, which is exactly the
 * question the term exists to avoid asking.
 *
 * <p>No timing hazard on the session path: {@code ClientNetGlue.createRequestManager} runs
 * after the session config arrives — by which point {@code Minecraft.level} has long
 * existed — and {@code ClientSessionGate} rebuilds the session on every JOIN, so a 26.2
 * proxy backend switch (which re-sends spawn info through the config state) re-reads the
 * seed rather than keeping a stale one.
 */
public final class ClientWorldSeed {

    private ClientWorldSeed() {
    }

    /**
     * The live context, with the configured switch folded in.
     *
     * @param liveLssSession see the class javadoc — the caller's knowledge, not ours
     */
    public static WorldSeedKey.Context context(boolean liveLssSession) {
        return context(LSSClientConfig.CONFIG.useWorldSeedCacheKey, liveLssSession);
    }

    /** Seam for the switch, so a caller that must ignore it (A22's reset) can say so. */
    public static WorldSeedKey.Context context(boolean seedKeyingEnabled, boolean liveLssSession) {
        var mc = Minecraft.getInstance();
        var serverData = mc.getCurrentServer();
        // Mirrors ClientNetGlue's own remote test verbatim (serverData != null && ip !=
        // null) so the two halves can never disagree about which branch a session is on.
        boolean remote = serverData != null && serverData.ip != null;
        boolean realm = serverData != null && serverData.isRealm();
        boolean singleplayer = mc.getSingleplayerServer() != null;
        OptionalLong seed = readBiomeZoomSeed(mc, seedKeyingEnabled);
        return new WorldSeedKey.Context(seedKeyingEnabled, liveLssSession, remote, realm,
                singleplayer, seed.isPresent(), seed.orElse(0L));
    }

    /**
     * The seed bucket this connection maps to regardless of the switch — A22's reset asks
     * this so a flipped switch cannot leave one structure's stamps behind.
     *
     * @param liveLssSession see the class javadoc
     */
    public static OptionalLong keyableSeed(boolean liveLssSession) {
        return WorldSeedKey.keyableSeed(context(false, liveLssSession));
    }

    /**
     * Reads {@code BiomeManager.biomeZoomSeed} through the {@code @Accessor}.
     *
     * <p>Empty means "we could not read it", and every reason lands here identically: no
     * level yet, or the mixin never applied (a loader whose config missed the entry — the
     * {@code instanceof} is the graceful-degradation seam, and it is why the value is never
     * assumed). Contained: a seed read must never be the thing that breaks a join.
     *
     * <p>{@code diagnose} exists to keep the AC "switch off = zero behaviour change" true
     * of the LOG as well as the key. This method is called unconditionally on the session
     * path (A22 needs the seed bucket derivable even with the switch off), so a log line
     * here would be new output in a configuration that is supposed to be byte-identical to
     * before the ticket. With the switch ON a failed read is worth one debug line, because
     * then it IS the difference between the two behaviours.
     */
    private static OptionalLong readBiomeZoomSeed(Minecraft mc, boolean diagnose) {
        try {
            var level = mc.level;
            if (level == null) return OptionalLong.empty();
            if (level.getBiomeManager() instanceof AccessorBiomeManager accessor) {
                return OptionalLong.of(accessor.lss$getBiomeZoomSeed());
            }
            if (diagnose) {
                LSSLogger.debug("world-seed accessor not applied — keeping the address cache key");
            }
            return OptionalLong.empty();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            if (diagnose) {
                LSSLogger.debug("world seed unreadable (" + t + ") — keeping the address cache key");
            }
            return OptionalLong.empty();
        }
    }
}
