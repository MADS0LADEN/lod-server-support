package dev.vox.lss.common.store;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The A3 pin (review-fixes round): the registry fingerprint is an id-ordered IDENTITY
 * hash on BOTH halves, never a count — the original block half was
 * {@code BLOCK_STATE_REGISTRY.size()}, so an id-permuting registry change of identical
 * total size (a mod swap landing on the same state count) passed the R2-M3 guard and
 * served every warm column as the wrong blocks with no self-heal. Pure-string helper so
 * this pins without a MinecraftServer; both platform services delegate to it
 * (C1's twin-drift protection).
 */
class RegistryFingerprintTest {

    @Test
    void formatIsAHashPairNeverACount() {
        String fp = RegistryFingerprint.of(
                List.of("Block{minecraft:stone}", "Block{minecraft:dirt}"),
                List.of("minecraft:plains"));
        assertTrue(fp.matches("bs:[0-9a-f]+/bio:[0-9a-f]+"),
                "fingerprint must be the bs:<hex>/bio:<hex> hash pair, got: " + fp);
    }

    @Test
    void sameCountDifferentOrderChangesTheFingerprint() {
        String a = RegistryFingerprint.of(List.of("state1", "state2"), List.of("bio"));
        String b = RegistryFingerprint.of(List.of("state2", "state1"), List.of("bio"));
        assertNotEquals(a, b,
                "an id-permuting registry change of identical size must change the"
                        + " fingerprint (the old count was blind to exactly this)");
    }

    @Test
    void sameCountDifferentContentChangesTheFingerprint() {
        String a = RegistryFingerprint.of(List.of("modA:ore[lit=true]"), List.of("bio"));
        String b = RegistryFingerprint.of(List.of("modB:ore[lit=true]"), List.of("bio"));
        assertNotEquals(a, b, "a mod swap landing on the same state count must be seen");
    }

    @Test
    void identicalInputsAreStableAcrossCalls() {
        var states = List.of("s1", "s2", "s3");
        var biomes = List.of("minecraft:plains", "minecraft:desert");
        assertEquals(RegistryFingerprint.of(states, biomes),
                RegistryFingerprint.of(states, biomes),
                "an unchanged registry must never trigger a rebuild");
    }
}
