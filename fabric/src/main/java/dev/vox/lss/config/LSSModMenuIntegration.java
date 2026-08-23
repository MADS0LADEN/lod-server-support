package dev.vox.lss.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.menu.LegacyOptionsScreenHandle;
import dev.vox.lss.config.menu.SodiumGeneration;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

/**
 * ModMenu's "Configure" button — a GENERATION SWITCH (sodium-options-page-generations-plan.md
 * D6), fully reflective so it compiles on lines with no 0.8 Sodium artifact and fails
 * soft on Sodium-internal drift:
 * <ul>
 *   <li>MODERN (0.8+): deep-link into Sodium's settings screen opened on our first
 *       page — {@code ConfigManager.CONFIG.getModOptions()} → our mod's entry →
 *       {@code VideoSettingsScreen.createScreen(parent, page)} (internal 0.8 classes,
 *       the pre-existing binding made reflective);</li>
 *   <li>LEGACY (0.6/0.7): {@code SodiumOptionsGUI.createScreen(parent)} — the
 *       constructor hook has already injected our pages — then, when the screen is our
 *       {@link LegacyOptionsScreenHandle} (not Sodium's config-corrupted screen), a
 *       PRE-INIT selection of our first page by writing the private non-final
 *       {@code currentPage} field. Never {@code setPage} here: it rebuilds the GUI
 *       through {@code Screen.font}, which MC assigns only in {@code init()} (review A-3).
 *       The field write is the one {@code setAccessible} in the design and is wrapped
 *       fail-soft — on any failure the screen opens on Sodium's default tab and the LSS
 *       tab is one click away;</li>
 *   <li>NONE: null (ModMenu shows no config screen — unchanged behavior).</li>
 * </ul>
 * Any throwable → null (the pre-existing contract).
 */
public class LSSModMenuIntegration implements ModMenuApi {

    private static final String MODERN_CONFIG_MANAGER = ".client.config.ConfigManager";
    private static final String MODERN_OPTION_PAGE = ".client.config.structure.OptionPage";
    private static final String MODERN_SCREEN = ".client.gui.VideoSettingsScreen";

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try {
                return switch (SodiumGeneration.detect()) {
                    case MODERN -> modernScreen(parent);
                    case LEGACY -> legacyScreen(parent);
                    case NONE -> null;
                };
            } catch (Throwable e) {
                return null;
            }
        };
    }

    private static Screen modernScreen(Screen parent) throws ReflectiveOperationException {
        String prefix = SodiumGeneration.CAFFEINE_PREFIX;
        Class<?> managerClass = Class.forName(prefix + MODERN_CONFIG_MANAGER);
        Object config = managerClass.getField("CONFIG").get(null);
        Collection<?> mods = (Collection<?>) config.getClass().getMethod("getModOptions").invoke(config);
        for (Object mod : mods) {
            String configId = (String) mod.getClass().getMethod("configId").invoke(mod);
            if (!LSSConstants.MOD_ID.equals(configId)) {
                continue;
            }
            List<?> pages = (List<?>) mod.getClass().getMethod("pages").invoke(mod);
            if (pages.isEmpty()) {
                return null;
            }
            Class<?> pageClass = Class.forName(prefix + MODERN_OPTION_PAGE);
            Class<?> screenClass = Class.forName(prefix + MODERN_SCREEN);
            return (Screen) screenClass.getMethod("createScreen", Screen.class, pageClass)
                    .invoke(null, parent, pages.get(0));
        }
        return null;
    }

    private static Screen legacyScreen(Screen parent) throws ReflectiveOperationException {
        Class<?> guiClass = Class.forName(SodiumGeneration.legacyPrefix() + SodiumGeneration.LEGACY_SCREEN_SUFFIX);
        Screen screen = (Screen) guiClass.getMethod("createScreen", Screen.class).invoke(null, parent);
        if (screen instanceof LegacyOptionsScreenHandle handle) {
            List<Object> injected = handle.lss$injectedPages();
            if (!injected.isEmpty()) {
                try {
                    Field currentPage = guiClass.getDeclaredField("currentPage");
                    currentPage.setAccessible(true);
                    currentPage.set(screen, injected.get(0));
                } catch (Throwable ignored) {
                    // Default tab it is — the LSS tab is still one click away.
                }
            }
        }
        return screen;
    }
}
