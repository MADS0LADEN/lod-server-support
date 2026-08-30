package dev.vox.lss.paper;

import net.minecraft.server.level.ServerPlayer;

/** Per-world LOD distance resolution for Paper (Bukkit world name, then dimension id). */
final class PaperWorldLod {
    static int distance(PaperConfig config, ServerPlayer player) {
        return config.lodDistanceForWorld(worldName(player), dimensionId(player));
    }

    static String worldName(ServerPlayer player) {
        try {
            var entity = player.getBukkitEntity();
            if (entity == null) return null;
            var world = entity.getWorld();
            return world == null ? null : world.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String dimensionId(ServerPlayer player) {
        try {
            var level = player.level();
            if (level == null) return null;
            var dim = level.dimension();
            return dim == null ? null : dim.identifier().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private PaperWorldLod() {}
}
