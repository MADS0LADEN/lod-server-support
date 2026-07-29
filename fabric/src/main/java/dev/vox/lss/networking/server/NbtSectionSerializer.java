package dev.vox.lss.networking.server;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.compat.AntiXrayCompat;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
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
 * Used by {@link ChunkDiskReader} for async disk reads.
 *
 * <p>Headless serve path (2026-07-29 profile — the recount + palette-decode chains were
 * ~45% of all server CPU during saturated backfill): the UNMASKED path never constructs a
 * {@link LevelChunkSection}. The two wire count headers ({@code nonEmptyBlockCount} and
 * {@code fluidCount} — 26.2 writes both) are computed by {@link #countNonEmptyAndFluid}'s
 * palette histogram instead of the ctor's per-cell hashmap recount, and the containers
 * write themselves ({@code section.write} is exactly the two shorts + the two container
 * writes — pinned by the headless-vs-section fuzz test). Palette-entry block-state decode
 * goes through {@link MemoizedNbtCodec}. The MASKED path still constructs real sections:
 * mask semantics deliberately rely on the counting ctor for the masked headers
 * (fluid-in/fluid-out replacement makes them non-adjustable — see XrayMaskFilter).
 */
final class NbtSectionSerializer {
    private NbtSectionSerializer() {}

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] ZERO_NIBBLES = new byte[2048];

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

    /** A parsed section, headless: containers + the two wire count headers + light, no
     *  {@link LevelChunkSection}. {@code litByBlock}/{@code litBySky} are the all-zero
     *  scans computed exactly once (the wire's "absent means all-zero" rule). */
    record ParsedSection(int sectionY,
                         PalettedContainer<BlockState> states,
                         PalettedContainerRO<Holder<Biome>> biomes,
                         int nonEmptyCount, int fluidCount,
                         byte[] blockLight, byte[] skyLight,
                         boolean litByBlock, boolean litBySky) {}

    /** Sizing-exactness telemetry: bumped when the exact pre-size mismatched the written
     *  bytes and the safe copy fallback ran (never wrong bytes, one warn). Tests pin 0. */
    static final AtomicLong SIZE_MISMATCH_FALLBACKS = new AtomicLong();
    private static final AtomicBoolean SIZE_MISMATCH_WARNED = new AtomicBoolean();

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
        // Block-state container codec is LSS-built: vanilla's exact codecRW arguments
        // (fuzz + goldens pin equivalence) with only the ELEMENT codec swapped for the
        // palette-entry memo. Biomes keep the factory codec (tiny palettes, dynamic
        // registry — not worth the per-registry memo lifecycle).
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

        // Masked path: parsed sections are throwaway — construct real sections and mask in
        // place, inside the same choke point the live path masks in, so disk and live
        // serves stay byte-identical. Mask semantics rely on the counting ctor for the
        // masked headers (they can only be recomputed, never adjusted — the fluid gotcha
        // cuts both ways), so this branch keeps the pre-headless shape wholesale. The
        // counter attributes to whatever manager is current at COMPLETION time (a read
        // straddling a service restart credits the successor) — diag-only cosmetics;
        // the mask itself always comes from the immutable submit-time entry.
        LevelChunkSection[] maskedSections = null;
        if (maskEntry != null) {
            maskedSections = new LevelChunkSection[parsed.size()];
            int[] replacedCells = new int[1];
            for (int i = 0; i < parsed.size(); i++) {
                var p = parsed.get(i);
                var section = new LevelChunkSection(p.states(), p.biomes());
                var masked = XrayMaskFilter.mask(section, p.sectionY(),
                        maskEntry.mask(), maskEntry.kind(), factory, replacedCells);
                maskedSections[i] = masked;
                // Count only when cells were actually hidden: a stale-palette rebuild
                // (mined-out section still listing its ore) swaps the section for the
                // palette prune but masks nothing.
                if (masked != section && replacedCells[0] > 0) {
                    var manager = XrayMaskManager.current();
                    if (manager != null) manager.countMaskedSection();
                }
            }
        }

        // Second pass: serialize to wire format, into an EXACTLY-sized buffer (the old
        // 1 KB/section estimate was 4-8x short, so netty regrew and recopied the payload
        // 2-3 times per column). A correct size means zero growth and the backing array
        // IS the payload (stolen below — no copy-out); a mismatch falls back to the copy,
        // never to wrong bytes.
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

                // All-zero layers are skipped to match SectionSerializer exactly: the live path
                // omits them ("absent" means all-zero on the wire), and vanilla saves the light
                // engine's allocated-but-zeroed arrays (e.g. after a light source is removed), so
                // shipping them would make disk serves byte-diverge from live serves of the same
                // content — breaking the up-to-date economy and DirtyContentFilter seeding.
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
            // block_states — exactly the boundary layers the fix serves. Build an all-air
            // container for them; the air/light gate below decides whether it ships.
            blockStates = factory.createForBlockStates();
            knownAir = true;
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
     * The two wire count headers, packed {@code (nonEmpty << 16) | fluid} — exactly
     * {@code LevelChunkSection.recalcBlockCounts}' BlockCounter minus the ticking counts
     * the wire never carries: per distinct state, a non-air state adds its cells to
     * nonEmpty, and (inside that branch) a non-empty fluid state adds them to fluid.
     * Replaces the ctor's per-cell {@code Int2IntOpenHashMap} recount with an
     * {@code int[palette.getSize()]} histogram (2026-07-29 profile: the recount chain was
     * ~22% of all server CPU). The container is thread-confined (freshly parsed on this
     * reader thread), so the raw {@code data} read is safe.
     */
    static int countNonEmptyAndFluid(PalettedContainer<BlockState> states) {
        var data = states.data; // access-widened; public (Moonrise) on the Paper twin
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
        // Intrinsified vectorized mismatch — ~an order of magnitude over the byte loop.
        // Callers guarantee length == 2048.
        return !java.util.Arrays.equals(light, ZERO_NIBBLES);
    }

    // Static (unlike factoryMemo): the block registry is bootstrap-frozen, so the memoized
    // element codec and its cache live for the JVM. Arguments mirror
    // PalettedContainerFactory.create's codecRW call exactly (fuzz + goldens pin it).
    private static final class BlockCodecHolder {
        static final Codec<PalettedContainer<BlockState>> CODEC = PalettedContainer.codecRW(
                new MemoizedNbtCodec<>(BlockState.CODEC, 1 << 16),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                Blocks.AIR.defaultBlockState());
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
