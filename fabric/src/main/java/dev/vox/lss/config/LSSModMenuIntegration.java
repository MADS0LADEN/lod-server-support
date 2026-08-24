package dev.vox.lss.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.vox.lss.config.menu.SodiumConfigScreens;

/**
 * ModMenu's "Configure" button → {@link SodiumConfigScreens#open} (the reflective
 * generation switch — sodium-options-page-generations-plan.md D6). Kept to this one
 * line so the switch itself compiles and unit-tests without ModMenu on the class path
 * (ModMenu is {@code compileOnly}; this class cannot load under fabric-loader-junit).
 * A null screen is ModMenu's documented "no config screen" (it null-guards the
 * {@code setScreen}), i.e. the pre-existing contract.
 */
public class LSSModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SodiumConfigScreens::open;
    }
}
