package ca.spottedleaf.moonrise.patches.chunk_system.player;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Real-package-name stub of Moonrise-Fabric's {@code RegionizedPlayerChunkLoader} with
 * the nested {@code PlayerChunkLoaderData} the tracer reads reflectively. Field names,
 * types, AND access (private) mirror the REAL 1.1.0 class — verified against
 * {@code moonrise-opt-W0HImEBl.jar} on 2026-08-06 — so the resolver's
 * {@code setAccessible} path and type checks run exactly as they would live.
 */
public final class RegionizedPlayerChunkLoader {

    public static final class PlayerChunkLoaderData {

        private final LongOpenHashSet sentChunks = new LongOpenHashSet();
        private final Long2ByteOpenHashMap chunkTicketStage = new Long2ByteOpenHashMap();
        private final LongHeapPriorityQueue sendQueue = new LongHeapPriorityQueue();
        private int lastSendDistance;
        private int lastChunkX;
        private int lastChunkZ;

        public LongOpenHashSet getSentChunksRaw() {
            return sentChunks;
        }

        // ---- test-side mutators (the real class is driven by Moonrise internals) ----

        public void testAddSent(long chunkKey) {
            sentChunks.add(chunkKey);
        }

        public void testSetStage(long chunkKey, byte stage) {
            chunkTicketStage.put(chunkKey, stage);
        }

        public void testQueue(long chunkKey) {
            sendQueue.enqueue(chunkKey);
        }

        public void testSetLoaderState(int sendDistance, int chunkX, int chunkZ) {
            this.lastSendDistance = sendDistance;
            this.lastChunkX = chunkX;
            this.lastChunkZ = chunkZ;
        }
    }
}
