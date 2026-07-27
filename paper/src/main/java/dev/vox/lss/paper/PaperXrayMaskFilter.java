package dev.vox.lss.paper;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Collection;

/**
 * LOD x-ray masking (docs/planning/antixray-compat-design.md §3): blanket replacement of
 * hidden block states in a section BEFORE it is serialized — no exposure analysis, no
 * reveal-on-approach (LODs render beyond view distance; near terrain is the real anti-xray
 * system's job). Twin of the Fabric {@code XrayMaskFilter} — keep the two byte-for-byte
 * behavior-identical; the shared golden fixture ({@code nbt-corpus/xray-masked.bin}, diffed
 * cross-module by the corpus parity test) pins it.
 *
 * <p>Determinism contract: the same section CONTENT must mask to the same bytes on every
 * path (live copy vs NBT-parsed — DirtyContentFilter hashes and live-vs-disk byte parity
 * both depend on it), so replacement selection never depends on palette iteration order.
 */
final class PaperXrayMaskFilter {
    private PaperXrayMaskFilter() {}

    /**
     * The resolved mask: one boolean per global block-state id (AntiXray's own
     * representation — O(1), no equality subtleties) plus the effective max-block-height.
     * Immutable after construction; resolved once per config load / detection pass and
     * shared across serializing threads.
     */
    static final class MaskSet {
        private final boolean[] hiddenByStateId;
        private final int maxBlockHeight;
        private final int resolvedBlocks;

        private MaskSet(boolean[] hiddenByStateId, int maxBlockHeight, int resolvedBlocks) {
            this.hiddenByStateId = hiddenByStateId;
            this.maxBlockHeight = maxBlockHeight;
            this.resolvedBlocks = resolvedBlocks;
        }

        /** Resolves block ids (bare or namespaced) to ALL their states. Unknown ids warn + skip. */
        static MaskSet resolve(Collection<String> blockIds, int maxBlockHeight) {
            boolean[] hidden = new boolean[Block.BLOCK_STATE_REGISTRY.size()];
            int resolved = 0;
            for (String id : blockIds) {
                if (id == null || id.isBlank()) {
                    // GSON deserializes ["iron_ore", null] verbatim and Identifier.tryParse
                    // NPEs on null — masking must be throw-free by construction: a throw
                    // here escapes into serve choke points whose ladders turn it into
                    // session-permanent NOT_GENERATED (generation) or tick aborts (probe).
                    LSSLogger.warn("xrayHiddenBlocks: null/blank entry — skipped");
                    continue;
                }
                Identifier rl = Identifier.tryParse(id);
                if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                    LSSLogger.warn("xrayHiddenBlocks: unknown block id '" + id + "' — skipped");
                    continue;
                }
                Block block = BuiltInRegistries.BLOCK.getValue(rl);
                if (block.defaultBlockState().isAir()) {
                    // Hiding air would FILL caves with the replacement and desync the
                    // section count headers — the never-air invariant is load-bearing.
                    LSSLogger.warn("xrayHiddenBlocks: '" + id + "' is an air block and cannot be hidden — skipped");
                    continue;
                }
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    int sid = Block.BLOCK_STATE_REGISTRY.getId(state);
                    if (sid >= 0 && sid < hidden.length) hidden[sid] = true;
                }
                resolved++;
            }
            return new MaskSet(hidden, maxBlockHeight, resolved);
        }

        /** Engine-adoption factory: the detected anti-xray engine's own hidden STATES
         *  (already block/tag-expanded — no id parsing, no warnings). Air states are
         *  dropped for the same never-air invariant as the id path. */
        static MaskSet fromStates(Collection<BlockState> states, int maxBlockHeight) {
            boolean[] hidden = new boolean[Block.BLOCK_STATE_REGISTRY.size()];
            int resolved = 0;
            for (BlockState state : states) {
                if (state == null || state.isAir()) continue;
                int sid = Block.BLOCK_STATE_REGISTRY.getId(state);
                if (sid >= 0 && sid < hidden.length && !hidden[sid]) {
                    hidden[sid] = true;
                    resolved++;
                }
            }
            return new MaskSet(hidden, maxBlockHeight, resolved);
        }

        boolean contains(BlockState state) {
            int sid = Block.BLOCK_STATE_REGISTRY.getId(state);
            return sid >= 0 && sid < this.hiddenByStateId.length && this.hiddenByStateId[sid];
        }

        /** True when no block id resolved — masking no-ops rather than serving a false sense of cover. */
        boolean isEmpty() {
            return this.resolvedBlocks == 0;
        }

        int maxBlockHeight() {
            return this.maxBlockHeight;
        }
    }

    /**
     * Both serve paths (live copy and NBT-parsed): returns the section itself when
     * untouched, or a REBUILT masked section. The source section is NEVER mutated (live
     * chunks keep real terrain; parsed sections are simply abandoned). Call on whatever
     * thread legally serializes the section.
     *
     * <p>The rebuild is the point (review 2026-07-27): {@code PalettedContainer.set} never
     * prunes the palette and {@code write()} ships the palette verbatim, so an in-place
     * mask still LISTED every hidden ore the section no longer contains — a
     * section-resolution ore-presence oracle across the whole LOD radius. Masking into a
     * {@code recreate()}d container builds a minimal palette (only referenced states), so
     * the wire carries no trace of the hidden set. The section ctor recalculates the count
     * headers from the masked states, which keeps live-vs-disk byte parity (both paths now
     * share this exact construction).
     */
    static LevelChunkSection mask(LevelChunkSection section, int sectionY, MaskSet mask, FallbackKind kind) {
        if (!needsMasking(section, sectionY, mask)) return section;
        PalettedContainer<BlockState> masked = maskedStates(section.getStates(), sectionY, mask, kind);
        var biomes = section.getBiomes();
        if (!(biomes instanceof PalettedContainer<Holder<Biome>> biomeContainer)) {
            // Unreachable on live sections and codecRO-parsed sections alike (both yield the
            // concrete type); the instanceof exists because Paper's section ctor requires
            // it, and the twins stay textually identical. Contained by every serve path.
            throw new IllegalStateException("section biomes are not a mutable PalettedContainer");
        }
        // The biome container is reused by REFERENCE (read-only here); only states differ.
        return new LevelChunkSection(masked, biomeContainer);
    }

    /** One pass, one allocation: reads the source container, writes same-or-replacement
     *  into a fresh same-strategy container whose palette ends minimal. */
    private static PalettedContainer<BlockState> maskedStates(PalettedContainer<BlockState> states,
                                                              int sectionY, MaskSet mask, FallbackKind kind) {
        BlockState replacement = chooseReplacement(states, sectionY, mask, kind);
        PalettedContainer<BlockState> fresh = states.recreate();
        int bottomY = sectionY << 4;
        // A section STRADDLING the cutoff keeps cells at/above it real — vanilla packets
        // already reveal them, masking would only mismatch near terrain.
        int yLimit = Math.min(16, mask.maxBlockHeight - bottomY);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = states.get(x, y, z);
                    fresh.set(x, y, z, y < yLimit && mask.contains(state) ? replacement : state);
                }
            }
        }
        return fresh;
    }

    private static boolean needsMasking(LevelChunkSection section, int sectionY, MaskSet mask) {
        if (mask == null || mask.isEmpty()) return false;
        // Step 0, the height gate: a section entirely at/above the cutoff skips even the
        // palette scan (with the default 64 that is every section from sectionY 4 up).
        if ((sectionY << 4) >= mask.maxBlockHeight) return false;
        if (section.hasOnlyAir()) return false;
        // Step 1: palette-only scan — ore-free sections pay nothing further.
        return section.getStates().maybeHas(mask::contains);
    }


    /**
     * The replacement is the section's dominant non-hidden, non-air state (adapts to
     * deepslate/nether/modded terrain with zero config), falling back to a dimension-flavored
     * filler. NEVER air — nonEmptyBlockCount stays honest and the all-air sentinel path is
     * never entered by masking. Ties break on the lowest global state id, NOT palette order:
     * live and NBT-parsed palettes order entries differently for identical content.
     */
    private static BlockState chooseReplacement(PalettedContainer<BlockState> states, int sectionY,
                                                MaskSet mask, FallbackKind kind) {
        var best = new Object() {
            BlockState state;
            int count;
            int id;
        };
        states.count((state, n) -> {
            if (state.isAir() || mask.contains(state)) return;
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            if (best.state == null || n > best.count || (n == best.count && id < best.id)) {
                best.state = state;
                best.count = n;
                best.id = id;
            }
        });
        if (best.state != null) return best.state;
        return switch (kind) {
            case NETHER -> Blocks.NETHERRACK.defaultBlockState();
            case END -> Blocks.END_STONE.defaultBlockState();
            case OVERWORLD -> sectionY < 0
                    ? Blocks.DEEPSLATE.defaultBlockState()
                    : Blocks.STONE.defaultBlockState();
        };
    }
}
