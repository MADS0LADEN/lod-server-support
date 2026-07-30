package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.world.service.VoxelIngestService;

/**
 * Test stub of Voxy's VoxyInstance, mirroring the shape VoxyCompat's ingest-backlog probe
 * resolves via MethodHandles: {@code VoxelIngestService getIngestService()}. The production
 * class is abstract with a final ctor-assigned service (never null); {@link #ingestService}
 * is settable (incl. null) so tests can drive the probe's defensive rungs anyway.
 */
public class VoxyInstance {

    public volatile VoxelIngestService ingestService = new VoxelIngestService();

    public VoxelIngestService getIngestService() {
        return ingestService;
    }
}
