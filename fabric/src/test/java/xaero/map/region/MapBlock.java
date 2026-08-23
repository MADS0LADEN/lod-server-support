package xaero.map.region;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/** Tier-1 stub — captures write() inputs for the extractor→commit assertions. */
public class MapBlock {
    public int preparedBottomY = Integer.MIN_VALUE;
    public BlockState state;
    public int height;
    public int topHeight;
    public ResourceKey<Biome> biome;
    public byte light;
    public boolean glowing;
    public boolean cave;
    public final java.util.List<Overlay> overlays = new java.util.ArrayList<>();

    public MapBlock() {}

    public void prepareForWriting(int defaultHeight) { this.preparedBottomY = defaultHeight; }

    public void write(BlockState state, int height, int topHeight, ResourceKey<Biome> biome,
                      byte light, boolean glowing, boolean cave) {
        this.state = state;
        this.height = height;
        this.topHeight = topHeight;
        this.biome = biome;
        this.light = light;
        this.glowing = glowing;
        this.cave = cave;
    }

    public void addOverlay(Overlay overlay) { this.overlays.add(overlay); }
}
