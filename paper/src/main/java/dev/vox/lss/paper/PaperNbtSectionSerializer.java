package dev.vox.lss.paper;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.biome.Biome;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads chunk NBT from disk and serializes sections into MC-native wire format.
 * Used by {@link PaperChunkDiskReader} for async disk reads.
 *
 * <p>Headless serve path (2026-07-29 profile — mirrors the Fabric twin exactly): the
 * UNMASKED path never constructs a {@link LevelChunkSection}. The two wire count headers
 * come from {@link #countNonEmptyAndFluid}'s palette histogram instead of the ctor's
 * per-cell recount (on Paper the ctor is even costlier — Moonrise's recalc also builds
 * per-state coordinate lists the wire never needs), and the containers write themselves.
 * Palette-entry block-state decode goes through {@link PaperMemoizedNbtCodec}. The MASKED
 * path still constructs real sections: mask semantics rely on the counting ctor for the
 * masked headers (see PaperXrayMaskFilter).
 */
final class PaperNbtSectionSerializer {
    private PaperNbtSectionSerializer() {}

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] ZERO_NIBBLES = new byte[2048];

    /** Test seam: the region-file NBT read — the only NMS call in the Paper disk-read path.
     *  Production wires {@link ChunkMap#read}; tests inject empty / failing / timing-out
     *  futures to pin the submit-envelope triage. */
    @FunctionalInterface
    interface ChunkNbtRead {
        CompletableFuture<Optional<CompoundTag>> read(int cx, int cz);
    }

    /**
     * Read chunk NBT from disk, verify FULL status, and serialize sections
     * into MC-native wire format. {@code maskEntry} (nullable) is the dimension's x-ray
     * mask, captured by the caller at submit time.
     * Returns the serialized byte array, or null if the chunk is missing/not FULL/empty —
     * or carries a truly-unparseable in-range section (R2-1: the whole column resolves as
     * an authoritative miss rather than serving with a silent hole).
     */
    static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            PaperXrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY) throws Exception {
        var future = read.read(cx, cz);
        var optionalTag = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) return null;
        return serializeChunkNbt(optionalTag.get(), registryAccess, maskEntry, minSectionY, maxSectionY);
    }

    /** Unmasked flavor — the shape the pre-masking tests and corpus pin. */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess) {
        return serializeChunkNbt(chunkNbt, registryAccess, null);
    }

    /** Range-free flavor (tests + corpus — the committed goldens serialize out-of-world
     *  Y values and must keep doing so; only the production path gates). */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    PaperXrayMaskManager.MaskEntry maskEntry) {
        return serializeChunkNbt(chunkNbt, registryAccess, maskEntry,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    // Unparseable-section warns are throttled — see the Fabric twin's rationale.
    private static final dev.vox.lss.common.LogThrottle PARSE_WARN_THROTTLE =
            new dev.vox.lss.common.LogThrottle(60_000);

    /** A parsed section, headless — see the Fabric twin. */
    record ParsedSection(int sectionY,
                         PalettedContainer<BlockState> states,
                         PalettedContainerRO<Holder<Biome>> biomes,
                         int nonEmptyCount, int fluidCount,
                         byte[] blockLight, byte[] skyLight,
                         boolean litByBlock, boolean litBySky) {}

    /** Sizing-exactness telemetry — see the Fabric twin. Tests pin 0. */
    static final AtomicLong SIZE_MISMATCH_FALLBACKS = new AtomicLong();
    private static final AtomicBoolean SIZE_MISMATCH_WARNED = new AtomicBoolean();

    /**
     * Serialize a chunk's NBT (as read from a region file) into MC-native wire format.
     * Returns {@code null} if the chunk is not FULL or has no sections, an empty array if every
     * section is empty, or the serialized section bytes. Package-visible for testing.
     *
     * <p>R2-1/R2-5 semantics (mirrors the Fabric twin): a renamed-block section parses via
     * {@code resultOrPartial} with air substitution (throttled warn); a truly-unparseable
     * IN-RANGE section returns {@code null} for the WHOLE column — an authoritative miss the
     * caller escalates to generation, never a partial serve and never a throw. Entries
     * outside {@code [minSectionY, maxSectionY]} are dropped BEFORE parse and can neither
     * serve nor condemn the column (vanilla saves light-only entries at blockRange±1; the
     * range-free overload above stays unbounded for the corpus goldens).
     */
    // LevelChunkSection.write(buf) is @Deprecated on Paper (an anti-xray overload was added),
    // but the 1-arg form is the canonical vanilla serialization and is byte-identical to the
    // Fabric path. The wire format must match Fabric exactly, so the MASKED branch keeps this
    // call (do not migrate); the headless branch writes the identical shape by construction.
    @SuppressWarnings("deprecation")
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    PaperXrayMaskManager.MaskEntry maskEntry,
                                    int minSectionY, int maxSectionY) {
        var statusStr = chunkNbt.getStringOr("Status", null);
        if (statusStr == null || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) return null;

        var factory = factoryFor(registryAccess);
        // Block-state container codec is LSS-built: vanilla's exact codecRW arguments
        // (fuzz + goldens pin equivalence) with only the ELEMENT codec swapped for the
        // palette-entry memo. Biomes keep the factory codec — see the Fabric twin.
        var blockStateCodec = BlockCodecHolder.CODEC;
        var biomeCodec = factory.biomeContainerCodec();

        var sectionsTag = chunkNbt.getList("sections");
        if (sectionsTag.isEmpty()) return null;

        var sectionsList = sectionsTag.orElseThrow();

        // First pass: parse sections and check if any are non-empty
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
                    factory, blockLightData, skyLightData, unparseable);
            if (result != null) {
                parsed.add(result);
            }
        }
        if (unparseable[0] > 0) {
            // Authoritative miss (null) — see the Fabric twin: a truly-unparseable section
            // makes the whole column unservable; the miss escalates to a generation ticket
            // that loads the chunk through the real DataFixer pipeline.
            return null;
        }

        // Boundary-light band (2026-07-27, black-boundary-faces fix — see the Fabric twin):
        // SKY-lit air serves only within one section of the content band; a column with NO
        // content sections stays a zero-section CLEAR. BLOCK-lit air keeps its
        // long-standing unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var p : parsed) {
            if (p.nonEmptyCount() != 0) {
                minContent = Math.min(minContent, p.sectionY());
                maxContent = Math.max(maxContent, p.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        parsed.removeIf(p -> p.nonEmptyCount() == 0
                && !p.litByBlock()
                && (noContent || p.sectionY() < lo || p.sectionY() > hi));

        if (parsed.isEmpty()) return new byte[0];

        // Masked path — see the Fabric twin: real sections, the same choke point the live
        // path masks in; mask headers can only be recomputed by the counting ctor, never
        // adjusted. Counter attribution at COMPLETION time is diag-only cosmetics.
        LevelChunkSection[] maskedSections = null;
        if (maskEntry != null) {
            maskedSections = new LevelChunkSection[parsed.size()];
            int[] replacedCells = new int[1];
            for (int i = 0; i < parsed.size(); i++) {
                var p = parsed.get(i);
                // Paper's (Moonrise) ctor takes the RW container — unpack always returns
                // one, so the instanceof matches for parsed AND default biomes (the
                // pre-headless code had the same shape).
                var section = p.biomes() instanceof PalettedContainer<Holder<Biome>> biomeContainer
                        ? new LevelChunkSection(p.states(), biomeContainer)
                        : new LevelChunkSection(p.states(), factory.createForBiomes());
                var masked = PaperXrayMaskFilter.mask(section, p.sectionY(),
                        maskEntry.mask(), maskEntry.kind(), factory, replacedCells);
                maskedSections[i] = masked;
                // Count only when cells were actually hidden — see the Fabric twin.
                if (masked != section && replacedCells[0] > 0) {
                    var manager = PaperXrayMaskManager.current();
                    if (manager != null) manager.countMaskedSection();
                }
            }
        }

        // Second pass: serialize to wire format, into an EXACTLY-sized buffer — see the
        // Fabric twin (zero netty growth; the backing array IS the payload on the exact
        // path; a mismatch falls back to the copy, never to wrong bytes).
        int size = VarInt.getByteSize(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            var p = parsed.get(i);
            size += 1 // sectionY byte
                    + (maskedSections != null
                            ? maskedSections[i].getSerializedSize()
                            : 4 + p.states().getSerializedSize() + p.biomes().getSerializedSize())
                    + 1 + (p.litByBlock() ? 2048 : 0)
                    + 1 + (p.litBySky() ? 2048 : 0);
        }

        var buf = new FriendlyByteBuf(Unpooled.buffer(size));
        try {
            buf.writeVarInt(parsed.size());
            for (int i = 0; i < parsed.size(); i++) {
                var p = parsed.get(i);
                buf.writeByte(p.sectionY());
                if (maskedSections != null) {
                    maskedSections[i].write(buf);
                } else {
                    // Headless section write — exactly LevelChunkSection.write's shape:
                    // the two count shorts, then the two containers.
                    buf.writeShort(p.nonEmptyCount());
                    buf.writeShort(p.fluidCount());
                    p.states().write(buf);
                    p.biomes().write(buf);
                }

                // All-zero layers are skipped to match the live serializer exactly (mirrors
                // Fabric's NbtSectionSerializer): "absent" means all-zero on the wire, and
                // vanilla saves the light engine's allocated-but-zeroed arrays, which would
                // otherwise make disk serves byte-diverge from live serves of identical content.
                buf.writeBoolean(p.litByBlock());
                if (p.litByBlock()) {
                    buf.writeBytes(p.blockLight());
                }

                buf.writeBoolean(p.litBySky());
                if (p.litBySky()) {
                    buf.writeBytes(p.skyLight());
                }
            }

            if (buf.writerIndex() == size && buf.arrayOffset() == 0 && buf.array().length == size) {
                return buf.array();
            }
            SIZE_MISMATCH_FALLBACKS.incrementAndGet();
            if (SIZE_MISMATCH_WARNED.compareAndSet(false, true)) {
                LSSLogger.warn("Exact column pre-size mismatched written bytes (expected "
                        + size + ", wrote " + buf.writerIndex()
                        + ") — falling back to a copy; bytes are unaffected");
            }
            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return result;
        } finally {
            buf.release();
        }
    }

    /**
     * Parse a section NBT tag into a headless {@link ParsedSection}.
     * Returns null if the section has no block states or only air (and no block light).
     */
    private static ParsedSection parseSection(
            CompoundTag sectionTag, int sectionY,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            PalettedContainerFactory factory,
            byte[] blockLightData, byte[] skyLightData, int[] unparseable) {

        var blockStatesOpt = sectionTag.getCompound("block_states");
        PalettedContainer<BlockState> blockStates;
        boolean knownAir = false;
        if (blockStatesOpt.isEmpty()) {
            // Vanilla's light-only cap entries (heightmap+1) carry SkyLight but no
            // block_states — exactly the boundary layers the fix serves.
            blockStates = factory.createForBlockStates();
            knownAir = true;
        } else {
            var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE, blockStatesOpt.get());
            // Vanilla-lenient (resultOrPartial) — see the Fabric twin: a recoverable
            // palette error (pre-DFU block rename) substitutes air and KEEPS the section;
            // only a no-partial parse counts unparseable.
            blockStates = blockStatesResult.resultOrPartial(err -> {
                long released = PARSE_WARN_THROTTLE.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (released > 0) {
                    String suffix = released > 1 ? " (+" + (released - 1) + " more suppressed)" : "";
                    LSSLogger.warn("Section block_states parse error (Y=" + sectionY + "): "
                            + err + suffix);
                }
            }).orElse(null);
            if (blockStates == null) {
                unparseable[0]++;
                return null;
            }
        }

        PalettedContainerRO<Holder<Biome>> biomes = null;
        var optBiomes = sectionTag.getCompound("biomes");
        if (optBiomes.isPresent()) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, optBiomes.get());
            biomes = biomesResult.result().orElse(null);
        }
        if (biomes == null) {
            biomes = factory.createForBiomes();
        }

        int counts = knownAir ? 0 : countNonEmptyAndFluid(blockStates);
        int nonEmpty = counts >>> 16, fluid = counts & 0xFFFF;

        boolean litByBlock = blockLightData.length == 2048 && hasNonZeroNibble(blockLightData);
        boolean litBySky = skyLightData.length == 2048 && hasNonZeroNibble(skyLightData);

        if (nonEmpty == 0 && !litByBlock && !litBySky) {
            return null;
        }

        return new ParsedSection(sectionY, blockStates, biomes, nonEmpty, fluid,
                blockLightData, skyLightData, litByBlock, litBySky);
    }

    /**
     * The two wire count headers, packed {@code (nonEmpty << 16) | fluid} — see the Fabric
     * twin (BlockCounter semantics minus the ticking counts; histogram instead of the
     * per-cell recount). {@code states.data} is public on Paper (Moonrise patch); the
     * container is thread-confined (freshly parsed on this reader thread).
     */
    static int countNonEmptyAndFluid(PalettedContainer<BlockState> states) {
        var data = states.data;
        var palette = data.palette();
        var storage = data.storage();
        int n = palette.getSize();
        int nonEmpty = 0, fluid = 0;
        if (n == 1) {
            var s = palette.valueFor(0);
            if (!s.isAir()) {
                int c = storage.getSize();
                nonEmpty = c;
                if (!s.getFluidState().isEmpty()) fluid = c;
            }
        } else if (n <= 4096) {
            int[] hist = new int[n];
            storage.getAll(id -> hist[id]++);
            for (int i = 0; i < n; i++) {
                int c = hist[i];
                if (c == 0) continue;
                var s = palette.valueFor(i);
                if (!s.isAir()) {
                    nonEmpty += c;
                    if (!s.getFluidState().isEmpty()) fluid += c;
                }
            }
        } else {
            // Global palette (getSize() is registry-sized): rare on disk — count through
            // vanilla's own path rather than allocating a registry-sized histogram.
            int[] acc = new int[2];
            states.count((state, c) -> {
                if (!state.isAir()) {
                    acc[0] += c;
                    if (!state.getFluidState().isEmpty()) acc[1] += c;
                }
            });
            nonEmpty = acc[0];
            fluid = acc[1];
        }
        return (nonEmpty << 16) | fluid;
    }

    private static boolean hasNonZeroNibble(byte[] light) {
        // Intrinsified vectorized mismatch — see the Fabric twin. Callers guarantee 2048.
        return !java.util.Arrays.equals(light, ZERO_NIBBLES);
    }

    // Static (unlike factoryMemo): the block registry is bootstrap-frozen, so the memoized
    // element codec and its cache live for the JVM. Arguments mirror
    // PalettedContainerFactory.create's codecRW call exactly (fuzz + goldens pin it).
    private static final class BlockCodecHolder {
        static final Codec<PalettedContainer<BlockState>> CODEC = PalettedContainer.codecRW(
                new PaperMemoizedNbtCodec<>(BlockState.CODEC, 1 << 16),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                Blocks.AIR.defaultBlockState());
    }

    // PalettedContainerFactory.create builds two strategies + codecs per call — measurable
    // allocation churn when every disk read pays it (review 2026-07-27). The registry access
    // is stable for a server's lifetime; a single-slot memo (atomic pair via one volatile)
    // covers it and survives the odd registry swap in tests. The key is held WEAKLY so a
    // departed world doesn't keep its dynamic registries pinned until the next world load
    // (final review 2026-07-27); the factory dies with its key.
    private static volatile java.util.Map.Entry<java.lang.ref.WeakReference<RegistryAccess>, PalettedContainerFactory> factoryMemo;

    static PalettedContainerFactory factoryFor(RegistryAccess registryAccess) {
        var memo = factoryMemo;
        if (memo != null && memo.getKey().get() == registryAccess) return memo.getValue();
        var factory = PalettedContainerFactory.create(registryAccess);
        factoryMemo = java.util.Map.entry(new java.lang.ref.WeakReference<>(registryAccess), factory);
        return factory;
    }
}
