package dev.vox.lss.networking.client;

import dev.vox.lss.seed.WorldSeedKey;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #1 where the key meets the STORE: the seed key must be an ordinary partition key
 * with no privileges — sanitised like any other, contained like any other — and
 * {@code /lss reset} must clear BOTH structures a connection can own (A22).
 *
 * <p>The A22 half is not defensive tidiness. Two things can move a server between
 * structures: flipping {@code useWorldSeedCacheKey}, and the {@code seed == 0} fallback.
 * A flush that cleared only the ACTIVE bucket would leave the other one's stamps on disk;
 * after the next flip those stamps declare terrain "fresh enough" while the LOD store
 * that held the columns has been wiped — a permanent hole no re-scan heals. That is the
 * exact failure the reset command exists to prevent, so it is pinned here.
 */
class SeedCacheKeyStoreTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<Level> dim(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    private static LodRequestManager sessionKeyedBy(String cacheKey) {
        var manager = new LodRequestManager();
        manager.onSessionConfig(
                new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 64, true),
                cacheKey);
        return manager;
    }

    private static Long2LongOpenHashMap stamps(long pos, long ts) {
        var map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1L);
        map.put(pos, ts);
        return map;
    }

    // ---- the key is an ordinary key ----

    @Test
    void theSeedKeyIsAlreadyFilePathSafeAndSanitisationLeavesItAlone() {
        // Not a licence to skip sanitising — the store still runs it. This asserts the
        // format was CHOSEN so that sanitisation is the identity, which is what makes the
        // on-disk directory name equal the documented "world-<16 hex>" contract rather
        // than some underscored derivative of it.
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0xffL}) {
            String key = WorldSeedKey.format(seed);
            assertEquals(key, ColumnCacheStore.sanitizeForFilePath(key),
                    "the seed key must survive sanitisation verbatim: " + key);
        }
    }

    @Test
    void sanitisationStillRunsAfterKeySelectionForAHostileAddress() {
        // Switch ON but the seed unusable (a waiting room sending 0): choose() hands back
        // the SERVER'S OWN string, so the store's sanitiser is still the only thing
        // between a hostile server name and the filesystem. Selecting a key must never be
        // mistaken for validating one.
        String hostile = "seedkey-evil/../../traversal-target";
        var ctx = new WorldSeedKey.Context(true, true, true, false, false, true, 0L);
        String chosen = WorldSeedKey.choose(ctx, hostile);
        assertEquals(hostile, chosen, "seed 0 falls back to the address, unmodified");

        String sanitized = ColumnCacheStore.sanitizeForFilePath(chosen);
        assertFalse(sanitized.contains("/"), sanitized);
        assertFalse(sanitized.contains("\\"), sanitized);

        ColumnCacheStore.save(chosen, dim("hostile"), stamps(1L, 5L));
        Path root = ColumnCacheStore.cacheRoot().toAbsolutePath().normalize();
        Path written = root.resolve(sanitized).toAbsolutePath().normalize();
        assertTrue(written.startsWith(root), "the write stayed inside the cache root: " + written);
        assertTrue(Files.exists(written), "and it is where the sanitiser said it would be");
        ColumnCacheStore.clearForServer(chosen);
    }

    @Test
    void aSeedKeyedCacheRoundTripsLikeAnyOther() {
        String key = WorldSeedKey.format(0x0123456789abcdefL);
        var dimension = dim("seedroundtrip");
        ColumnCacheStore.save(key, dimension, stamps(42L, 1234L));

        assertEquals(1234L, ColumnCacheStore.load(key, dimension).get(42L));
        assertTrue(Files.isDirectory(ColumnCacheStore.cacheRoot().resolve(key)),
                "the bucket directory is named exactly world-0123456789abcdef");

        ColumnCacheStore.clearForServer(key);
        assertTrue(ColumnCacheStore.load(key, dimension).isEmpty());
    }

    @Test
    void theAddressBucketAndTheSeedBucketAreDifferentDirectories() {
        // The whole point of the ticket, stated as a store fact: two entry addresses that
        // used to own two caches now own one, and it is not either address's directory.
        String key = WorldSeedKey.format(0xabcdL);
        assertFalse(ColumnCacheStore.sanitizeForFilePath("mc.example.com:25565").equals(key));
        assertFalse(ColumnCacheStore.sanitizeForFilePath("eu.example.com:25565").equals(key));
    }

    // ---- A22: /lss reset clears both structures ----

    @Test
    void flushCacheClearsTheAddressBucketAndTheSeedBucket() {
        var dimension = dim("a22both");
        String addressKey = "a22.example.com:25565";
        String seedKey = WorldSeedKey.format(0x5eedL);

        ColumnCacheStore.save(addressKey, dimension, stamps(7L, 700L));
        ColumnCacheStore.save(seedKey, dimension, stamps(8L, 800L));
        assertEquals(700L, ColumnCacheStore.load(addressKey, dimension).get(7L));
        assertEquals(800L, ColumnCacheStore.load(seedKey, dimension).get(8L));

        var manager = sessionKeyedBy(seedKey);              // the session is seed-keyed...
        manager.setAlternateCacheKeys(List.of(addressKey)); // ...and the address bucket is the OTHER structure
        manager.flushCache();

        assertTrue(ColumnCacheStore.load(seedKey, dimension).isEmpty(),
                "the active bucket is cleared (pre-existing behaviour)");
        assertTrue(ColumnCacheStore.load(addressKey, dimension).isEmpty(),
                "A22: the address bucket must go too, or its stamps outlive the wiped store");
    }

    @Test
    void flushCacheClearsTheSeedBucketWhenTheSessionIsAddressKeyed() {
        // The mirror case — the switch is OFF, so the seed bucket is the leftover.
        var dimension = dim("a22mirror");
        String addressKey = "a22mirror.example.com:25565";
        String seedKey = WorldSeedKey.format(0x5eed2L);

        ColumnCacheStore.save(addressKey, dimension, stamps(7L, 700L));
        ColumnCacheStore.save(seedKey, dimension, stamps(8L, 800L));

        var manager = sessionKeyedBy(addressKey);
        manager.setAlternateCacheKeys(List.of(seedKey));
        manager.flushCache();

        assertTrue(ColumnCacheStore.load(addressKey, dimension).isEmpty());
        assertTrue(ColumnCacheStore.load(seedKey, dimension).isEmpty(),
                "A22: the seed bucket survives a switch flip, so the flush must reach it");
    }

    @Test
    void flushCacheWithNoAlternateBucketIsUnchangedFromBeforeTheTicket() {
        var dimension = dim("a22none");
        String addressKey = "a22none.example.com:25565";
        ColumnCacheStore.save(addressKey, dimension, stamps(7L, 700L));

        var manager = sessionKeyedBy(addressKey);
        assertEquals(List.of(), manager.alternateCacheKeysForTest(),
                "no alternate bucket is the default — a session that cannot derive a seed "
                        + "behaves exactly as it did before #1");
        manager.flushCache();

        assertTrue(ColumnCacheStore.load(addressKey, dimension).isEmpty());
    }

    @Test
    void anAlternateKeyEqualToTheActiveOneIsNotClearedTwice() {
        var dimension = dim("a22dedupe");
        String key = "a22dedupe.example.com:25565";
        ColumnCacheStore.save(key, dimension, stamps(7L, 700L));

        var manager = sessionKeyedBy(key);
        manager.setAlternateCacheKeys(List.of(key));
        manager.flushCache();

        assertTrue(ColumnCacheStore.load(key, dimension).isEmpty());
    }
}
