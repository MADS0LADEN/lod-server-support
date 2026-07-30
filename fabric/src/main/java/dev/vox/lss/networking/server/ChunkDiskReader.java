package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.compat.MoonriseReadCompat;
import dev.vox.lss.mixin.AccessorIOWorker;
import dev.vox.lss.mixin.AccessorServerChunkCache;
import dev.vox.lss.mixin.AccessorSimpleRegionStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import java.lang.invoke.WrongMethodTypeException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Async region file reader for Fabric. The shared base owns the executor, result queues,
 * and error triage; this class only captures the MC handles and serializes via
 * {@link NbtSectionSerializer}.
 */
public class ChunkDiskReader extends AbstractChunkDiskReader {

    // IOWorker$Priority is package-private and cannot be named here, so the ordinal is
    // pinned. Verified against the 26.2 jar: FOREGROUND(0), BACKGROUND(1), SHUTDOWN(2) — scheduleWithResult takes the
    // priority as an int, which is how vanilla passes it too. The SerializerParityGameTests
    // byte-parity test exercises this path end-to-end but does NOT pin the enum order (any
    // in-range ordinal returns identical bytes); a vanilla reorder must be re-verified by hand.
    //
    // Where this lands us on the shared per-dimension executor: vanilla's chunk loads
    // (loadAsync -> submitTask) run FOREGROUND, and vanilla's chunk saves (storePendingChunk) run
    // BACKGROUND. So LOD reads at BACKGROUND sit strictly below the loads players wait on, and
    // tie with saves — an improvement for both, since chunkMap.read used to put LOD reads at
    // FOREGROUND, level with vanilla's loads and ahead of its saves.
    private static final int IOWORKER_PRIORITY_BACKGROUND = 1;

    private final boolean useBackgroundReadPriority;
    // One-shot guard for the "background priority unavailable" warning (a chunk-IO-overhaul mod
    // replaced vanilla's IOWorker executor — see backgroundReaderOrFallback). Warn once, not per read.
    private final AtomicBoolean backgroundUnavailableWarned = new AtomicBoolean();
    // Server-wide, one-way latch: set the first time the background-priority path resolves as
    // incompatible (a null handle OR any throwable from accessor resolution — a chunk-IO-overhaul
    // mod replaced vanilla's IOWorker). A chunk-IO mod overhauls IO globally, so once any read
    // resolves incompatible every subsequent read (every dimension) skips the accessor and uses the
    // foreground path with the adaptive throttle engaged. Never cleared — no flapping.
    private volatile boolean backgroundIncompatible = false;

    // One-way latch for the Moonrise rung, TYPED to the linkage/adaptation failure domain only
    // (WrongMethodTypeException / adaptation ClassCastException / LinkageError — deterministic
    // "the resolved handle doesn't fit" shapes where every future invoke would fail identically).
    // The CCE rung is deliberately broader than pure handle adaptation: it also catches a cast
    // failing synchronously INSIDE Moonrise's body (impossible on 1.1.0 — its mixin interfaces
    // are applied to ServerLevel itself — but a future Moonrise could change that); accepted,
    // because the degradation is fail-safe (warn once + the exact pre-bridge ladder).
    // Moonrise's own synchronous runtime throws (e.g. a read racing server shutdown hits
    // PrioritisedTask.queue()'s IllegalStateException) are per-chunk triage and must NOT latch —
    // on Paper the identical throw is per-read triage with no latch, and this rung mirrors Paper.
    private volatile boolean moonriseIncompatible = false;
    private final AtomicBoolean moonriseIncompatibleWarned = new AtomicBoolean();

    private final boolean useNbtTranscode;

    /** Convenience for tests/gametests: production defaults for the serialize path
     *  (transcode ON — the {@code useNbtTranscode} default). */
    public ChunkDiskReader(int threadCount, boolean useBackgroundReadPriority) {
        this(threadCount, useBackgroundReadPriority, true);
    }

    public ChunkDiskReader(int threadCount, boolean useBackgroundReadPriority, boolean useNbtTranscode) {
        super(threadCount);
        this.useBackgroundReadPriority = useBackgroundReadPriority;
        this.useNbtTranscode = useNbtTranscode;
    }

    public void submitReadDirect(UUID playerUuid, String dimension, ServerLevel level,
                                  int chunkX, int chunkZ, long submissionOrder) {
        var registryAccess = level.registryAccess();
        var chunkMap = ((AccessorServerChunkCache) level.getChunkSource()).getChunkMap();
        NbtSectionSerializer.ChunkNbtRead read = chooseReadPath(level, chunkMap);
        // The mask entry is captured at submit time (the level is in hand here); the read
        // itself runs on the reader pool where only the dimension string survives.
        var maskEntry = XrayMaskManager.entryForActive(level);
        int minSectionY = level.getMinSectionY();
        int maxSectionY = level.getMaxSectionY();
        submitRead(playerUuid, chunkX, chunkZ, dimension, submissionOrder,
                () -> NbtSectionSerializer.readAndSerializeSections(read, registryAccess, chunkX, chunkZ,
                        maskEntry, minSectionY, maxSectionY, this.useNbtTranscode));
    }

    /**
     * The per-submit read ladder. Package-private (and {@code level}-tolerant of null) so the
     * rung ORDER is unit-testable without a live {@code ServerLevel}:
     * <ol>
     * <li>Config rollback ({@code useBackgroundReadPriority=false}) or a latched
     *     {@code backgroundIncompatible} → foreground {@code chunkMap.read} (unchanged — the
     *     flag stays a true full rollback on every platform/mod combination, mirroring Paper;
     *     once latched, the throttle was engaged at latch time and the accessor is skipped).</li>
     * <li>The Moonrise rung (NEW): when Moonrise-Fabric is present and its IO API resolved,
     *     read via {@code loadDataAsync(..., Priority.LOW)} — consulted BEFORE the IOWorker
     *     accessor, because on a Moonrise server that accessor can only ever NPE on the nulled
     *     worker and latch the C2ME-style fallback. Moonrise LOW is the read protection (the
     *     analogue of Paper's), so the adaptive throttle stays disengaged. Bonus over the
     *     vanilla background path: {@code loadDataAsync} serves in-progress writes, so this
     *     rung has no read-your-writes gap.</li>
     * <li>The vanilla IOWorker BACKGROUND path with its C2ME-style latch+throttle fallback
     *     (unchanged — and the automatic degradation target if the Moonrise rung latches).</li>
     * </ol>
     */
    NbtSectionSerializer.ChunkNbtRead chooseReadPath(ServerLevel level, ChunkMap chunkMap) {
        if (!this.useBackgroundReadPriority || this.backgroundIncompatible) {
            return foregroundRead(chunkMap);
        }
        if (!this.moonriseIncompatible) {
            var bridge = moonriseBridgeOrNull();
            if (bridge != null) {
                return moonriseRead(bridge, level, chunkMap);
            }
        }
        return backgroundReaderOrFallback(chunkMap);
    }

    /** The plain foreground read. Package-private seam so ladder tests can observe which rung
     *  was chosen (and the inline latch fallback) without a live {@code ChunkMap}. */
    NbtSectionSerializer.ChunkNbtRead foregroundRead(ChunkMap chunkMap) {
        return (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
    }

    /** Package-private seam: the resolved Moonrise LOW-priority read, or null. Production
     *  consults the per-JVM bridge holder; tests inject recording/throwing bridges. */
    MoonriseReadCompat.LowPriorityRead moonriseBridgeOrNull() {
        return MoonriseReadCompat.resolveOrNull();
    }

    /**
     * The Moonrise rung's read, with the TYPED failure split (see {@code moonriseIncompatible}):
     * a linkage/adaptation throw latches + warns once + falls back inline to the foreground read
     * (this read and the already-queued cohort must not burst {@code disk.errors} — the closure
     * re-checks the latch at execution time, since up to threads + 32×threads closures bound
     * before the latch can still be queued); any other Throwable propagates to the base's
     * per-chunk error triage and the rung stays active — Paper parity.
     */
    private NbtSectionSerializer.ChunkNbtRead moonriseRead(MoonriseReadCompat.LowPriorityRead bridge,
                                                           ServerLevel level, ChunkMap chunkMap) {
        return (cx, cz) -> {
            if (this.moonriseIncompatible) {
                return foregroundRead(chunkMap).read(cx, cz);
            }
            try {
                return bridge.read(level, cx, cz);
            } catch (WrongMethodTypeException | ClassCastException | LinkageError t) {
                onMoonriseIncompatible(t);
                return foregroundRead(chunkMap).read(cx, cz);
            }
        };
    }

    /** Latch the Moonrise rung incompatible (one-way) and warn exactly once. Subsequent submits
     *  fall down the vanilla ladder — which on a real Moonrise server then latches
     *  {@code backgroundIncompatible} (nulled worker) and engages the throttle: exactly the
     *  pre-bridge behavior, reached automatically. */
    private void onMoonriseIncompatible(Throwable t) {
        this.moonriseIncompatible = true;
        if (this.moonriseIncompatibleWarned.compareAndSet(false, true)) {
            warnMoonriseIncompatible(t);
        }
    }

    /** Emit the single Moonrise-latch warning. Overridable (package-private) for the warn-once pin. */
    void warnMoonriseIncompatible(Throwable t) {
        LSSLogger.warn("Moonrise LOW-priority read invoke failed with a linkage/adaptation error —"
                + " disabling the Moonrise read path for this session and falling back to the"
                + " standard read ladder. A Moonrise update likely changed its internals;"
                + " please report this.", t);
    }

    /**
     * BACKGROUND-priority reader: schedules on the IOWorker's own single-threaded executor below
     * vanilla's FOREGROUND reads, reading straight from RegionFileStorage (the thread-safe target
     * of IOWorker's own reads).
     *
     * <p>Going straight to storage skips IOWorker's pendingWrites, so this path gives up
     * read-your-writes: a chunk saved but not yet flushed reads as its previous on-disk state.
     * The window is narrow — a save already queued ahead of us flushes first (same priority, FIFO)
     * — but it is real when the save is queued behind our read. Acceptable for LOD: the save that
     * we raced is itself what marks the column dirty, so the dirty broadcast re-request heals it.
     * (Paper has no such gap; Moonrise serves pending writes.)
     *
     * <p><b>Fail-safe (A→B fallback).</b> A chunk-IO-overhaul mod (C2ME's chunkio rewrite, and
     * structurally similar mods) replaces vanilla's single-threaded IOWorker with its own concurrent
     * IO system, leaving {@code consecutiveExecutor}/{@code storage} null on the vanilla shell — and
     * an unanticipated mod could just as well make accessor resolution <em>throw</em>. Either a null
     * handle OR any {@link Throwable} latches the server-wide {@code backgroundIncompatible} flag,
     * enables the adaptive read throttle (so LOD reads still yield to gameplay via self-restraint —
     * Approach B), warns once, and reads through the vanilla {@code chunkMap.read} path — which those
     * mods implement and prioritize themselves. LSS loses only its own background scheduling, not
     * correctness. Detection must never itself throw.
     */
    // Package-private (not private) so the fail-safe path is unit-testable via an injected resolver.
    NbtSectionSerializer.ChunkNbtRead backgroundReaderOrFallback(ChunkMap chunkMap) {
        PriorityConsecutiveExecutor executor = null;
        RegionFileStorage storage = null;
        try {
            var handles = resolveBackgroundHandles(chunkMap);
            executor = handles.executor();
            storage = handles.storage();
        } catch (Throwable t) {
            // Fall through with handles still null: a chunk-IO-overhaul mod changed vanilla's
            // internals so that even reading them fails. Treat exactly like a null handle.
        }
        if (backgroundReadUnavailable(executor, storage)) {
            onBackgroundIncompatible();
            return (cx, cz) -> chunkMap.read(new ChunkPos(cx, cz));
        }
        return backgroundRead(executor, storage);
    }

    /** Resolve the IOWorker's [executor, storage] handles from the chunk map. Overridable
     *  (package-private) so a test can inject a resolver that returns nulls or throws — simulating a
     *  chunk-IO-overhaul mod — without a live MC IOWorker, which no test environment can null out. */
    Handles resolveBackgroundHandles(ChunkMap chunkMap) {
        var worker = (AccessorIOWorker) ((AccessorSimpleRegionStorage) chunkMap).lss$getWorker();
        return new Handles(worker.lss$getConsecutiveExecutor(), worker.lss$getStorage());
    }

    /** The IOWorker handles the background path needs; either may be null on an incompatible server. */
    record Handles(PriorityConsecutiveExecutor executor, RegionFileStorage storage) {}

    /** Latch the server-wide incompatible flag, engage the adaptive throttle (Approach B), and warn
     *  exactly once. Idempotent + one-way: the latch stops later reads from re-attempting the
     *  accessor, and the CAS guard bounds the log to a single line for the server's lifetime. */
    private void onBackgroundIncompatible() {
        this.backgroundIncompatible = true;          // latch: subsequent reads skip the accessor
        enableAdaptiveThrottleFallback();            // B protects gameplay via self-restraint
        if (this.backgroundUnavailableWarned.compareAndSet(false, true)) {
            warnBackgroundUnavailable();
        }
    }

    /** Emit the single fallback warning. Overridable (package-private) so a test can confirm it
     *  fires exactly once across repeated triggering reads. Names Moonrise instead of C2ME when
     *  the Moonrise mod is present (reachable there only if the bridge failed to resolve or
     *  latched incompatible — the fixed C2ME text would be misleading). */
    void warnBackgroundUnavailable() {
        String cause = MoonriseReadCompat.moonriseDetected()
                ? "which Moonrise causes by replacing vanilla chunk IO (and the Moonrise"
                        + " LOW-priority read path is unavailable — see the preceding warning)"
                : "which a chunk-IO-overhaul mod (e.g. C2ME) causes by replacing vanilla chunk IO";
        LSSLogger.warn("Background-priority disk reads unavailable — vanilla's IOWorker executor is"
                + " absent, " + cause + ". "
                + Brand.shortName() + " switched to adaptive read throttling so LOD reads still yield to"
                + " gameplay. Set useBackgroundReadPriority=false to disable read protection entirely.");
    }

    /** True when the vanilla IOWorker executor path is unusable — either handle absent because a
     *  chunk-IO-overhaul mod replaced vanilla chunk IO. Package-private + typed as Object so the
     *  fallback CONDITION (either handle null → fall back) is unit-testable without an MC IOWorker,
     *  which no test environment can give a null executor. */
    static boolean backgroundReadUnavailable(Object executor, Object storage) {
        return executor == null || storage == null;
    }

    /** Package-private test seam: whether the one-way incompatible latch has fired. */
    boolean isBackgroundIncompatibleForTest() {
        return this.backgroundIncompatible;
    }

    /** Package-private test seam: whether the Moonrise-rung one-way latch has fired. */
    boolean isMoonriseIncompatibleForTest() {
        return this.moonriseIncompatible;
    }

    /**
     * The Moonrise rung is live-only (no CI runtime loads the mod), so — like the C2ME
     * fallback's {@code read_throttle=ENGAGED} token — it gets an end-to-end diagnostics
     * signal: {@code read_path=moonrise-low} while active, {@code read_path=moonrise-incompatible}
     * after the typed latch. Absent entirely when Moonrise is absent, the config flag is off,
     * or the vanilla path latched first — existing diagnostics goldens do not move.
     */
    @Override
    public String getDiagnostics() {
        String base = super.getDiagnostics();
        if (!this.useBackgroundReadPriority) return base;
        if (this.moonriseIncompatible) return base + ", read_path=moonrise-incompatible";
        if (this.backgroundIncompatible) return base;
        return moonriseBridgeOrNull() != null ? base + ", read_path=moonrise-low" : base;
    }

    private NbtSectionSerializer.ChunkNbtRead backgroundRead(PriorityConsecutiveExecutor executor,
                                                             RegionFileStorage storage) {
        return (cx, cz) -> {
            var pos = new ChunkPos(cx, cz);
            return executor.scheduleWithResult(IOWORKER_PRIORITY_BACKGROUND,
                    (CompletableFuture<Optional<CompoundTag>> f) -> {
                        try {
                            f.complete(Optional.ofNullable(storage.read(pos)));
                        } catch (Exception e) {
                            // As broad as vanilla's own read task (submitThrowingTask catches
                            // Exception): a corrupt region can fail unchecked, and anything that
                            // escapes here leaves the future uncompleted, stalling a reader thread
                            // until DISK_READ_TIMEOUT_SECONDS instead of triaging immediately.
                            f.completeExceptionally(e);
                        }
                    });
        };
    }
}
