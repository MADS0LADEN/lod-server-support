package dev.vox.lss.config;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import dev.vox.lss.common.LSSConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Optional;

public class LSSConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var cfg = LSSClientConfig.CONFIG;
        StorageEventHandler save = cfg::save;

        var container = FabricLoader.getInstance().getModContainer(LSSConstants.MOD_ID);
        var version = container
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        // Display-only branding flows from the jar's OWN descriptor: the Voxy Server Side
        // repackage rewrites fabric.mod.json name/icon (identity fields untouched), so the
        // VSS jar brands this screen without a code fork. MOD_ID stays the registration key.
        var displayName = container
                .map(c -> c.getMetadata().getName())
                .orElse("LOD Server Support");
        var mod = builder.registerModOptions(LSSConstants.MOD_ID, displayName, version)
                .setIcon(iconFromMetadata(container));

        var page = builder.createOptionPage();
        page.setName(Component.translatable("lss.config.page"));

        // Receive Server LODs
        var receiveGroup = builder.createOptionGroup();
        var receiveOption = builder.createBooleanOption(Identifier.parse("lss:receive_server_lods"));
        receiveOption.setName(Component.translatable("lss.config.receive_server_lods"));
        receiveOption.setTooltip(Component.translatable("lss.config.receive_server_lods.tooltip"));
        receiveOption.setImpact(OptionImpact.HIGH);
        receiveOption.setDefaultValue(true);
        receiveOption.setBinding(v -> cfg.receiveServerLods = v, () -> cfg.receiveServerLods);
        receiveOption.setStorageHandler(save);
        receiveGroup.addOption(receiveOption);
        page.addOptionGroup(receiveGroup);

        var enabledDep = new Identifier[]{Identifier.parse("lss:receive_server_lods")};

        // LOD Distance
        var distanceGroup = builder.createOptionGroup();
        var distanceOption = builder.createIntegerOption(Identifier.parse("lss:lod_distance"));
        distanceOption.setName(Component.translatable("lss.config.lod_distance"));
        distanceOption.setTooltip(Component.translatable("lss.config.lod_distance.tooltip"));
        distanceOption.setDefaultValue(0);
        distanceOption.setRange(new Range(0, LSSConstants.MAX_LOD_DISTANCE, 1));
        distanceOption.setValueFormatter(v -> v == 0
                ? Component.translatable("lss.config.lod_distance.server_default")
                : Component.literal(Integer.toString(v)));
        distanceOption.setBinding(v -> cfg.lodDistanceChunks = v, () -> cfg.lodDistanceChunks);
        distanceOption.setStorageHandler(save);
        distanceOption.setEnabledProvider(s -> s.readBooleanOption(enabledDep[0]), enabledDep);
        distanceGroup.addOption(distanceOption);
        page.addOptionGroup(distanceGroup);

        // Max LOD download rate — the manual column-rate cap
        // (docs/planning/client-column-rate-cap-design.md). The slider is CURVED (2026-08-14
        // granularity request): the option's int is an INDEX into RATE_STOPS, not the rate,
        // so the low end steps by 10 (a user can pick 20) while the top stays reachable in
        // one drag — the curve + rationale live in RateSliderStops (Sodium-free, test-pinned).
        // Slider top = 3200 because that is where the mechanism provably no-ops (800-budget
        // batches space to exactly the 5-tick fast floor); larger hand-edited values are
        // legal and inert (they display snapped to the top stop but are only rewritten if
        // the user actually moves THIS slider — Sodium writes only modified options).
        // Every nonzero stop round-trips the validate() clamp unchanged: the lowest stop
        // equals the [10, 100000] floor by construction.
        var rateGroup = builder.createOptionGroup();
        var rateOption = builder.createIntegerOption(Identifier.parse("lss:column_rate_limit"));
        rateOption.setName(Component.translatable("lss.config.column_rate_limit"));
        rateOption.setTooltip(Component.translatable("lss.config.column_rate_limit.tooltip"));
        rateOption.setImpact(OptionImpact.LOW);
        rateOption.setDefaultValue(0);
        rateOption.setRange(new Range(0, RateSliderStops.STOPS.length - 1, 1));
        rateOption.setValueFormatter(idx -> idx == 0
                ? Component.translatable("lss.config.column_rate_limit.unlimited")
                : Component.literal(Integer.toString(RateSliderStops.STOPS[idx])));
        rateOption.setBinding(idx -> cfg.lodColumnsPerSecondLimit = RateSliderStops.STOPS[idx],
                () -> RateSliderStops.nearestIndex(cfg.lodColumnsPerSecondLimit));
        rateOption.setStorageHandler(save);
        rateOption.setEnabledProvider(s -> s.readBooleanOption(enabledDep[0]), enabledDep);
        rateGroup.addOption(rateOption);

        // Slow Start on Join (join-slow-start-plan.md, user direction: toggle in the
        // menu, default enabled). Inert while the enableAdaptiveTransferRate umbrella
        // is off (config-file-only key) — the tooltip says so when that is the case
        // at menu build (the SeeU conditional-tooltip precedent).
        var slowStartOption = builder.createBooleanOption(Identifier.parse("lss:join_slow_start"));
        slowStartOption.setName(Component.translatable("lss.config.join_slow_start"));
        slowStartOption.setTooltip(Component.translatable(cfg.enableAdaptiveTransferRate
                ? "lss.config.join_slow_start.tooltip"
                : "lss.config.join_slow_start.tooltip.governor_off"));
        slowStartOption.setImpact(OptionImpact.LOW);
        slowStartOption.setDefaultValue(true);
        slowStartOption.setBinding(v -> cfg.enableJoinSlowStart = v, () -> cfg.enableJoinSlowStart);
        slowStartOption.setStorageHandler(save);
        slowStartOption.setEnabledProvider(s -> s.readBooleanOption(enabledDep[0]), enabledDep);
        rateGroup.addOption(slowStartOption);
        page.addOptionGroup(rateGroup);

        // Xaero's World Map bridge (issue #223, xaero-map-bridge-plan.md §2.9): write
        // received LODs into Xaero's map. Checked LIVE (flip applies mid-session);
        // with Xaero absent the toggle is inert — say so where the user is looking
        // (the join_slow_start/SeeU conditional-tooltip precedent).
        var xaeroGroup = builder.createOptionGroup();
        boolean xaeroPresent = FabricLoader.getInstance().isModLoaded("xaeroworldmap");
        var xaeroOption = builder.createBooleanOption(Identifier.parse("lss:xaero_map_bridge"));
        xaeroOption.setName(Component.translatable("lss.config.xaero_map_bridge"));
        xaeroOption.setTooltip(Component.translatable(xaeroPresent
                ? "lss.config.xaero_map_bridge.tooltip"
                : "lss.config.xaero_map_bridge.tooltip.not_installed"));
        xaeroOption.setImpact(OptionImpact.LOW);
        xaeroOption.setDefaultValue(false);
        xaeroOption.setBinding(v -> cfg.enableXaeroMapBridge = v, () -> cfg.enableXaeroMapBridge);
        xaeroOption.setStorageHandler(save);
        xaeroOption.setEnabledProvider(s -> s.readBooleanOption(enabledDep[0]), enabledDep);
        xaeroGroup.addOption(xaeroOption);
        page.addOptionGroup(xaeroGroup);

        // ---- Far players (E2, FARP §3.3): its own page — a distinct feature with
        //      its own privacy semantics, not another LOD slider ----
        // Save handler for far-player options: persist AND push the prefs NOW (E2
        // review M2/m4 — a mid-session "Share My Position" flip must not wait for a
        // rejoin; maybeSendPrefs's changed-guard makes redundant calls free).
        StorageEventHandler fpSave = () -> {
            cfg.save();
            dev.vox.lss.networking.client.FarPlayerClientSupport.onClientConfigChanged();
        };
        var fpPage = builder.createOptionPage();
        fpPage.setName(Component.translatable("lss.config.far_players.page"));

        var fpGroup = builder.createOptionGroup();
        boolean seeu = dev.vox.lss.networking.client.FarPlayerClientSupport.isSeeuPresent();
        var fpEnabled = builder.createBooleanOption(Identifier.parse("lss:far_players_enabled"));
        fpEnabled.setName(Component.translatable("lss.config.far_players_enabled"));
        // With SeeU installed the coexist gate overrides this toggle — say so where
        // the user is looking (E3, the plan §6 discoverability requirement).
        fpEnabled.setTooltip(Component.translatable(seeu
                ? "lss.config.far_players_enabled.tooltip.seeu"
                : "lss.config.far_players_enabled.tooltip"));
        fpEnabled.setImpact(OptionImpact.LOW);
        fpEnabled.setDefaultValue(true);
        fpEnabled.setBinding(v -> cfg.farPlayersEnabled = v, () -> cfg.farPlayersEnabled);
        fpEnabled.setStorageHandler(fpSave);
        fpGroup.addOption(fpEnabled);

        var fpDep = new Identifier[]{Identifier.parse("lss:far_players_enabled")};

        // "Share your position with other players' LOD view" — the E2 defaults
        // decision's wording obligation (decisions log 2026-08-13): plain words, no
        // jargon, because default-true at server-default-on means installing = sharing.
        var fpShare = builder.createBooleanOption(Identifier.parse("lss:far_players_share_self"));
        fpShare.setName(Component.translatable("lss.config.far_players_share_self"));
        fpShare.setTooltip(Component.translatable("lss.config.far_players_share_self.tooltip"));
        fpShare.setImpact(OptionImpact.LOW);
        fpShare.setDefaultValue(true);
        fpShare.setBinding(v -> cfg.farPlayersShareSelf = v, () -> cfg.farPlayersShareSelf);
        fpShare.setStorageHandler(fpSave);
        fpGroup.addOption(fpShare);

        var fpTags = builder.createBooleanOption(Identifier.parse("lss:far_players_name_tags"));
        fpTags.setName(Component.translatable("lss.config.far_players_name_tags"));
        fpTags.setTooltip(Component.translatable("lss.config.far_players_name_tags.tooltip"));
        fpTags.setImpact(OptionImpact.LOW);
        fpTags.setDefaultValue(true);
        fpTags.setBinding(v -> cfg.farPlayersNameTags = v, () -> cfg.farPlayersNameTags);
        fpTags.setStorageHandler(fpSave);
        fpTags.setEnabledProvider(s -> s.readBooleanOption(fpDep[0]), fpDep);
        fpGroup.addOption(fpTags);

        var fpRender = builder.createIntegerOption(Identifier.parse("lss:far_players_render_distance"));
        fpRender.setName(Component.translatable("lss.config.far_players_render_distance"));
        fpRender.setTooltip(Component.translatable("lss.config.far_players_render_distance.tooltip"));
        fpRender.setImpact(OptionImpact.LOW);
        fpRender.setDefaultValue(0);
        fpRender.setRange(new Range(0, 16384, 128));
        fpRender.setValueFormatter(v -> v == 0
                ? Component.translatable("lss.config.far_players_render_distance.server")
                : Component.literal(Integer.toString(v)));
        fpRender.setBinding(v -> cfg.farPlayersMaxRenderDistanceBlocks = v,
                () -> cfg.farPlayersMaxRenderDistanceBlocks);
        fpRender.setStorageHandler(fpSave);
        fpRender.setEnabledProvider(s -> s.readBooleanOption(fpDep[0]), fpDep);
        fpGroup.addOption(fpRender);

        if (seeu) {
            var fpWithSeeU = builder.createBooleanOption(
                    Identifier.parse("lss:far_players_with_seeu"));
            fpWithSeeU.setName(Component.translatable("lss.config.far_players_with_seeu"));
            fpWithSeeU.setTooltip(Component.translatable("lss.config.far_players_with_seeu.tooltip"));
            fpWithSeeU.setImpact(OptionImpact.LOW);
            fpWithSeeU.setDefaultValue(false);
            fpWithSeeU.setBinding(v -> cfg.farPlayersWithSeeU = v, () -> cfg.farPlayersWithSeeU);
            fpWithSeeU.setStorageHandler(fpSave);
            fpGroup.addOption(fpWithSeeU);
        }

        fpPage.addOptionGroup(fpGroup);

        // Main LSS page first (n12) — far players is the secondary page.
        mod.addPage(page);
        mod.addPage(fpPage);
    }

    /** The descriptor's `icon` path as a resource Identifier ("assets/&lt;ns&gt;/&lt;path&gt;" →
     *  "&lt;ns&gt;:&lt;path&gt;"), falling back to the LSS icon. Display-only — a malformed or
     *  missing path must degrade, never throw. */
    private static Identifier iconFromMetadata(Optional<ModContainer> container) {
        return container.flatMap(c -> c.getMetadata().getIconPath(32))
                .filter(p -> p.startsWith("assets/"))
                .map(p -> p.substring("assets/".length()))
                .map(p -> {
                    int slash = p.indexOf('/');
                    return slash > 0
                            ? Identifier.tryParse(p.substring(0, slash) + ":" + p.substring(slash + 1))
                            : null;
                })
                .filter(Objects::nonNull)
                .orElse(Identifier.parse("lss:icon.png"));
    }
}
