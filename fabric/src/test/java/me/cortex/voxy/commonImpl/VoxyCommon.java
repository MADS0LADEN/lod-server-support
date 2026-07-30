package me.cortex.voxy.commonImpl;

/**
 * Test stub of Voxy's VoxyCommon, mirroring the shape VoxyCompat's ingest-backlog probe
 * resolves via MethodHandles: {@code static VoxyInstance getInstance()} (null before world
 * creation / after shutdown). Control {@link #instance}, call {@link #reset()} between tests.
 */
public final class VoxyCommon {

    /** Returned by {@link #getInstance}; set null to simulate "no instance yet". */
    public static volatile VoxyInstance instance = new VoxyInstance();

    public static VoxyInstance getInstance() {
        return instance;
    }

    public static void reset() {
        instance = new VoxyInstance();
    }
}
