package dev.vox.lss.platform;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission seam's per-loader override pins (service-permission-gate-plan.md
 * §4.1 / §8 O1-M3): {@code checkPermission} is a DEFAULT method whose fallback —
 * serve everyone — is indistinguishable from working, so a loader impl that forgets
 * the override ships the gate permanently inert with a green suite. The NeoForge
 * side is covered by its own widened completeness scan; this is the Fabric half,
 * plus the call-site default-value pin.
 */
class LoaderPermissionSeamContractTest {

    private static String source(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath));
    }

    @Test
    void theFabricServerImplOverridesBothPermissionMethods() throws IOException {
        String impl = source("fabric/src/main/java/dev/vox/lss/platform/FabricLoaderServices.java");
        assertTrue(impl.contains("public boolean checkPermission("),
                "FabricLoaderServices must override checkPermission — the default serves "
                        + "everyone and no behavioural test can tell the difference");
        assertTrue(impl.contains("FabricPermissionsBridge.check(player, node, defaultValue)"),
                "…and route it through the reflective bridge");
        assertTrue(impl.contains("public String permissionProviderToken()"),
                "the Gate: diag line's provider token must be the loader's, not \"none\"");
    }

    @Test
    void everyServiceGateCallSitePassesDefaultTrue() throws IOException {
        // A flipped default is a silent server-wide black-out on the two platforms with
        // no plugin.yml to catch it (plan §2.1). Scan every production checkPermission
        // call site for the literal `true` default.
        for (String repoPath : new String[] {
                "xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java",
                "xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java",
        }) {
            String text = source(repoPath);
            int idx = 0;
            while ((idx = text.indexOf("checkPermission(", idx)) >= 0) {
                int close = text.indexOf(")", idx);
                String call = text.substring(idx, close + 1);
                if (!call.contains("boolean defaultValue")) { // skip declarations
                    assertTrue(call.contains("true"),
                            repoPath + " has a checkPermission call without the literal "
                                    + "default-true: " + call);
                }
                idx = close;
            }
        }
    }
}
