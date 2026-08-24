package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A22 (issue #1): {@code /lss reset} has to clear the Voxy store under BOTH naming
 * conventions a connection can have used — the address-named directory and the
 * seed-named {@code world-<16 hex>} one.
 *
 * <p>Why the seed-named root is allowed to be wiped without passing the storage-override
 * cross-check, and why that is NOT a relaxation of issue #4's fail-safe: the cross-check
 * answers "may we delete a root VOXY chose for us?", because a replay mod redirects Voxy
 * at the ORIGIN server's real store and that path passes directory containment. The
 * seed-named root is a root LSS derived ITSELF, from its own connection's login seed —
 * the same class of path as {@code fallbackPath}, which the no-instance branch has always
 * wiped under containment alone.
 *
 * <p>Replay safety is structural: the production hook yields a path only when a LIVE LSS
 * session backs the connection, which a replay playback (no handshake, no server) can never
 * have — see {@link #theProductionSeedRootIsGatedOnALiveLssSession()} and the pure half in
 * {@code WorldSeedKeyTest}. With the hook returning null everything in
 * {@link #aNullSeedHookLeavesIssue4sLadderBitIdentical()} holds, i.e. the ladder behaves
 * exactly as it did before this ticket.
 *
 * <p>The other half of the contract is honesty: a run that wipes MORE must not report
 * less — a declined LIVE root and a cleared SEED root happen together, and the report has
 * to name both.
 */
class VoxySeedRootResetTest {

    private static final VoxyCompat.ThrowingRunnable OK = () -> { };

    /** Hooks with a live instance, a wipe recorder, and an injectable seed root. */
    private static VoxyCompat.ResetHooks hooks(List<Path> wiped, Path liveRoot, Path derived,
                                               Path seedRoot) {
        return new VoxyCompat.ResetHooks(
                Object::new,
                instance -> liveRoot,
                () -> derived,
                () -> seedRoot,
                () -> () -> { },
                OK,
                OK,
                wiped::add,
                () -> { },
                () -> { });
    }

    /** The no-live-instance shape (config-disabled / GPU-unsupported Voxy). */
    private static VoxyCompat.ResetHooks noInstanceHooks(List<Path> wiped, Path derived,
                                                         Path seedRoot) {
        return new VoxyCompat.ResetHooks(
                () -> null,
                instance -> { throw new AssertionError("storagePath must not be read"); },
                () -> derived,
                () -> seedRoot,
                () -> () -> { },
                OK,
                OK,
                wiped::add,
                () -> { },
                () -> { });
    }

    private static final Path GAME = Path.of("/game");
    private static final Path ADDRESS_ROOT = Path.of("/game/.voxy/saves/mc.example.com_25565");
    private static final Path SEED_ROOT = Path.of("/game/.voxy/saves/world-00000000000000ff");

    // ---- the naming contract ----

    @Test
    void theSeedNamedRootUsesTheSharedDirectoryLiteral() {
        assertEquals(Path.of("/game/.voxy/saves/world-00000000000000ff"),
                VoxyCompat.seedVoxyRoot(GAME, 0xffL),
                "the same 'world-%016x' name the cache key uses — the two stores line up "
                        + "for human comparison, which is the whole agreement");
        assertEquals(Path.of("/game/.voxy/saves/world-ffffffffffffffff"),
                VoxyCompat.seedVoxyRoot(GAME, -1L), "negative seeds render unsigned");
    }

    @Test
    void theSeedNamedRootSurvivesTheContainmentFence() {
        // It sits strictly inside <gameDir>/.voxy/saves, so the SECOND fence passes it —
        // stated explicitly because "contained" is what licenses wiping it at all.
        assertTrue(VoxyCompat.isWipeContained(VoxyCompat.seedVoxyRoot(GAME, 0xffL), GAME));
        assertFalse(VoxyCompat.isWipeContained(Path.of("/game/.voxy/saves"), GAME),
                "and the parent itself is still never a wipe target");
    }

    // ---- the ladder ----

    @Test
    void aNullSeedHookLeavesIssue4sLadderBitIdentical() {
        // The default and the replay shape: no seed root, so nothing about #4 changes.
        var wiped = new ArrayList<Path>();
        var report = VoxyCompat.resetVoxy(hooks(wiped, ADDRESS_ROOT, ADDRESS_ROOT, null), false);
        assertEquals(ModCompat.VoxyResetOutcome.RESET, report.outcome());
        assertEquals(List.of(ADDRESS_ROOT), wiped);
        assertNull(report.seedRoot());
        assertFalse(report.wipeDeclined());

        // ...and an OVERRIDDEN live root is still refused, exactly as before.
        var wiped2 = new ArrayList<Path>();
        var overridden = VoxyCompat.resetVoxy(
                hooks(wiped2, Path.of("/game/.voxy/saves/someone-else"), ADDRESS_ROOT, null), false);
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, overridden.outcome());
        assertTrue(overridden.wipeDeclined());
        assertEquals(List.of(), wiped2, "nothing may be deleted on the fail-safe path");
    }

    @Test
    void bothStructuresAreWipedWhenTheyAreDifferentDirectories() {
        var wiped = new ArrayList<Path>();
        var report = VoxyCompat.resetVoxy(
                hooks(wiped, ADDRESS_ROOT, ADDRESS_ROOT, SEED_ROOT), false);

        assertEquals(ModCompat.VoxyResetOutcome.RESET, report.outcome());
        assertEquals(List.of(ADDRESS_ROOT, SEED_ROOT), wiped,
                "A22: the address-named store AND the seed-named store, in that order");
        assertEquals(SEED_ROOT, report.seedRoot(), "the report names what it deleted");
    }

    @Test
    void aDeclinedLiveRootIsNotDeletedUnderTheSeedNameEither() {
        // The invariant #4 actually promises: a directory the cross-check refused stays
        // refused, whatever OTHER derivation happens to produce the same name. Unreachable
        // in a real session — Voxy's getStorageBasePath() returns the address-derived
        // basePath its constructor fixed, and voxy-extra's seed mode mixes into
        // createStorage without touching it, so a live root is never a world-* name — which
        // means the only input that could reach this branch is a replay's storage override
        // that happens to be called world-*. That is exactly the case #4 exists to refuse,
        // so it must stay refused here too.
        var wiped = new ArrayList<Path>();
        var report = VoxyCompat.resetVoxy(
                hooks(wiped, SEED_ROOT, ADDRESS_ROOT, SEED_ROOT), false);

        assertEquals(List.of(), wiped, "nothing may be deleted on the fail-safe path");
        assertTrue(report.wipeDeclined());
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, report.outcome());
    }

    @Test
    void theProductionSeedRootIsGatedOnALiveLssSession() {
        // The replay / residual-state guard lives in the production wiring, which no JUnit
        // test can reach (it needs a live Minecraft). Pinned at the source instead, the way
        // this repo pins its other unreachable wiring — the pure half of the same guard is
        // in WorldSeedKeyTest.aReplayPlaybackOverResidualConnectionStateDerivesNoSeedBucket.
        String voxyCompat = readSource("dev/vox/lss/compat/VoxyCompat.java");
        assertTrue(voxyCompat.contains("ClientWorldSeed.keyableSeed(lssSessionActive)"),
                "the seed root must be derived only through the session-gated entry point");
        assertTrue(voxyCompat.contains("existingSeedVoxyRoot(gameDir, lssSessionActive)"),
                "...and both production hooks must pass the flag through");

        String commands = readSource("dev/vox/lss/networking/client/ClientCommandActions.java");
        assertTrue(commands.contains("resetVoxyLods(forceWipe, manager != null)"),
                "/lss reset must tell the Voxy half whether a live session backs it");
        assertTrue(commands.contains("probeVoxyStorage(manager != null)"),
                "...and so must the voxy-force probe, or stage 1 would advertise a target "
                        + "stage 2 refuses to touch");
    }

    private static String readSource(String javaPath) {
        try {
            return java.nio.file.Files.readString(
                    dev.vox.lss.testutil.SourcePaths.mainSource(javaPath));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void aWipeableLiveRootThatEqualsTheSeedRootIsNotWipedTwice() {
        var wiped = new ArrayList<Path>();
        VoxyCompat.resetVoxy(hooks(wiped, SEED_ROOT, SEED_ROOT, SEED_ROOT), false);
        assertEquals(List.of(SEED_ROOT), wiped);
    }

    @Test
    void aDeclinedLiveRootStillClearsTheSeedNamedStore() {
        // A genuine storage override plus a seed-named store of our own: the override is
        // still refused, and OUR root is still cleared. The report must say both things.
        var wiped = new ArrayList<Path>();
        Path foreign = Path.of("/game/.voxy/saves/someone-else");
        var report = VoxyCompat.resetVoxy(hooks(wiped, foreign, ADDRESS_ROOT, SEED_ROOT), false);

        assertEquals(List.of(SEED_ROOT), wiped, "the foreign live root is untouched");
        assertTrue(report.wipeDeclined(), "the live root was still declined");
        assertEquals(SEED_ROOT, report.seedRoot());
        assertEquals(ModCompat.VoxyResetOutcome.RESET_WIPE_SKIPPED, report.outcome());
    }

    @Test
    void theNoInstanceBranchWipesBothDerivedRoots() {
        var wiped = new ArrayList<Path>();
        var report = VoxyCompat.resetVoxy(noInstanceHooks(wiped, ADDRESS_ROOT, SEED_ROOT), false);

        assertEquals(ModCompat.VoxyResetOutcome.WIPED_NO_INSTANCE, report.outcome());
        assertEquals(List.of(ADDRESS_ROOT, SEED_ROOT), wiped);
        assertEquals(SEED_ROOT, report.seedRoot());
    }

    @Test
    void theNoInstanceBranchDoesNotWipeTheSameRootTwice() {
        var wiped = new ArrayList<Path>();
        VoxyCompat.resetVoxy(noInstanceHooks(wiped, SEED_ROOT, SEED_ROOT), false);
        assertEquals(List.of(SEED_ROOT), wiped);
    }

    @Test
    void aFailedShutdownSkipsTheSeedWipeToo() {
        // Deleting over open storage handles is the Windows partial-wipe trap whatever the
        // directory is called — the seed target rides in the SAME instance-down window.
        var wiped = new ArrayList<Path>();
        var hooks = new VoxyCompat.ResetHooks(
                Object::new,
                instance -> ADDRESS_ROOT,
                () -> ADDRESS_ROOT,
                () -> SEED_ROOT,
                () -> () -> { },
                () -> { throw new IllegalStateException("shutdown boom"); },
                OK,
                wiped::add,
                () -> { },
                () -> { });
        var report = VoxyCompat.resetVoxy(hooks, false);

        assertEquals(ModCompat.VoxyResetOutcome.SHUTDOWN_FAILED, report.outcome());
        assertEquals(List.of(), wiped, "no wipe at all runs over possibly-open handles");
    }

    @Test
    void anUnresolvableRendererAbortsBeforeAnySeedWipe() {
        var wiped = new ArrayList<Path>();
        var hooks = new VoxyCompat.ResetHooks(
                Object::new,
                instance -> ADDRESS_ROOT,
                () -> ADDRESS_ROOT,
                () -> SEED_ROOT,
                () -> null, // holder unresolvable -> fail-safe abort
                OK,
                OK,
                wiped::add,
                () -> { },
                () -> { });
        var report = VoxyCompat.resetVoxy(hooks, false);

        assertEquals(ModCompat.VoxyResetOutcome.UNAVAILABLE, report.outcome());
        assertEquals(List.of(), wiped);
        assertEquals(SEED_ROOT, report.seedRoot(),
                "the abort still names the root the user may want to delete by hand");
    }

    @Test
    void aThrowingSeedHookIsContainedAndDegradesToNoSeedRoot() {
        var wiped = new ArrayList<Path>();
        var hooks = new VoxyCompat.ResetHooks(
                Object::new,
                instance -> ADDRESS_ROOT,
                () -> ADDRESS_ROOT,
                () -> { throw new IllegalStateException("seed probe boom"); },
                () -> () -> { },
                OK,
                OK,
                wiped::add,
                () -> { },
                () -> { });
        var report = VoxyCompat.resetVoxy(hooks, false);

        assertEquals(ModCompat.VoxyResetOutcome.RESET, report.outcome(),
                "a message-grade read must never turn a working reset into a failed one");
        assertEquals(List.of(ADDRESS_ROOT), wiped);
        assertNull(report.seedRoot());
    }

    @Test
    void theProbeReportsTheSeedRootWithoutDeletingAnything() {
        var wiped = new ArrayList<Path>();
        var probe = VoxyCompat.probeStorage(
                hooks(wiped, ADDRESS_ROOT, ADDRESS_ROOT, SEED_ROOT), GAME);

        assertEquals(SEED_ROOT, probe.seedRoot());
        assertEquals(ADDRESS_ROOT, probe.liveRoot());
        assertEquals(List.of(), wiped, "stage 1 of voxy-force deletes nothing, ever");
    }
}
