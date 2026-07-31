package dev.vox.lss.common.store;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The SQLite LOD store tier (plan §1/§2): serves previously serialized wire-format
 * section bytes across restarts. DERIVED data — corruption, schema/wire/mask drift, and
 * codec changes are all "drop and rebuild", never migrate; deleting the DB (with -wal/
 * -shm) is always safe.
 *
 * <p><b>Schema (§2, Phase 0 decisions baked in):</b> WAL + synchronous=NORMAL (power
 * loss may lose recent commits, can never corrupt — fine for derived data; do not
 * "harden"), page_size 16384 (zero overflow chains at zstd-1 blob sizes), one ROWID
 * table per dimension ({@code lods_<dimId>}: {@code pos INTEGER PRIMARY KEY} IS the
 * rowid — true clustering, no secondary index), {@code dims}/{@code regions}/{@code
 * meta} side tables, ~64-row write transactions, mmap OFF (no measured win; SIGBUS on
 * IO error is uncatchable).
 *
 * <p><b>Threading:</b> {@code get()} runs on reader-pool threads over THREAD-CONFINED
 * read-only connections ({@code SQLITE_OPEN_READONLY} via query_only, {@code
 * wal_autocheckpoint=0} so readers never checkpoint); ALL writes (deposits, deletes,
 * sweep, meta) happen on the single batcher thread — WAL readers don't block the writer
 * and vice versa. The stale-hit window between a processing-thread invalidation and the
 * batcher's async row delete is closed by the same tombstone map the memory tier proved:
 * {@code invalidate()}/{@code delete()} stamp tombstones synchronously and {@code get()}
 * consults them first, so an invalidated position reads as a miss the moment the
 * invalidation returns ("effective before any subsequent get()"), with the DB delete
 * following on the batcher.
 *
 * <p><b>Freshness (§1 v2, per-column):</b> every row carries {@code src_stamp} — the
 * deposit-time epoch second, a CONSERVATIVE stand-in for the chunk's save time (any
 * region write AFTER the deposit has a header timestamp ≥ it). The startup sweep stats
 * every region file against {@code regions.seen_mtime} with {@code !=} (backup restores
 * move mtime BACKWARD), reads the changed files' 4 KiB header timestamp tables, and
 * DELETES rows whose header stamp ≥ their {@code src_stamp}; a VANISHED region file
 * drops all its rows (or the store would intercept the miss that regenerates
 * deliberately-deleted chunks). An unresolvable region directory drops the whole
 * dimension's rows (fail-safe: more NBT reads, never stale serves). The store serves
 * NOTHING until the sweep completes (misses during the boot window fall to the NBT
 * ladder). Known accepted cost: vanilla's metadata-only re-saves advance header stamps
 * without changing LOD content, so rows for chunks LOADED near shutdown are
 * conservatively dropped at the next startup — those are the probe-served positions the
 * store never serves anyway; the far disc keeps its rows.
 *
 * <p><b>Containment:</b> every entry point catches {@link Throwable}. Read-side failures
 * count {@code store.errors} and read as misses. Repeated writer-side failures latch the
 * store OFF one-way ({@code store=unavailable} in diag) with one warning. {@code
 * org.sqlite.tmpdir} is pointed at the store directory before the first connection
 * (noexec /tmp ships {@code UnsatisfiedLinkError} otherwise). A WAL watchdog issues
 * {@code wal_checkpoint(TRUNCATE)} above a size threshold (PASSIVE checkpoints cannot
 * reset the WAL under continuous readers) and feeds {@code store.wal_bytes}/{@code
 * db_bytes}/{@code checkpoint_ms_max}.
 */
public final class SqliteLodStore implements LodStoreService {

    static final int SCHEMA_VERSION = 1;
    private static final String DB_FILE = "store.db";
    private static final int PAGE_SIZE = 16384;
    private static final int WRITE_TXN_ROWS = 64;
    private static final int QUEUE_CAPACITY = 1024;
    private static final long WAL_CHECKPOINT_BYTES = 64L << 20; // TRUNCATE above 64 MB
    private static final long GAUGE_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Writer-side failures within one session before the one-way off latch. */
    private static final int WRITE_FAILURE_LATCH = 20;
    private static final byte[] EMPTY = new byte[0];

    /** Everything the platform must provide (kept as one bundle so tests can fake it). */
    // NOTE: no knownDimensions list — the sweep iterates the DIMS TABLE, so every
    // dimension the store holds rows for gets a freshness pass; a dim the resolver
    // cannot place fail-safe drops. A caller-frozen list exempted late-created worlds.
    public record Environment(Path storeDir, String mcVersion, int wireVersion,
                              Function<String, Path> regionDirResolver,
                              Function<String, String> maskFingerprintResolver,
                              int resweepSeconds) {}

    private sealed interface Op {
        record Deposit(String dim, long packed, byte[] bytes, long ts, long srcStampSeconds,
                       long enqueuedNanos) implements Op {}
        record DeleteRows(String dim, long[] positions) implements Op {}
        record Resweep() implements Op {}
        record BackfillMark(String dim, int rx, int rz) implements Op {}
    }

    private final LodStoreMode mode;
    private final LodStoreDiagnostics diag;
    private final StoreCodec codec;
    private final Environment env;
    private final Path dbPath;

    private final ArrayBlockingQueue<Op> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    // Deletes and resweeps ride a separate UNBOUNDED queue drained before deposits: a row
    // delete must never be shed (its tombstone expires after TOMBSTONE_TTL_NANOS — losing
    // the delete would resurrect a stale row), and mixing them into the bounded deposit
    // queue forced shed-ordering contortions (and a livelock when the queue was all
    // deletes). Unbounded is safe here: volume is edit-rate-bounded, and a mass
    // invalidation arrives as ONE DeleteRows op carrying the whole position array.
    private final java.util.concurrent.ConcurrentLinkedQueue<Op> controlQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Thread batcher;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    // One-way containment latch: after repeated writer failures the store stops serving
    // and stops accepting work (diag renders store=unavailable via the null-store path).
    private volatile boolean latchedOff;
    private final AtomicBoolean latchWarned = new AtomicBoolean();
    private final AtomicBoolean readErrorWarned = new AtomicBoolean();
    private int writerFailures;

    // Serving gate: false until the startup sweep completes — a not-yet-swept stale row
    // must never hit (misses during boot fall to the NBT ladder, fail-safe).
    private volatile boolean serving;
    private final CountDownLatch sweepDone = new CountDownLatch(1);

    // Sweep-drop fan-out: a test/observability seam (fed alongside the store.sweep_drops
    // counter). Its production consumer was the deleted memory front tier; kept because
    // any future front cache MUST register here or resweep culls won't reach it.
    private volatile java.util.function.BiConsumer<String, long[]> sweepDropListener;

    // The tombstone map (the memory tier's proven protocol, §threading above).
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> tombstones =
            new ConcurrentHashMap<>();
    private static final long TOMBSTONE_TTL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private long lastTombstoneSweepNanos = System.nanoTime();

    // Batcher-thread state
    private Connection writer;
    private final Map<String, Integer> dimIds = new HashMap<>();
    private final Map<String, PreparedStatement> insertByDim = new HashMap<>();
    private int txnRows;
    private long lastGaugeRefreshNanos;
    private long nextResweepNanos;

    // Reader connections, thread-confined (reader-pool threads via ThreadLocal). Tracked
    // for shutdown closing. Readers only ever see dimensions that exist at their first
    // use; a dim table created later is picked up by the per-call dim-id lookup.
    private final ThreadLocal<Connection> readerConn = new ThreadLocal<>();
    private final List<Connection> allReaderConns = new ArrayList<>();
    private final ConcurrentHashMap<String, Integer> dimIdsShared = new ConcurrentHashMap<>();

    /** Null when the codec native or the SQLite native/DB cannot serve this platform —
     *  the caller warns once and runs without the disk tier (degrade, never crash). */
    public static SqliteLodStore createOrNull(LodStoreMode mode, Environment env,
                                              LodStoreDiagnostics diag) {
        try {
            StoreCodec codec = StoreCodec.zstdOrNull();
            if (codec == null) return null;
            return new SqliteLodStore(mode, codec, env, diag);
        } catch (Throwable t) {
            LSSLogger.warn("LOD store: SQLite engine unavailable — running without the"
                    + " disk store", t);
            return null;
        }
    }

    private SqliteLodStore(LodStoreMode mode, StoreCodec codec, Environment env,
                           LodStoreDiagnostics diag) throws Exception {
        this.mode = mode;
        this.codec = codec;
        this.env = env;
        this.diag = diag;
        Files.createDirectories(env.storeDir());
        // noexec /tmp: sqlite-jdbc extracts its native lib to org.sqlite.tmpdir; the
        // world folder is always writable+executable for the server.
        if (System.getProperty("org.sqlite.tmpdir") == null) {
            System.setProperty("org.sqlite.tmpdir", env.storeDir().toString());
        }
        this.dbPath = env.storeDir().resolve(DB_FILE);
        // Open + validate meta on the CALLER thread (service construction): a mismatch
        // or corruption drops the DB and recreates it fresh — before the batcher exists.
        openOrRecreateWriter();
        this.batcher = new Thread(this::batcherLoop, Brand.shortName() + " LOD Store SQLite");
        this.batcher.setDaemon(true);
        this.batcher.setPriority(Thread.MIN_PRIORITY + 1);
        this.batcher.start();
    }

    // ---- lifecycle / meta ----

    private void openOrRecreateWriter() throws Exception {
        try {
            openWriter();
            if (!metaMatches()) {
                LSSLogger.info("LOD store: schema/wire/version drift — dropping and"
                        + " rebuilding the store (derived data, never migrated)");
                closeWriter();
                deleteDbFiles();
                openWriter();
                writeMeta();
            }
        } catch (Exception first) {
            // Any failure here (corrupt DB, bad page) → drop and rebuild once.
            LSSLogger.warn("LOD store: could not open the existing store — dropping and"
                    + " rebuilding (derived data)", first);
            closeWriter();
            deleteDbFiles();
            openWriter();
            writeMeta();
        }
    }

    private void openWriter() throws SQLException {
        var ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + this.dbPath);
        this.writer = ds.getConnection();
        try (Statement st = this.writer.createStatement()) {
            st.execute("PRAGMA page_size=" + PAGE_SIZE);
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS dims (id INTEGER PRIMARY KEY,"
                    + " name TEXT UNIQUE, mask_fingerprint TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS regions (dim INTEGER, rpos INTEGER,"
                    + " seen_mtime INTEGER, PRIMARY KEY (dim, rpos)) WITHOUT ROWID");
            st.execute("CREATE TABLE IF NOT EXISTS backfill (dim TEXT, rx INTEGER,"
                    + " rz INTEGER, done INTEGER, PRIMARY KEY (dim, rx, rz)) WITHOUT ROWID");
        }
        this.writer.setAutoCommit(false);
        this.writer.commit();
        loadDims();
        boolean hasMeta;
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM meta")) {
            rs.next();
            hasMeta = rs.getLong(1) > 0;
        }
        if (!hasMeta) writeMeta();
        this.writer.commit();
    }

    private boolean metaMatches() throws SQLException {
        Map<String, String> meta = new HashMap<>();
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT k, v FROM meta")) {
            while (rs.next()) meta.put(rs.getString(1), rs.getString(2));
        }
        return String.valueOf(SCHEMA_VERSION).equals(meta.get("schema_version"))
                && String.valueOf(this.env.wireVersion()).equals(meta.get("wire_format_version"))
                && this.env.mcVersion().equals(meta.get("mc_version"))
                && StoreCodec.NAME.equals(meta.get("codec"));
    }

    private void writeMeta() throws SQLException {
        try (PreparedStatement ps = this.writer.prepareStatement(
                "INSERT OR REPLACE INTO meta (k, v) VALUES (?,?)")) {
            for (var e : Map.of(
                    "schema_version", String.valueOf(SCHEMA_VERSION),
                    "wire_format_version", String.valueOf(this.env.wireVersion()),
                    "mc_version", this.env.mcVersion(),
                    "codec", StoreCodec.NAME).entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.executeUpdate();
            }
        }
        this.writer.commit();
    }

    private void loadDims() throws SQLException {
        this.dimIds.clear();
        // Clear the reader-side map too: after a drop-and-rebuild a stale entry would
        // point get() at a dropped lods_<id> table (a spurious store.errors per read).
        this.dimIdsShared.clear();
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM dims")) {
            while (rs.next()) {
                this.dimIds.put(rs.getString(2), rs.getInt(1));
                this.dimIdsShared.put(rs.getString(2), rs.getInt(1));
            }
        }
    }

    private void deleteDbFiles() {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            try {
                Files.deleteIfExists(this.dbPath.resolveSibling(DB_FILE + suffix));
            } catch (Exception ignored) {
            }
        }
    }

    private void closeWriter() {
        for (var ps : this.insertByDim.values()) {
            try { ps.close(); } catch (Exception ignored) { }
        }
        this.insertByDim.clear();
        if (this.writer != null) {
            try { this.writer.close(); } catch (Exception ignored) { }
            this.writer = null;
        }
    }

    // ---- LodStoreService ----

    @Override
    public LodStoreMode mode() {
        return this.mode;
    }

    @Override
    public StoreHit get(String dimension, long packed) {
        if (!this.serving || this.latchedOff) return null;
        var tombs = this.tombstones.get(dimension);
        if (tombs != null && tombs.containsKey(packed)) return null;
        Integer dimId = this.dimIdsShared.get(dimension);
        if (dimId == null) return null;
        try {
            Connection c = readerConnection();
            if (c == null) return null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts, chash, usize, blob FROM lods_" + dimId + " WHERE pos=?")) {
                ps.setLong(1, packed);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    long ts = rs.getLong(1);
                    long chash = rs.getLong(2);
                    int usize = rs.getInt(3);
                    if (usize == 0) return new StoreHit(EMPTY, ts);
                    byte[] blob = rs.getBytes(4);
                    byte[] raw = this.codec.decompress(blob, usize);
                    if (raw.length != usize || fnv1a(raw) != chash) {
                        throw new IllegalStateException("row integrity failure at " + packed
                                + " (usize/chash mismatch)");
                    }
                    return new StoreHit(raw, ts);
                }
            }
        } catch (Throwable t) {
            this.diag.recordError();
            // Delete the poisoned row (batcher op) so a repeatable failure can't re-fail
            // every re-declaration; the NBT ladder serves the truth and re-deposits.
            enqueueControl(new Op.DeleteRows(dimension, new long[]{packed}));
            if (this.readErrorWarned.compareAndSet(false, true)) {
                LSSLogger.warn("LOD store read failed — served from disk instead (counted"
                        + " store.errors; further failures are silent)", t);
            }
            return null;
        }
    }

    /** Thread-confined read-only connection (created lazily per reader-pool thread). */
    private Connection readerConnection() {
        Connection c = this.readerConn.get();
        if (c != null) return c;
        if (this.shutdown.get()) return null; // closing conns; a new one would leak
        try {
            var ds = new org.sqlite.SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + this.dbPath);
            c = ds.getConnection();
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA query_only=1");
                st.execute("PRAGMA wal_autocheckpoint=0");
            }
            this.readerConn.set(c);
            synchronized (this.allReaderConns) {
                this.allReaderConns.add(c);
            }
            return c;
        } catch (Throwable t) {
            this.diag.recordError();
            return null;
        }
    }

    @Override
    public void deposit(String dimension, long packed, byte[] sectionBytes, long columnTimestamp) {
        if (this.shutdown.get() || this.latchedOff) return;
        byte[] normalized = sectionBytes == null || sectionBytes.length == 0 ? EMPTY : sectionBytes;
        // src_stamp is the DEPOSIT-call wall second, NOT the column timestamp: a
        // disk-sourced column's ts IS its region header stamp, so stamping ts would make
        // the sweep's `header >= src_stamp` fire on EQUAL stamps and drop every
        // untouched row in any region whose mtime moved. Deposit wall time is strictly
        // later than the bytes' save time, so an untouched chunk keeps its row and any
        // save at-or-after the deposit still drops it (>= covers the same-second case).
        long srcStampSeconds = System.currentTimeMillis() / 1000L;
        // Shed policy: deposits are droppable (the NBT ladder re-deposits on the next
        // serve); the oldest DEPOSIT in the queue is shed to admit the newest.
        Op.Deposit op = new Op.Deposit(dimension, packed, normalized, columnTimestamp,
                srcStampSeconds, System.nanoTime());
        while (!this.queue.offer(op)) {
            if (this.shutdown.get() || this.latchedOff) return;
            if (this.queue.poll() != null) this.diag.recordDepositDrop();
        }
        this.diag.setQueueDepth(queueDepth());
    }

    @Override
    public void invalidate(String dimension, long[] positions) {
        long now = System.nanoTime();
        var tombs = this.tombstones.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());
        for (long packed : positions) {
            tombs.put(packed, now);
        }
        enqueueControl(new Op.DeleteRows(dimension, positions.clone()));
    }

    @Override
    public void delete(String dimension, long packed) {
        this.tombstones.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                .put(packed, System.nanoTime());
        enqueueControl(new Op.DeleteRows(dimension, new long[]{packed}));
    }

    /** Deletes/resweeps: unbounded, never shed (see {@link #controlQueue}). */
    private void enqueueControl(Op op) {
        if (this.shutdown.get() || this.latchedOff) return;
        this.controlQueue.add(op);
        this.diag.setQueueDepth(queueDepth());
    }

    private int queueDepth() {
        return this.queue.size() + this.controlQueue.size();
    }

    @Override
    public LodStoreDiagnostics diagnostics() {
        return this.diag;
    }

    /** Store health for long-running background users (the backfill driver): serving
     *  (startup sweep done) and not latched off. A latched store silently no-ops every
     *  write — a driver that keeps walking would burn IO for nothing and claim
     *  progress (review MAJOR). */
    public boolean isHealthy() {
        return this.serving && !this.latchedOff;
    }

    /** Row-existence check WITHOUT the blob fetch + decompress + integrity hash a full
     *  get() pays — the backfill's skip rung (review finding: a warm region walk was
     *  1024 back-to-back full-row reads). Tombstones honored like get(). */
    public boolean hasRow(String dimension, long packed) {
        if (!this.serving || this.latchedOff) return false;
        var tombs = this.tombstones.get(dimension);
        if (tombs != null && tombs.containsKey(packed)) return false;
        Integer dimId = this.dimIdsShared.get(dimension);
        if (dimId == null) return false;
        try {
            Connection c = readerConnection();
            if (c == null) return false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM lods_" + dimId + " WHERE pos=?")) {
                ps.setLong(1, packed);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Throwable t) {
            this.diag.recordError();
            return false;
        }
    }

    /** Backfill progress (Phase 4): mark a region fully processed (batcher-written,
     *  dropped with the DB — derived data). */
    public void markBackfillRegionDone(String dimension, int rx, int rz) {
        enqueueControl(new Op.BackfillMark(dimension, rx, rz));
    }

    /** Whether a region was already backfilled (backfill-thread read connection). */
    public boolean isBackfillRegionDone(String dimension, int rx, int rz) {
        if (this.latchedOff) return true; // latched store: do no work
        try {
            Connection c = readerConnection();
            if (c == null) return false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT done FROM backfill WHERE dim=? AND rx=? AND rz=?")) {
                ps.setString(1, dimension);
                ps.setInt(2, rx);
                ps.setInt(3, rz);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) != 0;
                }
            }
        } catch (Throwable t) {
            this.diag.recordError();
            return false;
        }
    }

    /** Blocks until the startup sweep finishes (test/harness seam). */
    public boolean awaitSweep(long timeoutMs) throws InterruptedException {
        return this.sweepDone.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Registers the sweep-drop fan-out (called on the batcher thread with each swept
     *  dimension's dropped positions; the tiered composition points this at the memory
     *  tier's invalidate). Set before serving starts. */
    public void setSweepDropListener(java.util.function.BiConsumer<String, long[]> listener) {
        this.sweepDropListener = listener;
    }

    private void notifySweepDrops(String dimension, List<Long> positions) {
        if (positions.isEmpty()) return;
        this.diag.recordSweepDrops(positions.size());
        var listener = this.sweepDropListener;
        if (listener == null) return;
        long[] packed = new long[positions.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = positions.get(i);
        try {
            listener.accept(dimension, packed);
        } catch (Throwable t) {
            this.diag.recordError();
        }
    }

    @Override
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) return;
        this.serving = false; // a post-shutdown get() must miss, not race closing conns
        this.batcher.interrupt();
        try {
            this.batcher.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (this.allReaderConns) {
            for (var c : this.allReaderConns) {
                try { c.close(); } catch (Exception ignored) { }
            }
            this.allReaderConns.clear();
        }
        // The batcher exited (or is wedged — then skip: single-writer discipline).
        if (!this.batcher.isAlive()) {
            try {
                if (this.writer != null) {
                    // Deliberately NO mtime snapshot here: seen_mtime is only ever
                    // written next to an actual header examination (see sweepDimension)
                    // — a shutdown-time stat would mark unexamined regions as seen and
                    // let offline edits skip every future sweep.
                    this.writer.commit();
                    checkpointTruncate();
                }
            } catch (Throwable ignored) {
            }
            closeWriter();
        }
        this.diag.setQueueDepth(0);
    }

    // ---- batcher thread ----

    private void batcherLoop() {
        try {
            startupSweep();
        } catch (InterruptedException e) {
            // Shutdown mid-sweep: serving simply never turns on — not a failure.
        } catch (Throwable t) {
            latchOff("startup sweep failed", t);
        } finally {
            this.sweepDone.countDown();
        }
        this.nextResweepNanos = this.env.resweepSeconds() > 0
                ? System.nanoTime() + TimeUnit.SECONDS.toNanos(this.env.resweepSeconds())
                : Long.MAX_VALUE;
        while (!this.shutdown.get() && !this.latchedOff) {
            Op op = this.controlQueue.poll(); // deletes first: they must never wait behind deposits
            if (op == null) {
                try {
                    op = this.queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            long now = System.nanoTime();
            try {
                if (op == null) {
                    commitTxn(); // idle flush: never hold a sub-batch across the snapshot cadence
                    sweepTombstones(now);
                } else {
                    apply(op);
                }
                if (now >= this.nextResweepNanos) {
                    this.nextResweepNanos = now + TimeUnit.SECONDS.toNanos(this.env.resweepSeconds());
                    commitTxn();
                    runSweep(false);
                }
                maybeRefreshGauges(now);
                // Reset the failure streak only when an op actually applied: idle
                // iterations are vacuous no-ops and must not launder a broken writer
                // below the latch under sparse traffic (review finding).
                if (op != null) this.writerFailures = 0;
            } catch (Throwable t) {
                this.diag.recordError();
                try { this.writer.rollback(); this.txnRows = 0; } catch (Throwable ignored) { }
                // A DELETE must never be lost to a transient failure: its tombstone
                // expires after TOMBSTONE_TTL_NANOS and the row would resurrect.
                // Re-queue it — retries are bounded by the failure latch below, and
                // latchOff() stops serving, so a permanently broken writer can not
                // serve the undeleted row either.
                if (op instanceof Op.DeleteRows) this.controlQueue.add(op);
                if (++this.writerFailures >= WRITE_FAILURE_LATCH) {
                    latchOff("repeated write failures", t);
                }
            }
            this.diag.setQueueDepth(queueDepth());
        }
        // Graceful exit: flush queued deletes (never shed — see controlQueue), then the
        // txn. Containment is PER OP: one failing delete must not abandon the rest
        // (the boot sweep is the cross-restart backstop for whatever still fails —
        // an invalidated column's later region save advances its header stamp).
        Op op;
        while ((op = this.controlQueue.poll()) != null) {
            if (op instanceof Op.DeleteRows) {
                try {
                    apply(op);
                } catch (Throwable ignored) {
                    try { this.writer.rollback(); this.txnRows = 0; } catch (Throwable ignored2) { }
                }
            }
        }
        try { commitTxn(); } catch (Throwable ignored) { }
    }

    private void latchOff(String why, Throwable t) {
        this.latchedOff = true;
        this.serving = false;
        if (this.latchWarned.compareAndSet(false, true)) {
            LSSLogger.warn("LOD store disabled for this session (" + why + ") — serving"
                    + " continues via the normal disk path; the store rebuilds next start"
                    + " (derived data)", t);
        }
        this.queue.clear();
        this.diag.setQueueDepth(0);
    }

    private void apply(Op op) throws Exception {
        switch (op) {
            case Op.Deposit dep -> applyDeposit(dep);
            case Op.DeleteRows del -> {
                Integer dimId = this.dimIds.get(del.dim());
                if (dimId != null) {
                    try (PreparedStatement ps = this.writer.prepareStatement(
                            "DELETE FROM lods_" + dimId + " WHERE pos=?")) {
                        for (long p : del.positions()) {
                            ps.setLong(1, p);
                            ps.executeUpdate();
                        }
                    }
                    bumpTxn(del.positions().length);
                }
            }
            case Op.Resweep ignored -> {
                commitTxn();
                runSweep(false);
            }
            case Op.BackfillMark mark -> {
                try (PreparedStatement ps = this.writer.prepareStatement(
                        "INSERT OR REPLACE INTO backfill (dim, rx, rz, done) VALUES (?,?,?,1)")) {
                    ps.setString(1, mark.dim());
                    ps.setInt(2, mark.rx());
                    ps.setInt(3, mark.rz());
                    ps.executeUpdate();
                }
                bumpTxn(1);
            }
        }
    }

    private void applyDeposit(Op.Deposit dep) throws Exception {
        var tombs = this.tombstones.get(dep.dim());
        if (tombs != null) {
            Long t = tombs.get(dep.packed());
            if (t != null && t >= dep.enqueuedNanos()) {
                this.diag.recordDepositSkip();
                return;
            }
        }
        int dimId = dimIdFor(dep.dim());
        byte[] blob = dep.bytes().length == 0 ? EMPTY : this.codec.compress(dep.bytes());
        // Latest-wins by STORED ts in ONE statement: the conditional upsert replaces the
        // old SELECT-then-INSERT pair (the cold-path gate measured per-deposit cost —
        // statement overhead matters at backfill rates). 0 rows changed = the WHERE
        // rejected an older deposit; compressing the rare loser first is cheaper than a
        // SELECT on every winner.
        PreparedStatement insert = this.insertByDim.get(dep.dim());
        if (insert == null) {
            insert = this.writer.prepareStatement("INSERT INTO lods_" + dimId
                    + " (pos, ts, chash, usize, src_stamp, blob) VALUES (?,?,?,?,?,?)"
                    + " ON CONFLICT(pos) DO UPDATE SET ts=excluded.ts,"
                    + " chash=excluded.chash, usize=excluded.usize,"
                    + " src_stamp=excluded.src_stamp, blob=excluded.blob"
                    + " WHERE excluded.ts >= ts");
            this.insertByDim.put(dep.dim(), insert);
        }
        insert.setLong(1, dep.packed());
        insert.setLong(2, dep.ts());
        insert.setLong(3, fnv1a(dep.bytes()));
        insert.setInt(4, dep.bytes().length);
        // src_stamp: the deposit-CALL wall second (never the column ts — see deposit()).
        insert.setLong(5, dep.srcStampSeconds());
        insert.setBytes(6, blob);
        if (insert.executeUpdate() > 0) {
            this.diag.recordDeposit();
        } else {
            this.diag.recordDepositSkip(); // lost latest-wins to a newer-stamped row
        }
        bumpTxn(1);
        // Tombstone re-check after the write (the memory tier's proven interleaving
        // guard): an invalidate between the first check and the write must win.
        tombs = this.tombstones.get(dep.dim());
        if (tombs != null) {
            Long t = tombs.get(dep.packed());
            if (t != null && t >= dep.enqueuedNanos()) {
                try (PreparedStatement ps = this.writer.prepareStatement(
                        "DELETE FROM lods_" + dimId + " WHERE pos=?")) {
                    ps.setLong(1, dep.packed());
                    ps.executeUpdate();
                }
                bumpTxn(1);
            }
        }
    }

    private int dimIdFor(String dimension) throws SQLException {
        Integer id = this.dimIds.get(dimension);
        if (id != null) return id;
        int next = this.dimIds.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        try (PreparedStatement ps = this.writer.prepareStatement(
                "INSERT INTO dims (id, name, mask_fingerprint) VALUES (?,?,?)")) {
            ps.setInt(1, next);
            ps.setString(2, dimension);
            ps.setString(3, currentMaskFingerprint(dimension));
            ps.executeUpdate();
        }
        try (Statement st = this.writer.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS lods_" + next + " ("
                    + "pos INTEGER PRIMARY KEY, ts INTEGER NOT NULL, chash INTEGER NOT NULL,"
                    + " usize INTEGER NOT NULL, src_stamp INTEGER NOT NULL, blob BLOB NOT NULL)");
        }
        this.writer.commit();
        this.dimIds.put(dimension, next);
        this.dimIdsShared.put(dimension, next);
        return next;
    }

    private String currentMaskFingerprint(String dimension) {
        try {
            String fp = this.env.maskFingerprintResolver().apply(dimension);
            return fp == null ? "" : fp;
        } catch (Throwable t) {
            return "";
        }
    }

    private void bumpTxn(int rows) throws SQLException {
        this.txnRows += rows;
        if (this.txnRows >= WRITE_TXN_ROWS) {
            commitTxn();
        }
    }

    private void commitTxn() throws SQLException {
        if (this.txnRows > 0) {
            this.writer.commit();
            this.txnRows = 0;
        }
    }

    private void sweepTombstones(long nowNanos) {
        if (nowNanos - this.lastTombstoneSweepNanos < TOMBSTONE_TTL_NANOS) return;
        this.lastTombstoneSweepNanos = nowNanos;
        for (var tombs : this.tombstones.values()) {
            tombs.values().removeIf(stamp -> nowNanos - stamp > TOMBSTONE_TTL_NANOS);
        }
    }

    private void maybeRefreshGauges(long nowNanos) {
        if (nowNanos - this.lastGaugeRefreshNanos < GAUGE_REFRESH_NANOS) return;
        this.lastGaugeRefreshNanos = nowNanos;
        try {
            this.diag.setDbBytes(Files.exists(this.dbPath) ? Files.size(this.dbPath) : 0);
            Path wal = this.dbPath.resolveSibling(DB_FILE + "-wal");
            long walBytes = Files.exists(wal) ? Files.size(wal) : 0;
            this.diag.setWalBytes(walBytes);
            if (walBytes > WAL_CHECKPOINT_BYTES) {
                commitTxn();
                checkpointTruncate();
                this.diag.setWalBytes(Files.exists(wal) ? Files.size(wal) : 0);
            }
        } catch (Throwable ignored) {
            // gauge refresh is best-effort; a failed checkpoint retries next interval
        }
    }

    private void checkpointTruncate() throws SQLException {
        long t0 = System.nanoTime();
        try (Statement st = this.writer.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        this.writer.commit();
        this.diag.recordCheckpointMs((System.nanoTime() - t0) / 1_000_000);
    }

    // ---- freshness sweep ----

    private void startupSweep() throws Exception {
        runSweep(true);
        this.serving = true;
    }

    /**
     * The mtime/header freshness pass (startup, and Paper's periodic re-sweep). For each
     * known dimension: resolve the region dir (unresolvable → drop the whole dimension's
     * rows, fail-safe); stat each region file and compare {@code !=} against
     * {@code regions.seen_mtime}; a changed region gets one header read and per-column
     * {@code src_stamp} comparison (header stamp ≥ src_stamp → row deleted); a region
     * with rows but NO file on disk drops all its rows.
     */
    private void runSweep(boolean startup) throws Exception {
        // Iterate the DIMS TABLE, not a caller-provided list: any dimension the store
        // has rows for MUST get a freshness pass — a dim deposited at runtime (created
        // world) but absent from the resolver map resolves null below and fail-safe
        // drops, never "served but unswept" (review finding: a knownDimensions-frozen
        // loop silently exempted such dims from every freshness rule).
        for (var dimEntry : List.copyOf(this.dimIds.entrySet())) {
            if (this.shutdown.get()) {
                // Shutdown-abort: leave the sweep INCOMPLETE rather than let the
                // interrupt turn header reads into ClosedByInterruptException nulls
                // (which read as unreadable regions and silently drop warm rows).
                throw new InterruptedException("shutdown during sweep");
            }
            String dimension = dimEntry.getKey();
            int dimId = dimEntry.getValue();
            Path regionDir;
            try {
                regionDir = this.env.regionDirResolver().apply(dimension);
            } catch (Throwable t) {
                regionDir = null;
            }
            if (regionDir == null || !Files.isDirectory(regionDir)) {
                List<Long> dropped = dropDimensionRows(dimId);
                notifySweepDrops(dimension, dropped);
                if (!dropped.isEmpty()) {
                    LSSLogger.warn("LOD store: region directory for " + dimension
                            + " is unresolvable — dropped its " + dropped.size()
                            + " stored rows (fail-safe)");
                }
                continue;
            }
            // Mask fingerprint (per-dimension, §1): drift → drop the dimension's rows.
            String fp = currentMaskFingerprint(dimension);
            String storedFp = storedMaskFingerprint(dimId);
            if (!fp.equals(storedFp)) {
                List<Long> dropped = dropDimensionRows(dimId);
                notifySweepDrops(dimension, dropped);
                try (PreparedStatement ps = this.writer.prepareStatement(
                        "UPDATE dims SET mask_fingerprint=? WHERE id=?")) {
                    ps.setString(1, fp);
                    ps.setInt(2, dimId);
                    ps.executeUpdate();
                }
                this.writer.commit();
                if (!dropped.isEmpty()) {
                    LSSLogger.info("LOD store: x-ray mask changed for " + dimension
                            + " — dropped " + dropped.size() + " rows (rebuilds from serves)");
                }
                continue; // fresh slate; mtimes recorded below next pass
            }
            sweepDimension(dimension, dimId, regionDir);
        }
        this.writer.commit();
    }

    private String storedMaskFingerprint(int dimId) throws SQLException {
        try (PreparedStatement ps = this.writer.prepareStatement(
                "SELECT mask_fingerprint FROM dims WHERE id=?")) {
            ps.setInt(1, dimId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
            }
        }
    }

    private void sweepDimension(String dimension, int dimId, Path regionDir) throws Exception {
        // Regions that HAVE rows: derive from the stored positions (region of pos).
        Map<Long, List<Long>> rowsByRegion = new HashMap<>();
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT pos, src_stamp FROM lods_" + dimId)) {
            while (rs.next()) {
                long pos = rs.getLong(1);
                long rpos = regionOf(pos);
                rowsByRegion.computeIfAbsent(rpos, k -> new ArrayList<>()).add(pos);
            }
        }
        Map<Long, Long> seenMtimes = new HashMap<>();
        try (PreparedStatement ps = this.writer.prepareStatement(
                "SELECT rpos, seen_mtime FROM regions WHERE dim=?")) {
            ps.setInt(1, dimId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) seenMtimes.put(rs.getLong(1), rs.getLong(2));
            }
        }
        List<Long> droppedPositions = new ArrayList<>();
        int droppedVanished = 0, droppedStale = 0, checkedRegions = 0;
        for (var entry : rowsByRegion.entrySet()) {
            if (this.shutdown.get()) throw new InterruptedException("shutdown during sweep");
            long rpos = entry.getKey();
            int rx = (int) (rpos >> 32);
            int rz = (int) rpos;
            Path mca = regionDir.resolve("r." + rx + "." + rz + ".mca");
            if (!Files.exists(mca)) {
                droppedVanished += deleteRows(dimId, entry.getValue());
                droppedPositions.addAll(entry.getValue());
                continue;
            }
            // Capture the mtime BEFORE the header read, and record THAT value as
            // seen_mtime only after this region's rows were actually judged against
            // its header. Recording a fresh stat (the old shutdown-time bulk
            // recordRegionMtimes) marked regions "seen" whose headers were NEVER
            // examined — a save landing after the last examination was then skipped
            // by the != compare forever, turning Paper's "save + one sweep" staleness
            // bound into unbounded cross-restart staleness (review MAJOR).
            long mtime = Files.getLastModifiedTime(mca).toMillis();
            Long seen = seenMtimes.get(rpos);
            if (seen != null && seen == mtime) continue; // unchanged since last sweep
            checkedRegions++;
            int[] headerStamps = readHeaderTimestamps(mca);
            if (headerStamps == null) {
                if (this.shutdown.get()) throw new InterruptedException("shutdown during sweep");
                // Unreadable header: fail-safe, drop the region's rows.
                droppedVanished += deleteRows(dimId, entry.getValue());
                droppedPositions.addAll(entry.getValue());
                continue;
            }
            List<Long> stale = new ArrayList<>();
            try (PreparedStatement ps = this.writer.prepareStatement(
                    "SELECT src_stamp FROM lods_" + dimId + " WHERE pos=?")) {
                for (long pos : entry.getValue()) {
                    int cx = PositionUtil.unpackX(pos);
                    int cz = PositionUtil.unpackZ(pos);
                    int idx = (cx & 31) + ((cz & 31) << 5);
                    ps.setLong(1, pos);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) continue;
                        long srcStamp = rs.getLong(1);
                        // >= : a save in the SAME second as the deposit may postdate it
                        // (1 s granularity) — conservative drop, never a stale serve.
                        if (headerStamps[idx] >= srcStamp) {
                            stale.add(pos);
                        }
                    }
                }
            }
            droppedStale += deleteRows(dimId, stale);
            droppedPositions.addAll(stale);
            // This region's rows are now judged against the header we read — record
            // the PRE-read mtime (a save racing the read leaves mtime > recorded, so
            // the next sweep re-checks; never the reverse).
            try (PreparedStatement ps = this.writer.prepareStatement(
                    "INSERT OR REPLACE INTO regions (dim, rpos, seen_mtime) VALUES (?,?,?)")) {
                ps.setInt(1, dimId);
                ps.setLong(2, rpos);
                ps.setLong(3, mtime);
                ps.executeUpdate();
            }
        }
        notifySweepDrops(dimension, droppedPositions);
        if (droppedVanished + droppedStale > 0 || checkedRegions > 0) {
            LSSLogger.info("LOD store sweep [" + dimension + "]: " + checkedRegions
                    + " changed region(s), dropped " + droppedStale + " stale + "
                    + droppedVanished + " vanished-region row(s)");
        }
        this.writer.commit();
    }

    private int[] readHeaderTimestamps(Path mca) {
        try (var ch = java.nio.channels.FileChannel.open(mca)) {
            var buf = java.nio.ByteBuffer.allocate(8192);
            int read = 0;
            while (read < 8192) {
                int n = ch.read(buf, read);
                if (n < 0) return null;
                read += n;
            }
            buf.flip();
            int[] stamps = new int[1024];
            for (int i = 0; i < 1024; i++) {
                int loc = buf.getInt(i * 4);
                // loc == 0: the chunk is ABSENT from a still-present region file — it was
                // deleted (region tools) or never saved. Fail-safe like the vanished-region
                // rule, at chunk granularity: MAX_VALUE makes the >= compare drop the row,
                // so the store never intercepts the miss that regenerates a deleted chunk.
                stamps[i] = loc == 0 ? Integer.MAX_VALUE : buf.getInt(4096 + i * 4);
            }
            return stamps;
        } catch (Exception e) {
            return null;
        }
    }

    private int deleteRows(int dimId, List<Long> positions) throws SQLException {
        if (positions.isEmpty()) return 0;
        try (PreparedStatement ps = this.writer.prepareStatement(
                "DELETE FROM lods_" + dimId + " WHERE pos=?")) {
            for (long pos : positions) {
                ps.setLong(1, pos);
                ps.executeUpdate();
            }
        }
        this.writer.commit();
        return positions.size();
    }

    /** Drops every row of a dimension, returning the dropped positions (the sweep-drop
     *  fan-out needs them — the memory tier must evict its copies too). */
    private List<Long> dropDimensionRows(int dimId) throws SQLException {
        List<Long> positions = new ArrayList<>();
        try (Statement st = this.writer.createStatement();
             ResultSet rs = st.executeQuery("SELECT pos FROM lods_" + dimId)) {
            while (rs.next()) positions.add(rs.getLong(1));
        }
        try (Statement st = this.writer.createStatement()) {
            st.executeUpdate("DELETE FROM lods_" + dimId);
            this.writer.commit();
        }
        return positions;
    }

    private static long regionOf(long packedPos) {
        int cx = PositionUtil.unpackX(packedPos);
        int cz = PositionUtil.unpackZ(packedPos);
        return ((long) (cx >> 5) << 32) | ((cz >> 5) & 0xFFFFFFFFL);
    }

    private static long fnv1a(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
