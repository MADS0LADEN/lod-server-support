package dev.vox.lss.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cacheAddressAliases} load validation (plan §2.2): a malformed group drops
 * WHOLE at {@code validate()} and the FIELD is rewritten to the survivors, so session
 * code re-reading it gets an already-clean shape — the {@code crossVersionBlockFallbacks}
 * fail-open convention. GSON-null shapes restore the empty default.
 */
class ClientAliasConfigValidationTest {

    @Test
    void aNullFieldRestoresTheEmptyDefault() {
        var c = new LSSClientConfig();
        c.cacheAddressAliases = null;
        c.validate();
        assertEquals(List.of(), c.cacheAddressAliases);
    }

    @Test
    void malformedGroupsDropWholeAndTheSurvivorsAreRewrittenInPlace() {
        var c = new LSSClientConfig();
        c.cacheAddressAliases = new ArrayList<>(List.of(
                new ArrayList<>(List.of("good.example.com", "alt.good.example.com")),
                new ArrayList<>(List.of("bad.example.com:25565", "alt.bad.example.com")),
                new ArrayList<>(Arrays.asList("half.example.com", (String) null))));
        c.validate();
        assertEquals(List.of(List.of("good.example.com", "alt.good.example.com")),
                c.cacheAddressAliases,
                "the port-bearing-canonical and null-entry groups drop whole; the good "
                        + "group survives untouched");
    }

    @Test
    void theDefaultsCarryNoGroupsAndSubBucketsOn() {
        var c = new LSSClientConfig();
        c.validate();
        assertEquals(List.of(), c.cacheAddressAliases);
        assertTrue(c.useWorldSubBuckets,
                "the world axis ships ON — (address, seed) can only be finer than any "
                        + "address-derived consumer partition (plan §2.3)");
    }

    @Test
    void validateIsIdempotentOnACleanField() {
        var c = new LSSClientConfig();
        c.cacheAddressAliases = new ArrayList<>(List.of(
                new ArrayList<>(List.of("a.example.com", "b.example.com"))));
        c.validate();
        var afterFirst = List.copyOf(c.cacheAddressAliases);
        c.validate();
        assertEquals(afterFirst, c.cacheAddressAliases,
                "validate() re-runs on every load — a clean field must pass unchanged");
    }
}
