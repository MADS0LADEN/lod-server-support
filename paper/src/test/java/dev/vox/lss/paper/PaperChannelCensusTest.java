package dev.vox.lss.paper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Paper mirror of the Fabric {@code ClientReceiverCensusTest} lesson (final review
 * G1): a C2S channel added to {@code LSSConstants} and {@code dispatchPluginMessage}
 * but forgotten in {@code registerChannels} compiles, boots, and then silently never
 * receives a frame — Bukkit's Messenger drops unregistered incoming channels with no
 * error anywhere. The census chain (both WireParityTests, the NeoForge registrar
 * census, the Fabric receiver censuses S2C AND C2S — the C2S half added by the final
 * panel) covers the other links; THIS one exists because {@code LSSPaperPluginGlueTest}
 * stubs {@code registerChannels} to a no-op, so the real registration set had no
 * automated pin. Source-scan (the contract-test idiom) over {@code LSSPaperPlugin}:
 * the incoming registrations and the dispatch cases must be the SAME set (scope note:
 * both sides derive from that one file — a C2S constant neither registered nor
 * dispatched is the Paper WireParityTest census's catch, not this one's).
 */
class PaperChannelCensusTest {

    private static final Pattern REGISTER_INCOMING = Pattern.compile(
            "registerIncomingPluginChannel\\(\\s*[\\w.]+,\\s*LSSConstants\\.(CHANNEL_\\w+)");
    private static final Pattern DISPATCH_CASE = Pattern.compile(
            "case\\s+LSSConstants\\.(CHANNEL_\\w+)\\s*->");

    private static Path source(String moduleRelative) {
        var p = Path.of(moduleRelative);
        if (Files.exists(p)) return p;
        return Path.of("paper").resolve(moduleRelative);
    }

    @Test
    void registeredIncomingChannelsMatchTheDispatchCases() throws Exception {
        String plugin = Files.readString(source(
                "src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java"));

        Set<String> registered = collect(REGISTER_INCOMING.matcher(plugin));
        Set<String> dispatched = collect(DISPATCH_CASE.matcher(plugin));

        assertTrue(registered.size() >= 5,
                "expected the full C2S surface registered, found only " + registered);
        assertEquals(dispatched, registered,
                "every dispatchPluginMessage case needs a registerIncomingPluginChannel "
                        + "(an unregistered channel is silently never delivered by "
                        + "Bukkit's Messenger), and every registration needs a dispatch "
                        + "case (an orphan registration is dead wire surface)");
        // Growth guard (the Fabric census idiom): every registration call must parse
        // into a recognized LSSConstants channel name — a registration written another
        // way would hide from this census and green while unpinned. The lookbehind
        // keeps unregisterIncomingPluginChannel (the disable path) out of the count.
        int registerCalls = plugin.split("(?<!un)registerIncomingPluginChannel\\(", -1).length - 1;
        assertEquals(registerCalls, registered.size(),
                "every registerIncomingPluginChannel call must reference an "
                        + "LSSConstants.CHANNEL_* constant this census recognizes");
    }

    private static Set<String> collect(Matcher m) {
        var found = new LinkedHashSet<String>();
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }
}
