package dev.vox.lss.mixin.sodium;

import dev.vox.lss.config.menu.LegacyOptionsScreenHandle;
import dev.vox.lss.config.menu.LegacySodiumPage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The legacy Sodium (0.6/0.7) options-screen hook (sodium-options-page-generations-plan.md
 * D5): appends the LSS pages to the screen's page list at the end of its constructor —
 * the injection point every consumer sees (Sodium Extra, Iris, Reese's Sodium Options
 * take the list from the constructed screen), rendered by the reflective
 * {@link LegacySodiumPage} so this mixin binds NO Sodium type.
 *
 * <p>{@code @Pseudo} + a STRING target: the class exists only on Sodium ≤0.7 (0.8 deleted
 * it — the modern config API renders the same catalog there), so on 0.8+ and Sodium-less
 * clients Mixin silently skips this mixin; no config plugin, and therefore no plugin-time
 * class load that could define the target before the hook attaches (review A-1/B-2).
 * {@code remap = false}: Sodium is not an MC class. The handler takes only the
 * {@code CallbackInfo} (constructor args omitted) so the hook stays MC-type-free, and it
 * injects NO MC-inherited method (an {@code init} target could not be remapped on the
 * loom-remap lines). Lives in its own non-required config
 * ({@code lss-sodium-legacy.mixins.json}) — an apply failure degrades to "no page".
 *
 * <p>Same-FQN TWIN in the neoforge tree — keep byte-identical (pinned).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI", remap = false)
public abstract class SodiumLegacyOptionsHook implements LegacyOptionsScreenHandle {

    @Shadow
    @Final
    private List<Object> pages;

    @Unique
    private List<Object> lss$injected;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lss$injectOptionPages(CallbackInfo ci) {
        List<Object> built = LegacySodiumPage.build();
        if (!built.isEmpty()) {
            this.pages.addAll(built);
        }
        this.lss$injected = built;
    }

    @Override
    public List<Object> lss$injectedPages() {
        List<Object> injected = this.lss$injected;
        return injected == null ? List.of() : injected;
    }
}
