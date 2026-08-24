package dev.vox.lss.seed;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Issue #1: which bucket a connection's column-stamp cache lives in — the WORLD SEED
 * when the connection can be trusted to have one, the connection ADDRESS otherwise.
 *
 * <p>Why at all: a proxy network reachable at several hostnames used to give every
 * entry address its own cache, so every alternative hostname cold-filled the same
 * world from scratch. The seed is the one identifier every vanilla login already
 * carries that is stable across entry addresses and stable across a proxy's backend
 * switches (26.2 re-sends spawn info through config state, and {@code ClientSessionGate}
 * rebuilds the session on every JOIN, so this class is re-evaluated at exactly the
 * moments the answer can change).
 *
 * <p>PURE BY CONSTRUCTION — no Minecraft, no IO, no state. Everything the decision
 * depends on arrives as a {@link Context} of plain values, so the whole predicate is
 * unit-testable; {@link ClientWorldSeed} is the (untestable) half that reads those
 * values off the live client.
 *
 * <h2>The predicate, and why every term is load-bearing</h2>
 * Seed keying applies only when <b>the switch is on AND a live LSS session backs the
 * read AND the connection is a remote server AND it is not a Realm AND no integrated
 * server is running AND the seed was readable AND the seed is not 0</b>. Anything else
 * falls back to the caller's {@code serverAddress} verbatim — the pre-#1 behaviour, byte
 * for byte.
 *
 * <ul>
 *   <li><b>switch</b> ({@code useWorldSeedCacheKey}, default false): LSS stores only
 *       stamps, the columns themselves live in Voxy. An LSS partition COARSER than
 *       Voxy's is the one dangerous direction — LSS says "already fresh", never asks,
 *       and Voxy's directory is empty: a permanent hole in the distant terrain that
 *       does not self-heal. Seed-keying is therefore only safe once the LOD consumer
 *       partitions by seed as well, which LSS cannot verify (and must not go asking:
 *       no cross-mod contract). So it ships opt-in.</li>
 *   <li><b>live LSS session</b>: the guard against reading a seed that does not belong to
 *       a connection at all. A Flashback / ReplayMod PLAYBACK replays the origin server's
 *       login packet, so the seed a client sees during a replay is the ORIGIN world's —
 *       and {@code Minecraft.currentServer} can still be carrying a previous multiplayer
 *       session's entry while the replay runs, which makes {@code remoteServer} true and
 *       {@code singleplayer} false. Without this term that combination would derive a seed
 *       bucket, and the {@code /lss reset} Voxy half would take someone else's store as a
 *       wipe target. A replay has no LSS session (no handshake, no session config — there
 *       is no server on the other end), so requiring one closes the shape structurally
 *       rather than by trying to recognise every replay mod by name. It is also the term
 *       that makes "residual state" unrepresentable in general: no live session, no seed
 *       bucket, whatever {@code currentServer} happens to still hold.</li>
 *   <li><b>remote</b>: this is the case the ticket exists for.</li>
 *   <li><b>not a Realm</b>: Realms reach the remote branch too ({@code getCurrentServer()}
 *       is non-null), and their addresses are ephemeral per-session handles — pinning
 *       one is not the improvement it looks like, and the pre-#1 shape is proven.</li>
 *   <li><b>no integrated server</b>: single-player worlds HAVE seeds. "There is a seed,
 *       use it" would silently re-partition every single-player cache — the ticket's
 *       explicit anti-requirement. The term also covers a stale
 *       {@code Minecraft.currentServer} surviving into a single-player session.</li>
 *   <li><b>seed readable</b>: the {@code @Accessor} may be absent (a loader whose mixin
 *       config missed it) or {@code Minecraft.level} may be null. Unreadable = fall back,
 *       never guess.</li>
 *   <li><b>seed != 0</b> (A19): {@code 0} is what a server with no real world sends —
 *       NanoLimbo-class waiting rooms call {@code setSeed(0L)} literally. Two UNRELATED
 *       waiting rooms would then share one bucket and cross-declare each other's stamps
 *       "fresh enough": missing terrain here, real data contamination on the LOD-consumer
 *       side. Known-but-unhandled: the older AntiSeedCracker / OsAntiSeedCracker builds
 *       send a fixed {@code 69} rather than 0 — that shape is NOT in this predicate and
 *       cannot be, since 69 is a legal seed hash; such servers must leave the switch off
 *       (documented on the config key). Mainstream anti-seed-cracking plugins do not
 *       touch the hashed seed at all.</li>
 * </ul>
 *
 * <h2>The key format is a contract</h2>
 * {@link #KEY_FORMAT} is deliberately the same literal voxy-extra's seed mode uses, so
 * the two mods' on-disk directories line up for human comparison. It is a shared
 * LITERAL, not a shared call: nothing here reads, imports or reflects on that mod.
 *
 * <p>{@link Locale#ROOT} is pinned DEFENSIVELY, and the reason is worth stating accurately
 * because the obvious one is wrong: Java's {@code %x} is not localised at all — it emits
 * ASCII hex under {@code ar-EG-u-nu-arab}, {@code fa-IR}, {@code hi-IN-u-nu-deva} and the
 * rest (measured 2026-08-24), where {@code %d} genuinely emits {@code ٠١٢} / {@code ۰۱۲} /
 * {@code ०१२}. So today the argument changes nothing. It is there because the day
 * {@link #KEY_FORMAT} grows a conversion that DOES localise, a locale-less
 * {@code String.format} would silently make the bucket name depend on the player's locale;
 * writing it now costs nothing and removes that whole class of future bug.
 */
public final class WorldSeedKey {

    /**
     * The seed-bucket directory name. Lowercase hex, zero-padded to a fixed 16 digits,
     * {@code world-} prefixed. Fixed width matters: it makes the names sort and compare
     * as a block, and it is what voxy-extra writes. Already
     * {@code sanitizeForFilePath}-safe by construction (only {@code [a-z0-9-]}) — which
     * is a property the tests assert, not an excuse to skip sanitising.
     */
    public static final String KEY_FORMAT = "world-%016x";

    private WorldSeedKey() {
    }

    /** {@link #KEY_FORMAT} applied under {@link Locale#ROOT}. */
    public static String format(long seed) {
        return String.format(Locale.ROOT, KEY_FORMAT, seed);
    }

    /**
     * Everything the choice depends on, as plain values.
     *
     * @param seedKeyingEnabled the {@code useWorldSeedCacheKey} switch
     * @param liveLssSession    a live LSS session backs this read (see the class javadoc —
     *                          the replay / residual-state guard)
     * @param remoteServer      a remote server entry exists ({@code getCurrentServer()}
     *                          non-null with an address) — the pre-#1 remote branch
     * @param realm             that entry is a Realm
     * @param singleplayer      an integrated server is running
     * @param seedAvailable     the world seed could actually be read
     * @param seed              the seed read (meaningless when {@code !seedAvailable})
     */
    public record Context(boolean seedKeyingEnabled,
                          boolean liveLssSession,
                          boolean remoteServer,
                          boolean realm,
                          boolean singleplayer,
                          boolean seedAvailable,
                          long seed) {
    }

    /**
     * The seed this connection partitions by, SWITCH-INDEPENDENTLY — empty whenever any
     * non-switch term of the predicate fails.
     *
     * <p>The switch is deliberately not a term here. A22: because the switch can be
     * flipped and because {@code seed == 0} falls back, one server's data can exist under
     * BOTH an address bucket and a seed bucket, and {@code /lss reset} has to clear both
     * or a surviving stamp set will out-live the wipe of the columns it describes. So
     * the reset path asks this method, and only the key CHOICE asks the switch.
     */
    public static OptionalLong keyableSeed(Context context) {
        if (!context.liveLssSession()) return OptionalLong.empty();
        if (!context.remoteServer()) return OptionalLong.empty();
        if (context.realm()) return OptionalLong.empty();
        if (context.singleplayer()) return OptionalLong.empty();
        if (!context.seedAvailable()) return OptionalLong.empty();
        if (context.seed() == 0L) return OptionalLong.empty();
        return OptionalLong.of(context.seed());
    }

    /** {@link #keyableSeed} rendered through {@link #format}. */
    public static Optional<String> seedKey(Context context) {
        OptionalLong seed = keyableSeed(context);
        return seed.isPresent() ? Optional.of(format(seed.getAsLong())) : Optional.empty();
    }

    /**
     * The cache partition key: the seed bucket when the switch is on and a seed bucket
     * exists, otherwise {@code serverAddress} verbatim.
     *
     * <p>Returned UNSANITISED, exactly like the address it may replace — the store still
     * runs its own {@code sanitizeForFilePath} on whatever comes back. Nothing here is
     * allowed to be the last line of defence for a hostile server name.
     */
    public static String choose(Context context, String serverAddress) {
        if (!context.seedKeyingEnabled()) return serverAddress;
        return seedKey(context).orElse(serverAddress);
    }
}
