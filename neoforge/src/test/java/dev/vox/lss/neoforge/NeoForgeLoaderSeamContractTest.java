package dev.vox.lss.neoforge;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.vox.lss.neoforge.NeoForgeModuleContractTest.read;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader-seam invariants (plan §1.2), source-pinned because no test JVM
 * can execute NeoForge networking: the C2S size bound, LoaderServices
 * completeness, and the sendIfListening containment.
 *
 * <p><b>The interop matrix these pins encode</b> (verified live at N-3's
 * smokes; each cell names its mechanism):
 * <ul>
 *   <li>NeoForge client → vanilla / LSS-less server: the handshake is an
 *       unprompted FIRST send and NeoForge THROWS on unannounced channels —
 *       {@code sendToServer}'s containment makes it a silent no-op (Fabric
 *       parity).</li>
 *   <li>NeoForge server → vanilla / Fabric-no-LSS client: S2C sends only ever
 *       follow a client handshake, but {@code sendToPlayer} still carries the
 *       {@code hasChannel} pre-check (defense in depth for re-handshake races).</li>
 *   <li>NeoForge client → Fabric LSS server (upstream #1913 exposure): our own
 *       handshake arms the session; if announcement is one-way-broken the
 *       contained send degrades the client to LSS-inert — never a crash.</li>
 *   <li>Fabric client → NeoForge LSS server: `.optional()` keeps login alive;
 *       the channels negotiate normally (the N-2 boot smoke's arm).</li>
 * </ul>
 */
class NeoForgeLoaderSeamContractTest {

    /**
     * The C2S ≤ 32 KiB loader bound (plan §1.2): the want-set batch is
     * MAX_BATCH_CHUNK_REQUESTS × 16 B (packed pos + client timestamp) plus a
     * small envelope; NeoForge refuses larger serverbound custom payloads at
     * the vanilla bound. Build-time pin so a future budget raise cannot
     * silently cross it (the WantSetBudgetInvariantTest pattern).
     */
    @Test
    void wantSetBatchStaysUnderTheNeoForgeC2SBound() {
        int entryBytes = 8 + 8;
        int envelopeMargin = 256;
        int worstCase = LSSConstants.MAX_BATCH_CHUNK_REQUESTS * entryBytes + envelopeMargin;
        assertTrue(worstCase < 32768,
                "the worst-case want-set batch (" + worstCase + " B) must stay under the"
                        + " 32 KiB serverbound custom-payload bound — raising"
                        + " MAX_BATCH_CHUNK_REQUESTS past this breaks every NeoForge client");
    }

    /**
     * LoaderServices completeness (the reflective-completeness-pin pattern,
     * source-flavored — MC param types keep the impl un-instantiable in a bare
     * JUnit JVM): every abstract method the xplat interface declares must be
     * overridden by the NeoForge impl, so an interface growth on the fabric
     * side cannot leave this loader compiling against a default that throws.
     */
    @Test
    void neoForgeImplOverridesEveryLoaderServicesMethod() throws IOException {
        String iface = read("xplat/src/main/java/dev/vox/lss/platform/LoaderServices.java");
        String impl = read("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java");
        Matcher m = Pattern.compile(
                "^\\s{4}(?:boolean|void|Path|String|int|long)\\s+(\\w+)\\(", Pattern.MULTILINE)
                .matcher(iface);
        int found = 0;
        while (m.find()) {
            String method = m.group(1);
            found++;
            assertTrue(Pattern.compile("public\\s+\\w+[\\w<>.\\[\\]]*\\s+" + method + "\\(")
                            .matcher(impl).find(),
                    "NeoForgeLoaderServices must override LoaderServices." + method);
        }
        assertTrue(found >= 5, "the interface-method scan went blind (found " + found
                + ") — fix this test's regex before trusting it");
    }

    /** The sendIfListening containment (plan §1.2): both send paths must carry the
     *  hasChannel pre-check / throw containment — without it a NeoForge client
     *  joining a vanilla server crashes on the handshake send (#160's failure class). */
    @Test
    void sendPathsContainTheUnannouncedChannelThrow() throws IOException {
        String impl = read("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java");
        assertTrue(impl.contains("hasChannel(payload.type())"),
                "sendToPlayer must pre-check channel negotiation (Fabric-parity silent no-op)");
        assertTrue(impl.contains("catch (UnsupportedOperationException"),
                "the pre-check race window still needs the throw contained");
        // The CLIENT send containment lands with the client impl at N-3; this pin
        // will grow the client-side assertions there (tracked in the plan §2 N-3).
    }
}
