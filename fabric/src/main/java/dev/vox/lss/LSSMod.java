package dev.vox.lss;

import dev.vox.lss.common.Brand;
import dev.vox.lss.networking.LSSNetworking;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.trace.MoveTraceBootstrap;
import net.fabricmc.api.ModInitializer;

public class LSSMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // FIRST: resolve display branding before any service/thread/log line is created.
        // Load-bearing beyond logging: the ModInitializer runs before the ClientModInitializer,
        // so on a client this is what guarantees Brand is resolved before LSSClientConfig's static
        // init reads Brand.shortName() to pick its config filename (brandedConfigCandidates). Do
        // not remove as "redundant with LSSClient" — keep branding resolved before any config touch.
        Brand.load(LSSMod.class.getClassLoader());
        // SECOND: the loader seam — before any config/compat touch (they read
        // configDir/isModLoaded through it). Client entrypoint upgrades this to
        // the client-capable impl (entrypoint order: main runs first).
        dev.vox.lss.platform.FabricLoaderServices.installProduction();
        LSSNetworking.registerPayloads();
        LSSServerNetworking.init();
        // Tracer lifecycle hangs off LSSMod, never off RequestProcessingService — it must
        // observe LSS-disabled servers and unregistered players (the E2 control arms).
        MoveTraceBootstrap.init();
        BenchmarkBridge.initServer();
    }
}
