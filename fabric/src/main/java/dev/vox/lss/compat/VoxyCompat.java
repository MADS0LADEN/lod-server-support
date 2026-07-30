package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Pure reflection bridge to Voxy — zero compile-time dependency.
 * Uses Voxy's {@code rawIngest} API to pass MC-native {@link net.minecraft.world.level.chunk.LevelChunkSection}
 * and {@link net.minecraft.world.level.chunk.DataLayer} objects directly, eliminating all
 * intermediate ID translation.
 */
class VoxyCompat {
    // WorldIdentifier.of(Level) → Object
    private static MethodHandle worldIdentifierOf;

    // VoxelIngestService.rawIngest(Object worldId, LevelChunkSection, int cx, int sy, int cz, DataLayer blockLight, DataLayer skyLight)
    private static MethodHandle rawIngest;

    // VoxyConfig — lazily initialized to avoid premature class loading
    private static volatile MethodHandle getVoxyConfig;
    private static volatile MethodHandle getSectionRenderDist;

    // Ingest-backlog probe (issue #71): VoxyCommon.getInstance() → VoxyInstance
    // .getIngestService() → VoxelIngestService.getTaskCount(). Resolved in init() but in a
    // SEPARATE try/catch from the ingest handles — a Voxy that renames this surface must
    // not cost the ingest bridge; the probe then permanently reports -1 (no signal).
    private static MethodHandle voxyGetInstance;
    private static MethodHandle getIngestService;
    private static MethodHandle getTaskCount;

    /** Resolves the reflected Voxy class names — test seam, see {@link #resetSeams()}. */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /** Receives the bridge's ingest-failure reports — test seam, see {@link #resetSeams()}. */
    @FunctionalInterface
    interface ReportSink {
        void report(ResourceKey<Level> dimension, int chunkX, int chunkZ);
    }

    // Test seams: default-wired to production by resetSeams() (zero behavior change);
    // VoxyCompatTest swaps them to drive the bridge's failure ladder against stub classes.
    static ClassResolver classResolver;
    static ReportSink reportSink;
    static Consumer<VoxelColumnConsumer> consumerRegistrar;

    static {
        resetSeams();
    }

    /** Restores production wiring for the test seams. */
    static void resetSeams() {
        classResolver = Class::forName;
        reportSink = LSSApi::reportIngestFailure;
        consumerRegistrar = LSSApi::registerColumnConsumer;
    }

    static boolean init() {
        try {
            var lookup = MethodHandles.lookup();

            Class<?> worldIdClass = classResolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldIdentifierOf = lookup.findStatic(worldIdClass, "of",
                    MethodType.methodType(worldIdClass, Level.class))
                    .asType(MethodType.methodType(Object.class, Level.class));

            Class<?> ingestClass = classResolver.resolve("me.cortex.voxy.common.world.service.VoxelIngestService");
            rawIngest = lookup.findStatic(ingestClass, "rawIngest",
                    MethodType.methodType(boolean.class,
                            worldIdClass, LevelChunkSection.class,
                            int.class, int.class, int.class,
                            DataLayer.class, DataLayer.class));

            initBacklogProbe(lookup, ingestClass);

            // Register column consumer — an anonymous class, not a lambda, so it can
            // override pendingIngestBacklog() with the live Voxy queue depth.
            var bridgeDead = new AtomicBoolean();
            VoxelColumnConsumer consumer = new VoxelColumnConsumer() {
                @Override
                public int pendingIngestBacklog() {
                    return probeIngestBacklog();
                }

                @Override
                public void onVoxelColumnReceived(ClientLevel level,
                                                  ResourceKey<Level> dimension,
                                                  int chunkX, int chunkZ,
                                                  VoxelColumnData columnData) {
                    ingestColumn(bridgeDead, level, dimension, chunkX, chunkZ, columnData);
                }
            };
            consumerRegistrar.accept(consumer);

            LSSLogger.info("Voxy detected — registered raw ingest bridge");
            return true;
        } catch (ClassNotFoundException e) {
            LSSLogger.warn("Voxy compat: class not found — " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            LSSLogger.warn("Voxy compat: method not found — " + e.getMessage());
            return false;
        } catch (Throwable e) {
            LSSLogger.error("Failed to initialize Voxy compat", e);
            return false;
        }
    }

    /** The ingest body — the pre-#71 consumer lambda, unchanged, hoisted so the anonymous
     *  consumer class stays a thin dispatch shell. */
    private static void ingestColumn(AtomicBoolean bridgeDead,
                                     ClientLevel level,
                                     ResourceKey<Level> dimension, int chunkX, int chunkZ,
                                     VoxelColumnData columnData) {
        if (bridgeDead.get()) {
            // The latch stops the churn, but the receive handler already stamped this
            // position received (ts>0): returning SILENTLY would keep — and persist —
            // a stamp for data Voxy never stored, poisoning the cache across sessions
            // (after a Voxy upgrade every such position resyncs up_to_date and stays
            // a hole). Report instead: each position parks honestly (ts=-1 +
            // session-satisfied) at the bounded failure cap.
            reportSink.report(dimension, chunkX, chunkZ);
            return;
        }
        try {
            Object worldId = worldIdentifierOf.invoke(level);
            if (worldId == null) {
                // Voxy has no storage for this world yet (e.g. mid open-to-LAN
                // transition) — without the report the column is silently lost
                // and never re-requested.
                reportSink.report(dimension, chunkX, chunkZ);
                return;
            }
            boolean allAccepted = true;
            for (var s : columnData.sections()) {
                // H-12 guard: LSS ships "absent light layer = null" (the universal
                // "absent means all-zero" wire default). Voxy renders a null sky-light
                // layer as full daylight, so a no-sky dimension (nether/end,
                // hasSkyLight()==false) — where MC never stores sky light, so every
                // section arrives null — would light its topmost surfaces as if skylit
                // until vanilla loaded the chunk. Hand Voxy an explicit all-zero
                // (present, non-empty) DataLayer so those surfaces render dark. Overworld
                // skylit surfaces ship a real non-null layer and are unaffected.
                DataLayer skyLight = s.skyLight() != null ? s.skyLight() : new DataLayer(new byte[2048]);
                allAccepted &= (boolean) rawIngest.invoke(worldId, s.section(),
                        chunkX, s.sectionY(), chunkZ,
                        s.blockLight(), skyLight);
            }
            if (!allAccepted) {
                reportSink.report(dimension, chunkX, chunkZ);
            }
        } catch (LinkageError e) {
            // Incompatible Voxy: this will never succeed for any column. Latch to
            // stop re-serve churn, but keep the stamps honest — this column (and every
            // later one, via the latch branch above) still reports so it parks at
            // ts=-1 instead of retaining a fabricated received-stamp.
            bridgeDead.set(true);
            LSSLogger.error("Voxy raw ingest is incompatible with this Voxy version — "
                    + "disabling the bridge for this session", e);
            reportSink.report(dimension, chunkX, chunkZ);
        } catch (Throwable e) {
            if (e instanceof Error && !(e instanceof AssertionError)) throw (Error) e;
            LSSLogger.error("Voxy raw ingest failed", e);
            reportSink.report(dimension, chunkX, chunkZ);
        }
    }

    /**
     * Resolve the ingest-backlog probe chain. SEPARATE failure domain from the ingest
     * handles: a Voxy that renames this surface costs only the pacing signal (probe
     * permanently -1), never the bridge. All three handles are (re)assigned atomically-
     * enough for the single-threaded init/poll contract: nulled first, so a failed
     * re-resolve can never leave a stale mixed set.
     */
    private static void initBacklogProbe(MethodHandles.Lookup lookup, Class<?> ingestClass) {
        voxyGetInstance = null;
        getIngestService = null;
        getTaskCount = null;
        try {
            Class<?> voxyCommonClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyCommon");
            Class<?> voxyInstanceClass = classResolver.resolve("me.cortex.voxy.commonImpl.VoxyInstance");
            var instanceHandle = lookup.findStatic(voxyCommonClass, "getInstance",
                    MethodType.methodType(voxyInstanceClass))
                    .asType(MethodType.methodType(Object.class));
            var serviceHandle = lookup.findVirtual(voxyInstanceClass, "getIngestService",
                    MethodType.methodType(ingestClass))
                    .asType(MethodType.methodType(Object.class, Object.class));
            var countHandle = lookup.findVirtual(ingestClass, "getTaskCount",
                    MethodType.methodType(int.class))
                    .asType(MethodType.methodType(int.class, Object.class));
            // Assign only once ALL three resolved — a partial chain must read as absent.
            voxyGetInstance = instanceHandle;
            getIngestService = serviceHandle;
            getTaskCount = countHandle;
        } catch (Throwable e) {
            LSSLogger.warn("Voxy compat: ingest-backlog probe unavailable — request pacing "
                    + "will not see Voxy's ingest queue (" + e + ")");
        }
    }

    /**
     * Live Voxy ingest backlog in sections, or -1 when unavailable: probe unresolved, no
     * VoxyInstance yet (pre-join / post-shutdown — routine, never logged), or any throw
     * (contained; true Errors propagate). Main client thread, up to 20 Hz — the terminal
     * call reads a semaphore permit count, safe at this cadence.
     */
    static int probeIngestBacklog() {
        var instanceProbe = voxyGetInstance;
        if (instanceProbe == null) return -1;
        try {
            Object instance = (Object) instanceProbe.invokeExact();
            if (instance == null) return -1;
            Object service = (Object) getIngestService.invokeExact(instance);
            if (service == null) return -1;
            return (int) getTaskCount.invokeExact(service);
        } catch (Throwable e) {
            if (e instanceof Error err && !(e instanceof AssertionError)) throw err;
            return -1;
        }
    }

    private static void initConfigHandles() throws Throwable {
        if (getVoxyConfig != null) return;
        var lookup = MethodHandles.lookup();
        Class<?> voxyConfigClass = classResolver.resolve("me.cortex.voxy.client.config.VoxyConfig");
        var configField = voxyConfigClass.getField("CONFIG");
        // Type-agnostic on purpose (review 2026-07-27): shipped Voxy 0.2.16 declares this
        // field as float, but the in-development Voxy source declares it int — an
        // exact-typed findGetter would throw on the next Voxy release and silently kill
        // slider adoption (the catch below returns empty). unreflectGetter + asType widens
        // int/float/double alike.
        var sectionRenderDistField = voxyConfigClass.getField("sectionRenderDistance");
        getSectionRenderDist = lookup.unreflectGetter(sectionRenderDistField)
                .asType(MethodType.methodType(double.class, Object.class));
        // Assign getVoxyConfig last — it's the guard checked by callers
        getVoxyConfig = lookup.unreflectGetter(configField)
                .asType(MethodType.methodType(Object.class));
    }

    static OptionalInt getViewDistanceChunks() {
        try {
            initConfigHandles();
            Object config = (Object) getVoxyConfig.invokeExact();
            double sectionDist = (double) getSectionRenderDist.invokeExact(config);
            return OptionalInt.of((int) Math.round(sectionDist * 32));
        } catch (Throwable e) {
            return OptionalInt.empty();
        }
    }
}
