package dev.vox.lss.config.menu;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

/**
 * The reflective "open LSS's settings" arms behind ModMenu's Configure button
 * (sodium-options-page-generations-plan.md D6) — a GENERATION SWITCH kept out of
 * {@code LSSModMenuIntegration} so it compiles and unit-tests without ModMenu on the
 * class path:
 * <ul>
 *   <li>MODERN (0.8+): deep-link into Sodium's settings screen opened on our first
 *       page — {@code ConfigManager.CONFIG.getModOptions()} → our mod's entry →
 *       {@code VideoSettingsScreen.createScreen(parent, page)}. These are INTERNAL 0.8
 *       classes (the pre-existing binding, now reflective so this file compiles on lines
 *       with no 0.8 artifact); {@link #MODERN_SURFACE} lists the members so the
 *       resolves-test checks them against the line's real Sodium bytecode;</li>
 *   <li>LEGACY (0.6/0.7): {@code SodiumOptionsGUI.createScreen(parent)} — the
 *       constructor hook has already injected our pages — then, when the screen is our
 *       {@link LegacyOptionsScreenHandle} (not Sodium's config-corrupted screen), a
 *       PRE-INIT selection of our first page by writing the private non-final
 *       {@code currentPage} field. Never {@code setPage} here: it rebuilds the GUI
 *       through {@code Screen.font}, which MC assigns only in {@code init()} (review A-3).
 *       The field write is the one {@code setAccessible} in the design and is wrapped
 *       fail-soft — on any failure the screen opens on Sodium's default tab and the LSS
 *       tab is one click away (under Reese's Sodium Options the selection is dropped by
 *       its screen swap; the tabs still show);</li>
 *   <li>NONE: null (the caller shows no config screen — unchanged behavior).</li>
 * </ul>
 * Every arm is fail-soft: any throwable → null.
 */
public final class SodiumConfigScreens {

    // ---- the 0.8+ internal deep-link surface, relative to the caffeine prefix ----
    public static final String MODERN_CONFIG_MANAGER = ".client.config.ConfigManager";
    public static final String MODERN_CONFIG = ".client.config.structure.Config";
    public static final String MODERN_MOD_OPTIONS = ".client.config.structure.ModOptions";
    public static final String MODERN_OPTION_PAGE = ".client.config.structure.OptionPage";
    public static final String MODERN_SCREEN = ".client.gui.VideoSettingsScreen";

    /** Every 0.8+ member the modern arm binds by name (the resolves-test's checklist). */
    public static final List<LegacySodiumPage.Member> MODERN_SURFACE = List.of(
            new LegacySodiumPage.Member(MODERN_CONFIG_MANAGER, LegacySodiumPage.MemberKind.FIELD, "CONFIG", 0),
            new LegacySodiumPage.Member(MODERN_CONFIG, LegacySodiumPage.MemberKind.METHOD, "getModOptions", 0),
            new LegacySodiumPage.Member(MODERN_MOD_OPTIONS, LegacySodiumPage.MemberKind.METHOD, "configId", 0),
            new LegacySodiumPage.Member(MODERN_MOD_OPTIONS, LegacySodiumPage.MemberKind.METHOD, "pages", 0),
            new LegacySodiumPage.Member(MODERN_SCREEN, LegacySodiumPage.MemberKind.STATIC_METHOD, "createScreen", 2));

    private SodiumConfigScreens() {
    }

    /** The settings screen for this client's Sodium generation, or null. Never throws. */
    public static Screen open(Screen parent) {
        try {
            return switch (SodiumGeneration.detect()) {
                case MODERN -> modernScreen(parent);
                case LEGACY -> legacyScreen(parent);
                case NONE -> null;
            };
        } catch (Throwable e) {
            return null;
        }
    }

    static Screen modernScreen(Screen parent) throws ReflectiveOperationException {
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

    static Screen legacyScreen(Screen parent) throws ReflectiveOperationException {
        Class<?> guiClass = Class.forName(SodiumGeneration.legacyPrefix() + SodiumGeneration.LEGACY_SCREEN_SUFFIX);
        Screen screen = (Screen) guiClass.getMethod("createScreen", Screen.class).invoke(null, parent);
        selectInjectedPage(screen, guiClass);
        return screen;
    }

    /** Pre-init selection of the first injected page; fail-soft (default tab). */
    static void selectInjectedPage(Object screen, Class<?> guiClass) {
        if (!(screen instanceof LegacyOptionsScreenHandle handle)) {
            return;
        }
        List<Object> injected = handle.lss$injectedPages();
        if (injected.isEmpty()) {
            return;
        }
        try {
            Field currentPage = guiClass.getDeclaredField("currentPage");
            currentPage.setAccessible(true);
            currentPage.set(screen, injected.get(0));
        } catch (Throwable ignored) {
            // Default tab it is — the LSS tab is still one click away.
        }
    }
}
