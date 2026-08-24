package dev.vox.lss.seed;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #1 — the cache-partition key selector, pinned as a PURE function.
 *
 * <p>The one rule this class exists to defend: <b>seed keying is opt-in AND
 * remote-only</b>. Single-player worlds carry a seed too, so "there is a seed, use it"
 * would silently re-partition every single-player cache — the ticket's explicit
 * anti-requirement. Every non-remote / unusable-seed shape must fall back to the
 * pre-#1 {@code serverAddress} key BYTE-FOR-BYTE, which is what
 * {@link #switchOffIsAByteForByteFallback()} and the shape tests below assert.
 *
 * <p>The {@code seed == 0} sentinel (A19) is NOT paranoia: NanoLimbo-class waiting
 * rooms send a literal {@code setSeed(0L)}, so two UNRELATED waiting rooms would share
 * one bucket and their stamps would cross-declare "fresh enough" — missing terrain on
 * the LSS side, real data cross-contamination on the Voxy side. Same rule as
 * voxy-extra's seed mode, by agreement, not by call.
 */
class WorldSeedKeyTest {

    private static final String ADDR = "play.example.com:25565";

    /** A live LSS session on an ordinary remote server — the only shape that seed-keys. */
    private static WorldSeedKey.Context remote(boolean enabled, boolean seedAvailable, long seed) {
        return new WorldSeedKey.Context(enabled, true, true, false, false, seedAvailable, seed);
    }

    // ---- the key format (AC: exactly world-%016x, negative seeds unsigned) ----

    @Test
    void keyFormatIsLowercaseSixteenHexDigitsWithTheSharedPrefix() {
        assertEquals("world-00000000000000ff", WorldSeedKey.format(0xffL),
                "the ticket's own worked example — this literal is the voxy-extra contract");
        assertEquals("world-0000000000000000", WorldSeedKey.format(0L));
        assertEquals("world-123456789abcdef0", WorldSeedKey.format(0x123456789abcdef0L));
    }

    @Test
    void negativeSeedsRenderAsUnsignedSixteenHex() {
        assertEquals("world-ffffffffffffffff", WorldSeedKey.format(-1L),
                "a long is signed, the directory name is not — %016x must not emit a '-'");
        assertEquals("world-8000000000000000", WorldSeedKey.format(Long.MIN_VALUE));
        assertTrue(WorldSeedKey.format(-7L).indexOf('-', "world-".length()) < 0,
                "no sign anywhere after the prefix: " + WorldSeedKey.format(-7L));
        assertEquals(6 + 16, WorldSeedKey.format(Long.MIN_VALUE).length());
    }

    @Test
    void theKeyIsIdenticalUnderEveryDigitLocale() {
        // MEASURED, not assumed (2026-08-24): Java's %x is NOT localised — it emits ASCII
        // hex under ar-EG-u-nu-arab, fa-IR, hi-IN-u-nu-deva and friends, where %d emits
        // ٠١٢ / ۰۱۲ / ०१२. So the ticket's stated reason for pinning Locale.ROOT does not
        // bite on THIS formatter, and a test that only flipped the default locale would
        // pass with or without the pin (verified by mutation).
        //
        // What this test is actually worth: it pins the OUTPUT, so a future rewrite of
        // format() into anything that does localise — %d-based padding, a NumberFormat, a
        // hand-rolled digit table — turns red here. The Locale.ROOT argument itself is
        // pinned separately, at the source, by localeRootIsPinnedInTheFormatCall().
        Locale previous = Locale.getDefault();
        try {
            for (String tag : new String[] {"ar-EG-u-nu-arab", "fa-IR", "hi-IN-u-nu-deva",
                    "bn-IN-u-nu-beng", "my-MM", "tr-TR"}) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                assertEquals("world-00000000000000ff", WorldSeedKey.format(0xffL),
                        "the bucket name must not depend on the player's locale (" + tag + ")");
                assertEquals("world-ffffffffffffffff", WorldSeedKey.format(-1L),
                        "including the lowercase 'ff' — tr-TR is the dotless-i trap (" + tag + ")");
            }
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void localeRootIsPinnedInTheFormatCall() throws IOException {
        // Belt for the above: since the current formatter happens not to localise hex, no
        // behavioural test can defend the explicit Locale.ROOT — and it should still be
        // there, because "String.format without a locale" is the habit that breaks the day
        // the format string changes. Pinned at the source, the only place it is visible.
        String source = Files.readString(
                SourcePaths.mainSource("dev/vox/lss/seed/WorldSeedKey.java"));
        assertTrue(source.contains("String.format(Locale.ROOT, KEY_FORMAT, seed)"),
                "the key format must be applied under Locale.ROOT explicitly: " + source);
    }

    // ---- the predicate (AC: seven shapes) ----

    @Test
    void switchOffIsAByteForByteFallback() {
        // AC "switch closed => getServerDir() output identical to before the change".
        assertEquals(ADDR, WorldSeedKey.choose(remote(false, true, 42L), ADDR));
        assertTrue(WorldSeedKey.seedKey(remote(false, true, 42L)).isPresent(),
                "the seed BUCKET is derivable regardless of the switch — A22's reset needs it");
    }

    @Test
    void switchOnRemoteWithASeedUsesTheSeedKey() {
        assertEquals("world-000000000000002a", WorldSeedKey.choose(remote(true, true, 42L), ADDR));
    }

    @Test
    void singleplayerKeepsTheAddressKeyEvenThoughItHasASeed() {
        var ctx = new WorldSeedKey.Context(true, true, false, false, true, true, 42L);
        assertEquals("local:MyWorld", WorldSeedKey.choose(ctx, "local:MyWorld"));
        assertTrue(WorldSeedKey.keyableSeed(ctx).isEmpty(),
                "no seed bucket exists for a single-player world — not even for the reset flush");
    }

    @Test
    void aRemoteFlagThatSomehowCoexistsWithAnIntegratedServerStillKeepsTheAddressKey() {
        // Belt for a stale Minecraft.currentServer left over from an earlier multiplayer
        // session: the singleplayer term alone must veto seed keying.
        var ctx = new WorldSeedKey.Context(true, true, true, false, true, true, 42L);
        assertEquals(ADDR, WorldSeedKey.choose(ctx, ADDR));
    }

    @Test
    void unknownConnectionKeepsTheAddressKey() {
        var ctx = new WorldSeedKey.Context(true, true, false, false, false, true, 42L);
        assertEquals("unknown", WorldSeedKey.choose(ctx, "unknown"));
    }

    @Test
    void anUnreadableSeedKeepsTheAddressKey() {
        // The @Accessor threw, the mixin did not apply, or Minecraft.level was null:
        // the MC-facing half reports seedAvailable=false and we fall back.
        assertEquals(ADDR, WorldSeedKey.choose(remote(true, false, 0L), ADDR));
        assertTrue(WorldSeedKey.keyableSeed(remote(true, false, 0L)).isEmpty());
    }

    @Test
    void zeroSeedKeepsTheAddressKey() {
        assertEquals(ADDR, WorldSeedKey.choose(remote(true, true, 0L), ADDR),
                "0 is NanoLimbo-class 'no real world' — two unrelated waiting rooms must "
                        + "not share one bucket");
        assertTrue(WorldSeedKey.keyableSeed(remote(true, true, 0L)).isEmpty(),
                "and no seed bucket is derivable for the reset flush either");
    }

    @Test
    void realmsKeepsTheAddressKey() {
        var ctx = new WorldSeedKey.Context(true, true, true, true, false, true, 42L);
        assertEquals(ADDR, WorldSeedKey.choose(ctx, ADDR));
        assertTrue(WorldSeedKey.keyableSeed(ctx).isEmpty());
    }

    // ---- the replay / residual-state guard ----

    @Test
    void aReplayPlaybackOverResidualConnectionStateDerivesNoSeedBucket() {
        // The shape that makes this term necessary, spelled out: a Flashback / ReplayMod
        // PLAYBACK replays the ORIGIN server's login packet, so the seed on screen is the
        // origin world's — and Minecraft.currentServer can still be carrying the previous
        // multiplayer session, so remoteServer is true and singleplayer is false. Every
        // OTHER term of the predicate passes. Without liveLssSession this would derive a
        // bucket, and /lss reset's Voxy half would take the origin server's real LOD store
        // as a wipe target.
        var replay = new WorldSeedKey.Context(true, false, true, false, false, true, 42L);
        assertTrue(WorldSeedKey.keyableSeed(replay).isEmpty(),
                "no live LSS session -> no seed bucket, whatever currentServer still holds");
        assertEquals(ADDR, WorldSeedKey.choose(replay, ADDR));
    }

    @Test
    void theSessionTermIsIndependentOfTheSwitch() {
        // A22 asks keyableSeed() with the switch forced off; the guard must still hold
        // there, or the reset path would be the one place the replay shape leaks through.
        var replay = new WorldSeedKey.Context(false, false, true, false, false, true, 42L);
        assertTrue(WorldSeedKey.keyableSeed(replay).isEmpty());
    }

    @Test
    void seedKeyAndChooseAgreeOnEveryShape() {
        for (boolean live : new boolean[] {false, true}) {
            for (boolean remote : new boolean[] {false, true}) {
                for (boolean realm : new boolean[] {false, true}) {
                    for (boolean sp : new boolean[] {false, true}) {
                        for (boolean avail : new boolean[] {false, true}) {
                            for (long seed : new long[] {0L, 1L, -1L}) {
                                var ctx = new WorldSeedKey.Context(
                                        true, live, remote, realm, sp, avail, seed);
                                String chosen = WorldSeedKey.choose(ctx, ADDR);
                                assertEquals(WorldSeedKey.seedKey(ctx).orElse(ADDR), chosen,
                                        "choose() must be seedKey()-or-fallback, nothing "
                                                + "else: " + ctx);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyNonSwitchTermIsIndividuallyNecessary() {
        // One flipped bit per row, from the one shape that DOES seed-key. Each must veto on
        // its own — a predicate that only fails on combinations is a predicate with a hole.
        var ok = new WorldSeedKey.Context(true, true, true, false, false, true, 42L);
        assertEquals("world-000000000000002a", WorldSeedKey.choose(ok, ADDR));
        record Row(String term, WorldSeedKey.Context ctx) {}
        for (var row : java.util.List.of(
                new Row("liveLssSession", new WorldSeedKey.Context(
                        true, false, true, false, false, true, 42L)),
                new Row("remoteServer", new WorldSeedKey.Context(
                        true, true, false, false, false, true, 42L)),
                new Row("realm", new WorldSeedKey.Context(
                        true, true, true, true, false, true, 42L)),
                new Row("singleplayer", new WorldSeedKey.Context(
                        true, true, true, false, true, true, 42L)),
                new Row("seedAvailable", new WorldSeedKey.Context(
                        true, true, true, false, false, false, 42L)),
                new Row("seed != 0", new WorldSeedKey.Context(
                        true, true, true, false, false, true, 0L)))) {
            assertEquals(ADDR, WorldSeedKey.choose(row.ctx(), ADDR),
                    row.term() + " alone must veto seed keying");
            assertTrue(WorldSeedKey.keyableSeed(row.ctx()).isEmpty(), row.term());
        }
    }
}
