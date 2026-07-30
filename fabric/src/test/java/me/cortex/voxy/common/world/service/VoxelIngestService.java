package me.cortex.voxy.common.world.service;

import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test stub of Voxy's ingest service, mirroring the exact static signature VoxyCompat
 * resolves via MethodHandles: {@code static boolean rawIngest(WorldIdentifier,
 * LevelChunkSection, int, int, int, DataLayer, DataLayer)}. Every invocation is recorded in
 * {@link #calls} (even when {@link #behavior} subsequently throws), then {@link #behavior}
 * decides the result — it may throw unchecked Throwables to drive the bridge's failure
 * ladder. General-purpose; call {@link #reset()} between tests.
 */
public final class VoxelIngestService {

    /** One recorded {@link #rawIngest} invocation, fields in raw argument order. */
    public record IngestCall(WorldIdentifier worldId, LevelChunkSection section,
                             int chunkX, int sectionY, int chunkZ,
                             DataLayer blockLight, DataLayer skyLight) {}

    /** Decides each recorded call's result; may throw unchecked. */
    @FunctionalInterface
    public interface IngestBehavior {
        boolean ingest(IngestCall call);
    }

    public static final List<IngestCall> calls = Collections.synchronizedList(new ArrayList<>());

    public static volatile IngestBehavior behavior = call -> true;

    public static boolean rawIngest(WorldIdentifier worldId, LevelChunkSection section,
                                    int chunkX, int sectionY, int chunkZ,
                                    DataLayer blockLight, DataLayer skyLight) {
        var call = new IngestCall(worldId, section, chunkX, sectionY, chunkZ, blockLight, skyLight);
        calls.add(call);
        return behavior.ingest(call);
    }

    /** Value returned by the instance {@link #getTaskCount()} (the backlog-probe terminal). */
    public static volatile int taskCount = 0;

    /** When non-null, {@link #getTaskCount()} throws this instead of returning. */
    public static volatile RuntimeException taskCountThrow = null;

    /** Instance method mirroring the shape the backlog probe resolves: {@code int getTaskCount()}. */
    public int getTaskCount() {
        var t = taskCountThrow;
        if (t != null) throw t;
        return taskCount;
    }

    public static void reset() {
        calls.clear();
        behavior = call -> true;
        taskCount = 0;
        taskCountThrow = null;
    }
}
