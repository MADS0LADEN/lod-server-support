package dev.vox.lss.networking.server;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.compat.AntiXrayCompat;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads chunk NBT from disk and serializes sections into MC-native wire format.
 * Used by {@link ChunkDiskReader} for async disk reads.
 */
final class NbtSectionSerializer {
    private NbtSectionSerializer() {}

    private static final byte[] EMPTY = new byte[0];

    /**
     * Seam for the region-file NBT read: FOREGROUND ({@code chunkMap.read}) or BACKGROUND
     * priority, per {@code useBackgroundReadPriority}. Mirrors Paper's {@code ChunkNbtRead}.
     */
    @FunctionalInterface
    interface ChunkNbtRead {
        CompletableFuture<Optional<CompoundTag>> read(int cx, int cz);
    }

    /**
     * Read chunk NBT from disk, verify FULL status, and serialize sections
     * into MC-native wire format. {@code maskEntry} (nullable) is the dimension's x-ray
     * mask, captured by the caller at submit time.
     * Returns the serialized byte array, or null if the chunk is missing/not FULL/empty.
     */
    static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            XrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY) throws Exception {
        var future = read.read(cx, cz);
        var optionalTag = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) return null;
        var chunkNbt = optionalTag.get();
        // The shim scope covers the codec parse AND the section writes (both carry AntiXray
        // mixin injections); it deliberately excludes the blocking read above.
        return AntiXrayCompat.callSerializing(
                () -> serializeChunkNbt(chunkNbt, registryAccess, maskEntry, minSectionY, maxSectionY));
    }

    /** Unmasked flavor — the shape the pre-masking tests and corpus pin. */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess) {
        return serializeChunkNbt(chunkNbt, registryAccess, null);
    }

    /** Range-free flavor (tests + corpus — the committed goldens serialize out-of-world
     *  Y values like -128 and must keep doing so; only the production path gates). */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    XrayMaskManager.MaskEntry maskEntry) {
        return serializeChunkNbt(chunkNbt, registryAccess, maskEntry,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    // Unparseable-section warns are throttled: on the mainstream trigger (an upgraded
    // world's pre-DFU chunks) every affected column re-reads at ~1 Hz until its
    // generation ticket serves it — unthrottled, that is a #32-class console flood.
    private static final dev.vox.lss.common.LogThrottle PARSE_WARN_THROTTLE =
            new dev.vox.lss.common.LogThrottle(60_000);

    /**
     * Serialize a chunk's NBT (as read from a region file) into MC-native wire format.
     * Returns {@code null} if the chunk is not FULL, has no sections, or contains a
     * truly-unparseable section (an authoritative miss — serving a column with a silently
     * missing section would stamp a persistent hole no re-declaration heals; the miss
     * escalates to a generation ticket that loads the chunk through the REAL DataFixer
     * pipeline instead). Returns an empty array if every section is empty. Sections
     * outside {@code [minSectionY, maxSectionY]} are dropped before parsing: vanilla
     * saves light-only entries one section beyond the block range, the live path can
     * never emit them (disk/live parity), and their out-of-world sectionY reaches
     * consumers unchecked. Package-visible for testing.
     */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    XrayMaskManager.MaskEntry maskEntry,
                                    int minSectionY, int maxSectionY) {
        var statusStr = chunkNbt.getStringOr("Status", null);
        if (statusStr == null || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) return null;

        var factory = factoryFor(registryAccess);
        var blockStateCodec = factory.blockStatesContainerCodec();
        var biomeCodec = factory.biomeContainerCodec();
        var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);
        var biomeHolderMap = biomeRegistry.asHolderIdMap();

        var sectionsTag = chunkNbt.getList("sections");
        if (sectionsTag.isEmpty()) return null;

        var sectionsList = sectionsTag.orElseThrow();

        // First pass: parse sections and check if any are non-empty
        record ParsedSection(int sectionY, LevelChunkSection section, byte[] blockLight, byte[] skyLight) {}
        var parsed = new java.util.ArrayList<ParsedSection>(sectionsList.size());

        int[] unparseable = {0};
        for (var sectionElement : sectionsList) {
            var sectionTag = (CompoundTag) sectionElement;
            int sectionY = sectionTag.getIntOr("Y", Integer.MIN_VALUE);
            if (sectionY == Integer.MIN_VALUE) continue;
            // Range gate BEFORE parse: an out-of-range garbage entry must not count as
            // unparseable and condemn a column it would have been dropped from anyway.
            if (sectionY < minSectionY || sectionY > maxSectionY) continue;

            byte[] blockLightData = sectionTag.getByteArray("BlockLight").orElse(EMPTY);
            byte[] skyLightData = sectionTag.getByteArray("SkyLight").orElse(EMPTY);
            var result = parseSection(sectionTag, sectionY, blockStateCodec, biomeCodec,
                    defaultBiome, biomeHolderMap, blockLightData, skyLightData, unparseable);
            if (result != null) {
                parsed.add(new ParsedSection(sectionY, result, blockLightData, skyLightData));
            }
        }
        if (unparseable[0] > 0) {
            // Authoritative miss (null): a truly-unparseable section (no partial value —
            // real corruption, not a recoverable rename) makes the whole column
            // unservable. Serving without the section would stamp a persistent invisible
            // hole; null rides the existing not-found ladder instead (memoized, and on
            // gen-enabled servers the generation ticket loads the chunk through the real
            // DataFixer pipeline and serves it correctly).
            return null;
        }

        // Boundary-light band (2026-07-27, black-boundary-faces fix): SKY-lit air sections
        // are served only within one section of the column's CONTENT band — vanilla's own
        // stored-light coverage (heightmap+1), matching the live path exactly (disk/live
        // byte parity) — so a void/cleared column's ambient sky can never turn a
        // zero-section CLEAR into a data column. BLOCK-lit air keeps its long-standing
        // unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var p : parsed) {
            if (!p.section().hasOnlyAir()) {
                minContent = Math.min(minContent, p.sectionY());
                maxContent = Math.max(maxContent, p.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        parsed.removeIf(p -> p.section().hasOnlyAir()
                && !(p.blockLight().length == 2048 && hasNonZeroNibble(p.blockLight()))
                && (noContent || p.sectionY() < lo || p.sectionY() > hi));

        if (parsed.isEmpty()) return new byte[0];

        if (maskEntry != null) {
            // Parsed sections are throwaway — mask in place, inside the same choke point
            // the live path masks in, so disk and live serves stay byte-identical. The
            // counter attributes to whatever manager is current at COMPLETION time (a read
            // straddling a service restart credits the successor) — diag-only cosmetics;
            // the mask itself always comes from the immutable submit-time entry.
            int[] replacedCells = new int[1];
            for (int i = 0; i < parsed.size(); i++) {
                var p = parsed.get(i);
                var masked = XrayMaskFilter.mask(p.section(), p.sectionY(),
                        maskEntry.mask(), maskEntry.kind(), factory, replacedCells);
                if (masked != p.section()) {
                    parsed.set(i, new ParsedSection(p.sectionY(), masked, p.blockLight(), p.skyLight()));
                    // Count only when cells were actually hidden: a stale-palette rebuild
                    // (mined-out section still listing its ore) swaps the section for the
                    // palette prune but masks nothing.
                    if (replacedCells[0] > 0) {
                        var manager = XrayMaskManager.current();
                        if (manager != null) manager.countMaskedSection();
                    }
                }
            }
        }

        // Second pass: serialize to wire format
        var buf = new FriendlyByteBuf(Unpooled.buffer(parsed.size() * 1024));
        try {
            buf.writeVarInt(parsed.size());
            for (var p : parsed) {
                buf.writeByte(p.sectionY);
                p.section.write(buf);

                // All-zero layers are skipped to match SectionSerializer exactly: the live path
                // omits them ("absent" means all-zero on the wire), and vanilla saves the light
                // engine's allocated-but-zeroed arrays (e.g. after a light source is removed), so
                // shipping them would make disk serves byte-diverge from live serves of the same
                // content — breaking the up-to-date economy and DirtyContentFilter seeding.
                boolean hasBlockLight = p.blockLight.length == 2048 && hasNonZeroNibble(p.blockLight);
                buf.writeBoolean(hasBlockLight);
                if (hasBlockLight) {
                    buf.writeBytes(p.blockLight);
                }

                boolean hasSkyLight = p.skyLight.length == 2048 && hasNonZeroNibble(p.skyLight);
                buf.writeBoolean(hasSkyLight);
                if (hasSkyLight) {
                    buf.writeBytes(p.skyLight);
                }
            }

            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return result;
        } finally {
            buf.release();
        }
    }

    /**
     * Parse a section NBT tag into a LevelChunkSection.
     * Returns null if the section has no block states or only air (and no block light).
     */
    private static LevelChunkSection parseSection(
            CompoundTag sectionTag, int sectionY,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            Holder<Biome> defaultBiome,
            net.minecraft.core.IdMap<Holder<Biome>> biomeHolderMap,
            byte[] blockLightData, byte[] skyLightData, int[] unparseable) {

        var blockStatesOpt = sectionTag.getCompound("block_states");
        PalettedContainer<BlockState> blockStates;
        if (blockStatesOpt.isEmpty()) {
            // Vanilla's light-only cap entries (heightmap+1) carry SkyLight but no
            // block_states — exactly the boundary layers the fix serves. Build an all-air
            // section for them; the air/light gate below decides whether it ships.
            blockStates = new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        } else {
            var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE, blockStatesOpt.get());
            // Vanilla-lenient (resultOrPartial): a recoverable palette error — e.g. a
            // pre-DataFixer block rename in an upgraded world's unvisited chunk (this
            // path reads RAW region NBT, no DFU runs) — substitutes the container
            // default (air) for the unknown entry and KEEPS the section, exactly like
            // vanilla's own load. The strict result() used to drop the whole section
            // silently, and an all-drop column was served as an authoritative 0-section
            // CLEAR that wiped the client's correct cached LOD.
            blockStates = blockStatesResult.resultOrPartial(err -> {
                long released = PARSE_WARN_THROTTLE.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (released > 0) {
                    String suffix = released > 1 ? " (+" + (released - 1) + " more suppressed)" : "";
                    LSSLogger.warn("Section block_states parse error (Y=" + sectionY + "): "
                            + err + suffix);
                }
            }).orElse(null);
            if (blockStates == null) {
                // No partial either — true corruption, not a rename. Counted: the caller
                // resolves the whole column as an authoritative miss.
                unparseable[0]++;
                return null;
            }
        }

        PalettedContainerRO<Holder<Biome>> biomes;
        var optBiomes = sectionTag.getCompound("biomes");
        if (optBiomes.isPresent()) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, optBiomes.get());
            biomes = biomesResult.result().orElse(null);
        } else {
            biomes = null;
        }

        LevelChunkSection section;
        if (biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer) {
            section = new LevelChunkSection(blockStates, biomeContainer);
        } else {
            var defaultBiomeContainer = new PalettedContainer<>(
                    defaultBiome, Strategy.createForBiomes(biomeHolderMap));
            section = new LevelChunkSection(blockStates, defaultBiomeContainer);
        }

        if (section.hasOnlyAir()) {
            boolean litByBlock = blockLightData.length == 2048 && hasNonZeroNibble(blockLightData);
            boolean litBySky = skyLightData.length == 2048 && hasNonZeroNibble(skyLightData);
            if (!litByBlock && !litBySky) {
                return null;
            }
        }

        return section;
    }

    private static boolean hasNonZeroNibble(byte[] light) {
        for (byte b : light) {
            if (b != 0) return true;
        }
        return false;
    }

    // PalettedContainerFactory.create builds two strategies + codecs per call — measurable
    // allocation churn when every disk read pays it (review 2026-07-27). The registry access
    // is stable for a server's lifetime; a single-slot memo (atomic pair via one volatile)
    // covers it and survives the odd registry swap in tests. The key is held WEAKLY so an
    // integrated server's departed world doesn't keep its dynamic registries pinned until
    // the next world load (final review 2026-07-27); the factory dies with its key.
    private static volatile java.util.Map.Entry<java.lang.ref.WeakReference<RegistryAccess>, PalettedContainerFactory> factoryMemo;

    static PalettedContainerFactory factoryFor(RegistryAccess registryAccess) {
        var memo = factoryMemo;
        if (memo != null && memo.getKey().get() == registryAccess) return memo.getValue();
        var factory = PalettedContainerFactory.create(registryAccess);
        factoryMemo = java.util.Map.entry(new java.lang.ref.WeakReference<>(registryAccess), factory);
        return factory;
    }
}
