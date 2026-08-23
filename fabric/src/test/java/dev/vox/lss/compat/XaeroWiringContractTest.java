package dev.vox.lss.compat;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring pins for the Xaero map bridge (the {@code SaveHookContractTest}/
 * {@code LanHookContractTest} family): the whole feature hangs off THREE call
 * sites in shared glue — delete any of them and every behavioral test in
 * {@code XaeroMapCompatTest} stays green while the live bridge silently stops
 * (the queue fills to its bounds and drops forever, or the mod is never
 * detected at all). Source-regex pins, loader-neutral: both loaders call the
 * same {@code ClientNetGlue}/{@code ModCompat} bodies.
 */
class XaeroWiringContractTest {

    @Test
    void theClientTickGlueDrivesThePump() throws IOException {
        String glue = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
        int tickBody = glue.indexOf("public static void onEndClientTick()");
        assertTrue(tickBody >= 0, "onEndClientTick moved — retarget this pin");
        assertTrue(glue.indexOf("ModCompat.clientTick()", tickBody) > tickBody,
                "onEndClientTick must call ModCompat.clientTick() — the bridge's only pump");
    }

    @Test
    void theDisconnectGlueTearsDownTheSession() throws IOException {
        String glue = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
        int disconnectBody = glue.indexOf("public static void onDisconnect()");
        assertTrue(disconnectBody >= 0, "onDisconnect moved — retarget this pin");
        int tickBody = glue.indexOf("public static void onEndClientTick()");
        int end = tickBody > disconnectBody ? tickBody : glue.length();
        assertTrue(glue.substring(disconnectBody, end).contains("ModCompat.onDisconnect()"),
                "onDisconnect must call ModCompat.onDisconnect() — queue/latch/registration"
                        + " teardown (a stale queue can leak one tile into the NEXT server's map)");
    }

    @Test
    void modCompatForwardsToTheBridge() throws IOException {
        String modCompat = Files.readString(SourcePaths.mainSource(
                "dev/vox/lss/compat/ModCompat.java"));
        assertTrue(modCompat.contains("isModLoaded(\"xaeroworldmap\")"),
                "init must gate on the xaeroworldmap mod id (identical on both loaders)");
        assertTrue(modCompat.contains("XaeroMapCompat.init()"),
                "init must initialize the bridge");
        assertTrue(modCompat.contains("XaeroMapCompat.clientTick()"),
                "clientTick must forward to the bridge's pump");
        assertTrue(modCompat.contains("XaeroMapCompat.onDisconnect()"),
                "onDisconnect must forward to the bridge's session teardown");
    }
}
