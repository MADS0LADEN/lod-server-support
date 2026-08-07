package ca.spottedleaf.moonrise.patches.chunk_system.player;

/**
 * Real-package-name stub of Moonrise-Fabric's {@code ChunkSystemServerPlayer} (the
 * interface Moonrise mixes into {@code ServerPlayer}), mirroring the REAL 1.1.0 method
 * set — verified against {@code moonrise-opt-W0HImEBl.jar} (mojang-mapped, MC 26.2) on
 * 2026-08-06. The full five-method shape is kept (the {@code MoonriseReadCompatTest}
 * decoy culture) so a resolver regression to "any method on the interface" reds here.
 *
 * <p>Drives {@code MoonriseSendStateCompatTest}; the production resolver takes the loader
 * class from THIS method's return type, never from a hardcoded name.
 */
public interface ChunkSystemServerPlayer {

    boolean moonrise$isRealPlayer();

    void moonrise$setRealPlayer(boolean real);

    RegionizedPlayerChunkLoader.PlayerChunkLoaderData moonrise$getChunkLoader();

    void moonrise$setChunkLoader(RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader);

    Object moonrise$getViewDistanceHolder();
}
