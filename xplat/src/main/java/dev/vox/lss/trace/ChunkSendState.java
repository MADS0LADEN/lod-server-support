package dev.vox.lss.trace;

import dev.vox.lss.common.LSSLogger;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Chunk-delivery state resolver (move-desync-tracer-plan.md §1.3): answers "what does the
 * server believe about chunk (cx, cz) for this player" on one of two rungs, resolved once
 * per JVM (the {@code MoonriseReadCompat.build()} seam pattern — instance-scoped
 * {@code build(modLoaded, lookup, warn)} so the Tier 1 pins are test-order independent).
 *
 * <p><b>Moonrise rung</b> (the live server): {@code ChunkSystemServerPlayer} (an interface
 * Moonrise mixes into {@code ServerPlayer}) → {@code moonrise$getChunkLoader()} —
 * null-checked into a per-call "none" (review F-10: it returns null in the
 * dimension-change window) — then the public {@code getSentChunksRaw()} plus reflective
 * {@code Field} reads of {@code sendQueue}, {@code chunkTicketStage},
 * {@code lastSendDistance}, {@code lastChunkX/Z} (all verified private on Moonrise-Fabric
 * 1.1.0, which ships MOJANG-mapped — its own member names are untouched by remapping).
 * All reads are server-thread (Moonrise asserts TickThread on every mutator).
 *
 * <p><b>Vanilla rung</b> (local rigs): {@code chunkSender.isPending(key)} — both public,
 * zero crash surface. Its field is named {@code not_pending}, NOT {@code sent} (review
 * U-12): vanilla removes from {@code pendingChunks} at collection time and never-tracked
 * chunks are also "not pending" — a strictly weaker predicate that must not silently
 * aggregate with the Moonrise mask.
 *
 * <p>Moonrise present but unresolvable → rung {@code none} + one warn: falling to the
 * vanilla rung there would answer from a sender Moonrise bypasses — confidently wrong,
 * the one §0 sin.
 */
final class ChunkSendState {

    private static final String CHUNK_SYSTEM_SERVER_PLAYER =
            "ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer";

    /** Injection seam for class resolution (the {@code MoonriseReadCompat} shape). */
    interface ClassLookup {
        Class<?> lookup(String name) throws Throwable;
    }

    /** Injection seam for the once-per-JVM drift warning. */
    interface DriftWarn {
        void warn(String detail, Throwable cause);
    }

    private static final class Holder {
        static final ChunkSendState INSTANCE = buildProduction();
    }

    static ChunkSendState resolve() {
        return Holder.INSTANCE;
    }

    private static ChunkSendState buildProduction() {
        boolean moonrise;
        try {
            moonrise = dev.vox.lss.platform.LoaderServices.get().isModLoaded("moonrise");
        } catch (Throwable t) {
            moonrise = false;
        }
        return build(moonrise, Class::forName,
                (detail, cause) -> LSSLogger.warn("Move trace chunk-send state unavailable ("
                        + detail + (cause != null ? ": " + cause : "") + ") — send-state fields"
                        + " will be absent (rung=none)"));
    }

    private final String rung;
    // Moonrise rung artifacts; all null unless rung == moonrise.
    private final Method getChunkLoader;
    private final Method getSentChunksRaw;
    private final Field sendQueue;
    private final Field chunkTicketStage;
    private final Field lastSendDistance;
    private final Field lastChunkX;
    private final Field lastChunkZ;

    private ChunkSendState(String rung, Method getChunkLoader, Method getSentChunksRaw,
                           Field sendQueue, Field chunkTicketStage, Field lastSendDistance,
                           Field lastChunkX, Field lastChunkZ) {
        this.rung = rung;
        this.getChunkLoader = getChunkLoader;
        this.getSentChunksRaw = getSentChunksRaw;
        this.sendQueue = sendQueue;
        this.chunkTicketStage = chunkTicketStage;
        this.lastSendDistance = lastSendDistance;
        this.lastChunkX = lastChunkX;
        this.lastChunkZ = lastChunkZ;
    }

    /** Instance-scoped resolution — each test builds a fresh instance; no JVM-wide state. */
    static ChunkSendState build(boolean moonriseLoaded, ClassLookup lookup, DriftWarn warn) {
        if (!moonriseLoaded) {
            return new ChunkSendState(MoveRow.RUNG_VANILLA, null, null, null, null, null, null, null);
        }
        try {
            Class<?> playerIface = lookup.lookup(CHUNK_SYSTEM_SERVER_PLAYER);
            Method getLoader = playerIface.getMethod("moonrise$getChunkLoader");
            // The loader data class comes from the matched method's own return type —
            // never a hardcoded nested-class name (the MoonriseReadCompat lesson: Moonrise
            // shades/moves things; the method IS the contract).
            Class<?> loaderClass = getLoader.getReturnType();
            Method sentRaw = loaderClass.getMethod("getSentChunksRaw");
            if (!LongOpenHashSet.class.isAssignableFrom(sentRaw.getReturnType())) {
                warn.warn("getSentChunksRaw returns " + sentRaw.getReturnType().getName(), null);
                return none();
            }
            Field queue = declared(loaderClass, "sendQueue", LongHeapPriorityQueue.class);
            Field stage = declared(loaderClass, "chunkTicketStage", Long2ByteOpenHashMap.class);
            Field radius = declared(loaderClass, "lastSendDistance", int.class);
            Field cx = declared(loaderClass, "lastChunkX", int.class);
            Field cz = declared(loaderClass, "lastChunkZ", int.class);
            return new ChunkSendState(MoveRow.RUNG_MOONRISE, getLoader, sentRaw,
                    queue, stage, radius, cx, cz);
        } catch (Throwable t) {
            warn.warn("moonrise present but send-state shape did not resolve", t);
            return none();
        }
    }

    private static ChunkSendState none() {
        return new ChunkSendState(MoveRow.RUNG_NONE, null, null, null, null, null, null, null);
    }

    private static Field declared(Class<?> owner, String name, Class<?> expectedType)
            throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        if (!expectedType.isAssignableFrom(f.getType())) {
            throw new NoSuchFieldException(name + " has type " + f.getType().getName()
                    + ", expected " + expectedType.getName());
        }
        f.setAccessible(true);
        return f;
    }

    /** The resolved rung name for the boot row and the diag line. */
    String rungName() {
        return rung;
    }

    /** One send-state capture for the production resolver. Server thread only. */
    static MoveRow.SendState capture(ServerPlayer player, int cx, int cz) {
        return resolve().captureFor(player, cx, cz);
    }

    MoveRow.SendState captureFor(ServerPlayer player, int cx, int cz) {
        try {
            switch (rung) {
                case MoveRow.RUNG_MOONRISE -> {
                    return captureResolved(getChunkLoader.invoke(player), cx, cz);
                }
                case MoveRow.RUNG_VANILLA -> {
                    var listener = player.connection;
                    if (listener == null) return MoveRow.SendState.none();
                    boolean pending = listener.chunkSender.isPending(MoveEventMath.mcChunkKey(cx, cz));
                    return MoveRow.SendState.vanilla(cx, cz, !pending);
                }
                default -> {
                    return MoveRow.SendState.none();
                }
            }
        } catch (Throwable t) {
            return MoveRow.SendState.none();
        }
    }

    /** Moonrise-rung capture with the per-call null ladder (review F-10: the loader is
     *  null in the dimension-change window — "none" per call, never a latch).
     *  Package-visible so the Tier 1 compat test can drive it against a
     *  real-package-name stub loader. */
    MoveRow.SendState captureResolved(Object loader, int cx, int cz) {
        if (loader == null) return MoveRow.SendState.none();
        try {
            return captureFromLoader(loader, cx, cz);
        } catch (Throwable t) {
            return MoveRow.SendState.none();
        }
    }

    private MoveRow.SendState captureFromLoader(Object loader, int cx, int cz) throws Exception {
        var sent = (LongOpenHashSet) getSentChunksRaw.invoke(loader);
        int mask25 = 0;
        int mask9 = 0;
        for (int dz = -MoveEventMath.MASK_5X5_RADIUS; dz <= MoveEventMath.MASK_5X5_RADIUS; dz++) {
            for (int dx = -MoveEventMath.MASK_5X5_RADIUS; dx <= MoveEventMath.MASK_5X5_RADIUS; dx++) {
                if (!sent.contains(MoveEventMath.mcChunkKey(cx + dx, cz + dz))) continue;
                mask25 |= 1 << MoveEventMath.maskBit5x5(dx, dz);
                int bit9 = MoveEventMath.maskBit3x3(dx, dz);
                if (bit9 >= 0) mask9 |= 1 << bit9;
            }
        }
        var stages = (Long2ByteOpenHashMap) chunkTicketStage.get(loader);
        // Absent keys read 0 == CHUNK_TICKET_STAGE_NONE — semantically exact.
        int stage = stages.get(MoveEventMath.mcChunkKey(cx, cz));
        var queue = (LongHeapPriorityQueue) sendQueue.get(loader);
        Integer headStage = queue.isEmpty() ? null
                : (int) stages.get(queue.firstLong());
        return MoveRow.SendState.moonrise(cx, cz, mask25, mask9, stage,
                lastSendDistance.getInt(loader), lastChunkX.getInt(loader),
                lastChunkZ.getInt(loader), queue.size(), headStage);
    }
}
