package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.VoxyInstance;

import java.nio.file.Path;

/**
 * Test stub of Voxy's client instance subclass — the reset domain resolves
 * {@code getStorageBasePath()} on it (public in 0.2.11, 0.2.18-beta and dev source,
 * javap-verified at plan review).
 */
public class VoxyClientInstance extends VoxyInstance {

    public volatile Path storageBasePath;

    public Path getStorageBasePath() {
        return storageBasePath;
    }
}
