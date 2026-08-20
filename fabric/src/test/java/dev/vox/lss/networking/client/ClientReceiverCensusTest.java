package dev.vox.lss.networking.client;

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
 * Every S2C payload registered in the Fabric {@code LSSNetworking} must have a
 * {@code registerGlobalReceiver} in {@code LSSClientNetworking} — a payload TYPE with no
 * receiver compiles, registers, negotiates, and then silently drops every frame the
 * server sends (found live by the first warm-rejoin-summary soak: the region-summary
 * frame arrived and vanished, zero client counters, no error anywhere). The channel
 * census pins channels ↔ payloads; THIS pins payloads ↔ handlers. Source-scan (the
 * SaveHookContractTest idiom) because both classes touch Fabric networking statics.
 */
class ClientReceiverCensusTest {

    private static final Pattern S2C_REGISTRATION = Pattern.compile(
            "clientboundPlay\\(\\)\\.register\\(\\s*(?:[\\w.]*\\bpayloads\\.)?(\\w+S2CPayload)\\.TYPE");
    private static final Pattern RECEIVER = Pattern.compile(
            "registerGlobalReceiver\\(\\s*(?:[\\w.]*\\bpayloads\\.)?(\\w+S2CPayload)\\.TYPE");

    private static Path source(String moduleRelative) {
        var p = Path.of(moduleRelative);
        if (Files.exists(p)) return p;
        return Path.of("fabric").resolve(p);
    }

    @Test
    void everyRegisteredS2CPayloadHasAClientReceiver() throws Exception {
        String networking = Files.readString(source(
                "src/main/java/dev/vox/lss/networking/LSSNetworking.java"));
        String client = Files.readString(source(
                "src/main/java/dev/vox/lss/networking/client/LSSClientNetworking.java"));

        Set<String> registered = collect(S2C_REGISTRATION.matcher(networking));
        Set<String> handled = collect(RECEIVER.matcher(client));

        assertTrue(registered.size() >= 7,
                "expected the full S2C payload surface in LSSNetworking, found only "
                        + registered);
        // Growth guard (P2 integration review m5): the name-extracting regex must
        // account for EVERY clientboundPlay registration — an 8th payload named
        // outside the *S2CPayload convention (or moved out of a payloads package)
        // would otherwise hide from both scans and stay green while unhandled.
        int s2cCalls = networking.split("clientboundPlay\\(\\)\\s*\\.register\\(", -1).length - 1;
        assertEquals(s2cCalls, registered.size(),
                "every clientboundPlay().register call must parse into a payload name "
                        + "this census recognizes — got " + s2cCalls + " calls vs "
                        + registered);
        assertEquals(registered, handled,
                "every S2C payload TYPE needs a registerGlobalReceiver in "
                        + "LSSClientNetworking (an unhandled type silently drops frames), "
                        + "and a receiver without a registration is an orphan");
    }

    private static Set<String> collect(Matcher m) {
        var found = new LinkedHashSet<String>();
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }
}
