package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for async chunk disk readers. Provides executor setup, per-player result
 * queues, diagnostics, the full submit/triage envelope (saturation, errors, all-air,
 * not-found), and shutdown logic. Subclasses supply only the platform-specific
 * {@link ReadOperation} that produces serialized section bytes.
 */
public abstract class AbstractChunkDiskReader {

    /** Platform hook: read the chunk's NBT and serialize visible sections to wire bytes.
     *  Returns null for "not found"; an empty array for an all-air chunk. */
    @FunctionalInterface
    public interface ReadOperation {
        byte[] read() throws Exception;
    }

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final int QUEUE_CAPACITY_PER_THREAD = 32;

    // Saturation is a normal, self-healing path: the result is dropped silently and the
    // client's next want-set re-declares the position (v17 — nothing is bounced back). Since
    // the router's headroom gate stops submits into a full pool, a rejection here is now a
    // residual (race/shutdown) rather than the steady state, but a burst can still reject many
    // reads per second, and one WARN per position floods the console (#32). Aggregate to at
    // most one warning per minute carrying the rejected count; per-delivery detail stays on
    // the debug path in OffThreadProcessor.
    private static final long SATURATION_WARN_INTERVAL_MS = 60_000;
    private final LogThrottle saturationWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);
    // Read timeouts are documented transients (miss-memo A/B finding) — same aggregation.
    private final LogThrottle timeoutWarn = new LogThrottle(SATURATION_WARN_INTERVAL_MS);

    private final ExecutorService executor;
    private final ArrayBlockingQueue<Runnable> workQueue;
    private final int threadCount;
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<ChunkReadResult>> playerResults = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    // Pool tasks accepted but not yet finished — the adaptive throttle's in-flight input.
    // A dedicated counter (not submitted-completed): store hits occupy pool capacity like
    // any task but are EXCLUDED from disk.submitted/completed by the rung contract, so
    // the counter pair no longer measures pool occupancy.
    private final AtomicInteger tasksInFlight = new AtomicInteger();

    // The LOD store (docs/planning/lod-store-implementation-plan.md §1): consulted by the
    // rung in readAndDeliver before any region IO. Null while lodStore=off. Volatile:
    // attached once at service init (before the first submit) from the server thread.
    private volatile dev.vox.lss.common.store.LodStoreService store;
    // Frame-form store serving (protocol 19): see setServeStoreFrames.
    private volatile boolean serveStoreFrames;

    // Adaptive read throttle (Approach B): null until a platform reader detects that its
    // background-priority path is incompatible (a chunk-IO-overhaul mod replaced vanilla IO) and
    // calls enableAdaptiveThrottleFallback(). Never set on a working-A server — A gives true
    // priority, so throttling would only cost LSS throughput for no gameplay benefit. Volatile:
    // enabled on the processing/submit thread, read by hasHeadroom() (submit thread) and fed by
    // recordRealCompletion() (pool threads).
    private volatile AdaptiveReadThrottle throttle;

    protected final DiskReaderDiagnostics diag = new DiskReaderDiagnostics();

    protected AbstractChunkDiskReader(int threadCount) {
        this.threadCount = threadCount;
        int queueCapacity = threadCount * QUEUE_CAPACITY_PER_THREAD;
        var workQueue = new ArrayBlockingQueue<Runnable>(queueCapacity);
        this.workQueue = workQueue;
        this.executor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                workQueue, r -> {
            var thread = new Thread(r, Brand.shortName() + " Disk Reader #" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /**
     * True when a submit will not be rejected. Single-submitter contract: only the
     * processing thread submits, and pool workers only DRAIN the queue, so a true result
     * cannot turn into a rejection before the same thread's next submit (the race is
     * pessimistic-only). This is what keeps disk saturation out of the client-visible
     * protocol: the router leaves the entry in the backlog instead of submitting into a
     * full pool.
     */
    public boolean hasHeadroom() {
        if (this.workQueue.remainingCapacity() <= 0) return false;   // pool queue full (unchanged)
        var t = this.throttle;                                       // null on the working-A path
        if (t != null) {
            // Approach B expressed as a headroom modifier: when the adaptive limit is reached the
            // router leaves the read in the want-set backlog (NO_DISK_HEADROOM) and the client
            // re-declares it — no bounce, no rate_limited (retired in v17). in-flight is the
            // dedicated pool-task counter (store hits occupy the pool too, but are excluded
            // from the submitted/completed pair by the store rung contract).
            if (!t.canSubmit(this.tasksInFlight.get())) return false;
        }
        return true;
    }

    /** The native→v20 body translator for pre-migration {@code wirefmt=19} store rows
     *  (C4, XVER §5.3): platform-wired ({@code NbtSectionSerializer.toV20} against the
     *  server's own registries). REQUIRED for 19-row serves — {@code raw() == v20} is a
     *  C2 pipeline invariant (the client decodes v20; the legacy egress translates FROM
     *  v20), so an unwired translator reads a 19-hit as an errored miss rather than
     *  leaking native bytes downstream. Runs on the reader pool (the emit tables are
     *  memoized and thread-safe). */
    private volatile java.util.function.UnaryOperator<byte[]> storeLegacyTranslator;
    /** Resolved once on the first 19-row frame serve (review #5 — {@code zstdOrNull()}
     *  runs a full compress/decompress self-test per call; per-serve probing burned
     *  that on EVERY legacy hit). A store frame existing at all proves the natives
     *  loaded, so one probe suffices for the process lifetime. */
    private volatile dev.vox.lss.common.store.StoreCodec legacyRowCodec;

    public final void setStoreLegacyTranslator(java.util.function.UnaryOperator<byte[]> t) {
        this.storeLegacyTranslator = t;
    }

    /** Attach the LOD store (lodStore != off). Must happen before the first submit. */
    public final void attachStore(dev.vox.lss.common.store.LodStoreService store) {
        this.store = store;
    }

    /**
     * Enable frame-form store serving (protocol 19, plan §3): the rung consults
     * {@code getFrame} instead of {@code get}, delivering the stored zstd frame
     * verbatim (zero decompress; raw-needing recipients materialize lazily on the
     * processing thread). Set by the services ONLY while wire compression is live —
     * with compression off, frame hits would cost every recipient a processing-thread
     * decompress that {@code get} pays on the reader pool instead. Volatile: set once
     * at service init.
     */
    public final void setServeStoreFrames(boolean serveFrames) {
        this.serveStoreFrames = serveFrames;
    }

    /**
     * Idempotently enable the adaptive-throttle fallback (a platform reader's background-priority
     * path reported itself incompatible — a chunk-IO-overhaul mod replaced vanilla IO). Safe from
     * any thread; the first caller wins. The throttle starts at the pool's full depth (optimistic),
     * so enabling it does not restrict until measured read latency actually rises.
     */
    protected final void enableAdaptiveThrottleFallback() {
        if (this.throttle == null) {
            synchronized (this) {
                if (this.throttle == null) {
                    this.throttle = AdaptiveReadThrottle.forPool(this.threadCount,
                            QUEUE_CAPACITY_PER_THREAD, LSSConstants.ADAPTIVE_READ_TARGET_LATENCY_MS);
                }
            }
        }
    }

    /** The adaptive throttle's current effective concurrency limit, or -1 when it is not engaged
     *  (the normal working-A path). For {@code /lsslod diag}. */
    public int adaptiveThrottleLimitOrDisabled() {
        var t = this.throttle;
        return t == null ? -1 : t.currentLimit();
    }

    /** Package-private test seam: the live throttle instance (null until engaged), so the
     *  in-package wiring test can drive its AIMD limit with synthetic latency samples without
     *  occupying a pool thread. Production reads throttle state via
     *  {@link #adaptiveThrottleLimitOrDisabled()}. */
    AdaptiveReadThrottle throttleForTest() {
        return this.throttle;
    }

    protected boolean isShutdown() {
        return this.isShutdown.get();
    }

    /**
     * Submit a read: the operation runs on the reader pool and its outcome is triaged into
     * the player's result queue (store hit / data / all-air / not-found / saturated).
     * The store rung inside {@link #readAndDeliver} runs first; only a MISS proceeds to
     * region IO and the {@code disk.*} counters — {@code disk.submitted} therefore counts
     * at the start of the NBT path, not here (the rung contract: hits are excluded from
     * the disk pair and the throttle EWMA). Error containment lives inside
     * {@code readAndDeliver} (broadened to {@link Throwable}: an {@link Error} — SOE on
     * corrupt NBT — still produces a result first, or the request would strand its
     * admission slot + dedup group; the re-throw after bookkeeping is best-effort only,
     * FutureTask captures it into a Future nobody inspects).
     */
    protected final void submitRead(UUID playerUuid, int chunkX, int chunkZ, String dimension,
                                     long submissionOrder, ReadOperation operation) {
        if (isShutdown()) return;

        try {
            this.tasksInFlight.incrementAndGet();
            this.executor.submit(() -> {
                try {
                    if (!isShutdown()) {
                        readAndDeliver(playerUuid, chunkX, chunkZ, dimension, submissionOrder, operation);
                    }
                } catch (Throwable t) {
                    // Last-resort containment (Phase 1 review MAJOR-1): every expected
                    // failure is handled INSIDE readAndDeliver (the store-rung belt, the
                    // op-region catch); reaching here means an unexpected throw outside
                    // those islands (result construction, queue append, an op-path Error
                    // re-thrown after its own bookkeeping). A result MUST still be
                    // delivered or the pending entry + dedup group wedge the position
                    // behind Duplicate.IN_FLIGHT for the whole session. Disk counters
                    // are deliberately untouched (state unknown — identity drift only on
                    // OOM-class events); a duplicate result for the Error path resolves
                    // as the documented ghost (pending already gone, silent drop).
                    LSSLogger.error("Unexpected failure delivering disk read at "
                            + chunkX + ", " + chunkZ, t);
                    addResult(playerUuid, ChunkReadResult.notFoundFromError(
                            playerUuid, chunkX, chunkZ, dimension, submissionOrder));
                } finally {
                    this.tasksInFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            this.tasksInFlight.decrementAndGet();
            // nanoTime, not currentTimeMillis: the wall clock can step backwards (NTP), which
            // would silence the aggregate warning exactly while the pool is behind demand.
            long rejected = this.saturationWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (rejected > 0) {
                LSSLogger.warn("Disk reader saturated: " + rejected + " chunk read(s) dropped"
                        + " since the last warning — clients re-request automatically; raise"
                        + " diskReaderThreads in lss-server-config.json if this persists");
            }
            // The bounce never consulted the store or storage: submitted+saturated+completed
            // recorded together so the at-rest identity (completed == outcomes) holds.
            this.diag.recordSubmitted();
            this.diag.recordSaturation();
            this.diag.recordCompleted(0);
            addResult(playerUuid, ChunkReadResult.saturated(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
        }
    }

    /** Record a REAL read completion: the diagnostics count plus, when the adaptive throttle is
     *  engaged, the measured submit->result latency (the 0-latency bounce/error-before-IO paths
     *  are NOT fed — they measured no IO and would poison the EWMA). */
    private void recordRealCompletion(long elapsedNanos) {
        this.diag.recordCompleted(elapsedNanos);
        var t = this.throttle;
        if (t != null) t.recordLatency(elapsedNanos);
    }

    /**
     * The store rung (§1 rung contract): consult the LOD store before any region IO. A
     * hit delivers the STORED bytes + STORED timestamp (delivery honesty — never a
     * fabricated fresh stamp) tagged {@code fromStore}, touching NEITHER the
     * {@code disk.*} counters NOR {@link #recordRealCompletion} — a sub-100 µs hit fed
     * to the AIMD EWMA would collapse the adaptive limit on exactly the C2ME-latched
     * servers where the throttle is the only gameplay protection. {@code byte[0]} from
     * the store means all-air (delivered as the all-air result shape, never as
     * not-found — null section bytes would read as an authoritative miss and seed the
     * miss memo). {@link dev.vox.lss.common.store.LodStoreService#get} is contained by
     * contract (a store failure reads as a miss and counts {@code store.errors}).
     */
    private boolean storeServedHit(UUID playerUuid, int chunkX, int chunkZ, String dimension,
                                    long submissionOrder) {
        var s = this.store;
        if (s == null) return false;
        long packed = dev.vox.lss.common.PositionUtil.packPosition(chunkX, chunkZ);
        long t0 = System.nanoTime();
        if (this.serveStoreFrames) {
            // Frame-form rung (protocol 19, plan §3): the stored zstd frame ships
            // VERBATIM — zero decompress here, zero compress downstream. Exactly ONE of
            // getFrame/get is consulted per submit (a getFrame miss falls to region IO,
            // never to a second get() of the same row).
            dev.vox.lss.common.store.LodStoreService.FrameHit hit;
            try {
                hit = s.getFrame(dimension, packed);
            } catch (Throwable t) {
                s.diagnostics().recordError();
                hit = null;
            }
            if (hit == null) {
                s.diagnostics().recordMiss();
                return false;
            }
            if (hit.usize() > 0 && (hit.frame() == null || hit.frame().length == 0)) {
                // Contract-violation belt, twin of the raw rung's null-sectionBytes
                // guard (4-agent round, store F1): a data-claiming FrameHit with no
                // frame would flow downstream as a null-bytes holder and read as
                // ALL-AIR — an authoritative clearing column fabricated over real
                // terrain. Contain as an errored miss; the NBT ladder serves truth.
                s.diagnostics().recordError();
                s.diagnostics().recordMiss();
                return false;
            }
            if (hit.usize() == 0) {
                s.diagnostics().recordHit(System.nanoTime() - t0);
                // All-air: same result shape as the raw rung (null section bytes,
                // never not-found — a null read as an authoritative miss would seed
                // the miss memo falsely).
                addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ, null,
                        dimension, 0, hit.columnTimestamp(),
                        false, false, false, true, submissionOrder, 0L));
                return true;
            }
            if (hit.wirefmt() == dev.vox.lss.common.store.LodStoreService.WIREFMT_NATIVE_19) {
                // Pre-migration native-layout row (C4, XVER §5.3): decompress (fhash
                // already validated in the store) and translate to the canonical v20
                // form HERE — raw()==v20 is a C2 pipeline invariant, and a verbatim
                // native frame would be mis-decoded by every consumer. Delivered as
                // RAW bytes; the delivery re-compresses per recipient capability
                // (ColumnBytes.frame()) as with any raw source. Cost: tens of µs,
                // doubly decaying (the walk migrates rows; clients update).
                byte[] v20 = translateLegacyStoreRow(s, hit.frame(), hit.usize());
                if (v20 == null) {
                    return false; // errored miss, counted; the NBT ladder serves truth
                }
                // Hit recorded only on translation SUCCESS (review m17): a failed
                // translation is an errored miss and must not also book a hit.
                s.diagnostics().recordHit(System.nanoTime() - t0);
                addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ, v20,
                        dimension, v20.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                        hit.columnTimestamp(),
                        false, false, false, true, submissionOrder, 0L));
                return true;
            }
            s.diagnostics().recordHit(System.nanoTime() - t0);
            int estimatedBytes = hit.usize() + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
            addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ, null,
                    dimension, estimatedBytes, hit.columnTimestamp(),
                    false, false, false, true, submissionOrder, 0L,
                    hit.frame(), hit.usize()));
            return true;
        }
        dev.vox.lss.common.store.LodStoreService.StoreHit hit;
        try {
            hit = s.get(dimension, packed);
        } catch (Throwable t) {
            // get() is contained by contract; this belt exists because an escaped throw
            // here would strand the request in flight (leaked slot + orphaned dedup
            // group) — a miss is always the safe reading.
            s.diagnostics().recordError();
            hit = null;
        }
        if (hit == null) {
            s.diagnostics().recordMiss();
            return false;
        }
        if (hit.sectionBytes() == null) {
            // Contract violation (all-air must be byte[0], never null — a null here
            // would deliver as an authoritative miss and seed the miss memo falsely).
            // Contain as an errored miss; the NBT ladder serves the truth.
            s.diagnostics().recordError();
            s.diagnostics().recordMiss();
            return false;
        }
        boolean allAir = hit.sectionBytes().length == 0;
        byte[] bytes = allAir ? null : hit.sectionBytes();
        if (!allAir
                && hit.wirefmt() == dev.vox.lss.common.store.LodStoreService.WIREFMT_NATIVE_19) {
            // Pre-migration native-layout row on the raw rung — same translation
            // contract as the frame rung above.
            bytes = translateLegacyRaw(s, bytes);
            if (bytes == null) {
                return false;
            }
        }
        s.diagnostics().recordHit(System.nanoTime() - t0);
        int estimatedBytes = allAir ? 0
                : bytes.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ, bytes,
                dimension, estimatedBytes, hit.columnTimestamp(),
                false, false, false, true, submissionOrder, 0L));
        return true;
    }

    /** Decompress + translate a 19-row frame to v20; null = contained errored miss. */
    private byte[] translateLegacyStoreRow(dev.vox.lss.common.store.LodStoreService s,
                                           byte[] frame, int usize) {
        var codec = this.legacyRowCodec;
        if (codec == null) {
            codec = dev.vox.lss.common.store.StoreCodec.zstdOrNull();
            if (codec == null) {
                // No re-probe latch needed: a FrameHit cannot exist unless the store
                // opened, which required the natives — this arm is belt only.
                s.diagnostics().recordError();
                s.diagnostics().recordMiss();
                return null;
            }
            this.legacyRowCodec = codec;
        }
        try {
            return translateLegacyRawOrThrow(codec.decompress(frame, usize));
        } catch (Throwable t) {
            s.diagnostics().recordError();
            s.diagnostics().recordMiss();
            return null;
        }
    }

    /** Translate native raw bytes to v20; null = contained errored miss. */
    private byte[] translateLegacyRaw(dev.vox.lss.common.store.LodStoreService s,
                                      byte[] nativeRaw) {
        try {
            return translateLegacyRawOrThrow(nativeRaw);
        } catch (Throwable t) {
            s.diagnostics().recordError();
            s.diagnostics().recordMiss();
            return null;
        }
    }

    private byte[] translateLegacyRawOrThrow(byte[] nativeRaw) {
        var translator = this.storeLegacyTranslator;
        if (translator == null) {
            // Unwired translator (test rigs) + a 19-row: never leak native bytes into
            // the v20 pipeline — the errored miss is the safe reading.
            throw new IllegalStateException("no store legacy translator wired for a"
                    + " wirefmt=19 row");
        }
        return translator.apply(nativeRaw);
    }

    private void readAndDeliver(UUID playerUuid, int chunkX, int chunkZ, String dimension,
                                 long submissionOrder, ReadOperation operation) {
        if (isShutdown()) return;
        if (storeServedHit(playerUuid, chunkX, chunkZ, dimension, submissionOrder)) return;

        long startNs = System.nanoTime();
        // Freshness stamp at READ START (R1-M2): the bytes the read produces reflect
        // region state no earlier than this second, so any save landing during the read
        // or in the read→deposit gap has a header stamp >= it and the sweep drops the
        // row. Stamping later (completion/deposit-call) left those saves invisible.
        long srcStampSeconds = LSSConstants.epochSeconds();
        this.diag.recordSubmitted(); // the NBT path begins here — store hits never count

        byte[] serializedSections;
        try {
            serializedSections = operation.read();
        } catch (Throwable e) {
            // Failure shapes here arrive BOTH wrapped (fetch failures in
            // ExecutionException from future.get) and unwrapped (the B3 split's
            // pool-side parse throws raw). The triage deliberately branches on nothing
            // but the top-level TimeoutException — do NOT add an ExecutionException
            // unwrap, it would silently reclassify the split path (B3 review F9).
            if (e instanceof java.util.concurrent.TimeoutException) {
                // A read exceeding DISK_READ_TIMEOUT_SECONDS is a documented TRANSIENT on
                // slow IO under generation save pressure (miss-memo-design.md A/B finding):
                // it triages down the not-found ladder and self-heals via re-declaration.
                // One throttled line, no stack — a storm of these is diagnosable from
                // disk.errors, and per-chunk stack traces were pure console flooding.
                long releases = this.timeoutWarn.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (releases > 0) {
                    LSSLogger.warn("Disk read timed out (>" + LSSConstants.DISK_READ_TIMEOUT_SECONDS
                            + "s) at " + chunkX + ", " + chunkZ + " — triaged as not-found"
                            + " (counted disk.errors; self-heals by re-declaration on"
                            + " gen-enabled servers)"
                            + (releases > 1 ? " (+" + (releases - 1) + " more since last report)" : ""));
                }
            } else {
                LSSLogger.error("Failed to read chunk NBT from disk at " + chunkX + ", " + chunkZ, e);
            }
            this.diag.recordError();
            recordRealCompletion(System.nanoTime() - startNs);
            // Error/timeout TRIAGED as not-found (law A5's disk.errors fold) — says nothing
            // about existence, so it must never seed the miss memo.
            addResult(playerUuid, ChunkReadResult.notFoundFromError(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            // Deliberately NO Error re-throw (the pre-Phase-1 shape re-threw best-effort
            // into a FutureTask nobody inspects — provably unobservable): the last-resort
            // catch in the submit lambda would now see it and deliver a SECOND result,
            // breaking the one-result-per-submit envelope. Containment + delivery above
            // are complete; the pool thread survives either way.
            return;
        }

        if (serializedSections == null) {
            this.diag.recordNotFound();
            recordRealCompletion(System.nanoTime() - startNs);
            addResult(playerUuid, ChunkReadResult.notFoundAuthoritative(playerUuid, chunkX, chunkZ, dimension, submissionOrder));
            return;
        }

        long columnTimestamp = LSSConstants.epochSeconds();

        if (serializedSections.length == 0) {
            // Chunk exists on disk (FULL status) but is all air — resolve as found, not "not found"
            this.diag.recordAllAir();
            recordRealCompletion(System.nanoTime() - startNs);
            addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ,
                    null, dimension, 0, columnTimestamp, false, false, false, false,
                    submissionOrder, srcStampSeconds));
            return;
        }

        int estimatedBytes = serializedSections.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;

        this.diag.recordSuccess();
        recordRealCompletion(System.nanoTime() - startNs);
        addResult(playerUuid, new ChunkReadResult(playerUuid, chunkX, chunkZ,
                serializedSections, dimension, estimatedBytes, columnTimestamp,
                false, false, false, false, submissionOrder, srcStampSeconds));
    }

    public void registerPlayer(UUID playerUuid) {
        this.playerResults.computeIfAbsent(playerUuid, k -> new ConcurrentLinkedQueue<>());
    }

    private void addResult(UUID playerUuid, ChunkReadResult result) {
        var queue = this.playerResults.get(playerUuid);
        if (queue != null) {
            queue.add(result);
        }
    }

    public ConcurrentLinkedQueue<ChunkReadResult> getPlayerQueue(UUID playerUuid) {
        return this.playerResults.get(playerUuid);
    }

    public void removePlayerResults(UUID playerUuid) {
        this.playerResults.remove(playerUuid);
    }

    public String getDiagnostics() {
        String base = this.diag.formatDiagnostics(getPendingResultCount());
        // The throttle is engaged only on the Fabric A-incompatible fallback path (a chunk-IO mod
        // replaced vanilla IO). On the normal working-A path it is null and the line is unchanged,
        // so existing diagnostics goldens do not move; when engaged it makes the fallback observable
        // (the only end-to-end signal — no automated test can reach the C2ME path). limit/max shows
        // how far AIMD has backed LSS off under shared-IO load.
        var t = this.throttle;
        if (t == null) return base;
        return base + ", read_throttle=ENGAGED(" + t.currentLimit() + "/" + t.maxLimit() + ")";
    }

    /** Read results delivered but not yet drained by the processing thread, across all players. */
    public int getPendingResultCount() {
        int pending = 0;
        for (var queue : this.playerResults.values()) {
            pending += queue.size();
        }
        return pending;
    }

    public DiskReaderDiagnostics getDiag() { return this.diag; }

    public void shutdown() {
        this.isShutdown.set(true);
        this.executor.shutdownNow();
        try {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LSSLogger.warn("Disk reader threads did not terminate within 5 seconds");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        this.playerResults.clear();
    }
}
