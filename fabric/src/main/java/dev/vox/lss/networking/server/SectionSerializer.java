package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.compat.AntiXrayCompat;
import io.netty.buffer.Unpooled;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Serializes loaded chunk columns into MC-native wire format.
 * Uses {@link LevelChunkSection#write(FriendlyByteBuf)} for block states + biomes,
 * plus raw DataLayer nibble bytes for light data.
 *
 * <p>Must be called on the server thread (reads LightEngine).
 */
public final class SectionSerializer {
    private SectionSerializer() {}

    /**
     * Serialize all non-air sections of a loaded chunk column into MC-native wire format.
     * Returns a {@link LoadedColumnData} with pre-serialized bytes.
     */
    private record SectionInfo(int index, int sectionY, SectionPos sectionPos,
                               DataLayer blLayer, boolean hasBlockLight,
                               DataLayer slLayer, boolean hasSkyLight) {}

    public static LoadedColumnData serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        // One AntiXray-shim scope per COLUMN (not per section): section.write crashes under
        // the AntiXray mod's mixins outside this binding — see AntiXrayCompat.
        return AntiXrayCompat.callSerializing(() -> serializeColumnInner(level, chunk, cx, cz));
    }

    private static LoadedColumnData serializeColumnInner(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();
        var lightEngine = level.getLightEngine();
        var blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        var skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);

        // First pass: collect non-air sections and cache light results. Air-only sections
        // WITH stored non-zero sky light are served too (2026-07-27, black-boundary-faces
        // fix): vanilla's light grid extends one section past terrain, and those boundary
        // air layers are exactly what lights the top/side faces of the terrain below/beside
        // them at a chunk border. A consumer sampling a never-served neighbor section gets
        // light 0 — leaves rendered black from one side. Fully-dark air (caves, deep
        // enclosures) still skips: its layers are null/zero, so the serve set stays
        // bounded to vanilla's own stored-light coverage (~1-2 extra sections per column).
        var includedSections = new java.util.ArrayList<SectionInfo>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null) continue;
            int sectionY = minSectionY + i;
            var sectionPos = SectionPos.of(cx, sectionY, cz);
            var blLayer = blockLightListener.getDataLayerData(sectionPos);
            boolean hasBlockLight = blLayer != null && hasNonZeroData(blLayer);
            var slLayer = skyLightListener.getDataLayerData(sectionPos);
            boolean hasSkyLight = slLayer != null && hasNonZeroData(slLayer);

            if (section.hasOnlyAir() && !hasBlockLight && !hasSkyLight) continue;

            includedSections.add(new SectionInfo(i, sectionY, sectionPos,
                    blLayer, hasBlockLight, slLayer, hasSkyLight));
        }

        // Band rule, same as the NBT path (disk/live byte parity): SKY-lit air serves only
        // within one section of the content band — vanilla's own stored-light coverage —
        // so a void/cleared column's ambient sky can never turn a zero-section CLEAR into
        // a data column. BLOCK-lit air keeps its long-standing unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var info : includedSections) {
            if (!sections[info.index()].hasOnlyAir()) {
                minContent = Math.min(minContent, info.sectionY());
                maxContent = Math.max(maxContent, info.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        includedSections.removeIf(info -> sections[info.index()].hasOnlyAir()
                && !info.hasBlockLight()
                && (noContent || info.sectionY() < lo || info.sectionY() > hi));

        if (includedSections.isEmpty()) {
            return new LoadedColumnData(cx, cz, null, 0);
        }

        // Second pass: serialize using cached results
        var maskEntry = XrayMaskManager.entryForActive(level);
        var maskFactory = maskEntry != null ? NbtSectionSerializer.factoryFor(level.registryAccess()) : null;
        var buf = new FriendlyByteBuf(Unpooled.buffer(sections.length * 1024));
        try {
            buf.writeVarInt(includedSections.size());

            for (var info : includedSections) {
                var section = sections[info.index];
                if (maskEntry != null) {
                    // Masking INSIDE the choke point: probe, generation, and the
                    // DirtyContentFilter hash all see identical masked bytes by construction.
                    var masked = XrayMaskFilter.mask(section, info.sectionY,
                            maskEntry.mask(), maskEntry.kind(), maskFactory);
                    if (masked != section) {
                        section = masked;
                        var manager = XrayMaskManager.current();
                        if (manager != null) manager.countMaskedSection();
                    }
                }

                buf.writeByte(info.sectionY);
                section.write(buf);

                // Block light (cached from pass 1)
                buf.writeBoolean(info.hasBlockLight);
                if (info.hasBlockLight) {
                    buf.writeBytes(info.blLayer.getData());
                }

                // Sky light (cached from pass 1)
                buf.writeBoolean(info.hasSkyLight);
                if (info.hasSkyLight) {
                    buf.writeBytes(info.slLayer.getData());
                }
            }

            byte[] serialized = new byte[buf.readableBytes()];
            buf.readBytes(serialized);
            return new LoadedColumnData(cx, cz, serialized, serialized.length);
        } finally {
            buf.release();
        }
    }

    private static boolean hasNonZeroData(DataLayer layer) {
        for (byte b : layer.getData()) {
            if (b != 0) return true;
        }
        return false;
    }
}
