package dev.vox.lss.networking.client;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Opt-in client-side event trace for diagnosing request-loop behavior live (scan order,
 * movement recentering, receive order). Toggled by {@code /<brand> trace} ({@code /lss} or
 * {@code /vss}); writes compact JSONL to {@code logs/<brand>-trace-<timestamp>.jsonl} in the
 * game directory (the filename prefix follows {@link dev.vox.lss.common.Brand}). Disabled it
 * costs one volatile read per hook — every formatting happens behind the gate.
 *
 * <p><b>Threading:</b> mostly the main client thread, but NOT exclusively —
 * {@code col_light} is emitted from {@code ClientColumnProcessor}'s drain thread. The
 * {@code synchronized} on {@link #event}/{@link #toggle}/{@link #stop} and the
 * {@code volatile} on {@code enabled} are therefore load-bearing, not defensive; this
 * javadoc used to claim main-thread-only, which invited exactly the "single-threaded
 * anyway" simplification that would corrupt the writer and the flush bookkeeping.
 *
 * <p><b>Flush policy</b> (2026-08-01, elytra-wall investigation part II): the writer holds a
 * 64 KB buffer and flushes at most every {@link #FLUSH_INTERVAL_MS} ms. It used to flush per
 * line, which is one write syscall per event — and {@code col}/{@code col_light} fire PER
 * COLUMN, so at the ~700 columns/s of a backfill wall the instrument became a measurable
 * client-side load on exactly the thing it was measuring. A force-quit now loses at most
 * {@link #FLUSH_INTERVAL_MS} of trace, which is the deliberate trade.
 */
final class ClientTraceLog {

    /** Trace writer buffer — sized so a wall-rate burst of {@code col} lines costs ~5
     *  syscalls/s instead of ~700. */
    private static final int BUFFER_CHARS = 64 * 1024;
    /** Upper bound on trace lost to a client crash / force-quit. */
    private static final long FLUSH_INTERVAL_MS = 200;

    /** Hard ceiling on one trace file (~64 MB of UTF-8-ish text). */
    private static final long MAX_TRACE_CHARS = 64L * 1024 * 1024;

    private static volatile boolean enabled;
    private static BufferedWriter writer;
    private static long startMs;
    private static long lastFlushMs;
    private static long charsWritten;

    /** Stops an active trace, closing its file. Idempotent. Called by the size ceiling
     *  and on disconnect — a trace must not follow the player to the next server. */
    static synchronized void stop() {
        if (!enabled && writer == null) return;
        enabled = false;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                LSSLogger.error("Failed to close " + Brand.shortName() + " trace", e);
            }
            writer = null;
        }
    }

    static boolean enabled() {
        return enabled;
    }

    /** Toggle outcome: {@code path} non-null when a trace started; {@code failed} true when
     *  a start was attempted and could not open its file (so the command can say so instead
     *  of reporting a stop that never happened). */
    record Toggle(Path path, boolean failed) {}

    /** Toggle the trace; see {@link Toggle}. */
    static synchronized Toggle toggle() {
        if (enabled) {
            enabled = false;
            try {
                writer.close();
            } catch (IOException e) {
                LSSLogger.error("Failed to close " + Brand.shortName() + " trace", e);
            }
            writer = null;
            return new Toggle(null, false);
        }
        // A previous trace that died on a write failure leaves its writer for us to close —
        // the stop branch above is unreachable for it (enabled already false).
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                LSSLogger.error("Failed to close leaked " + Brand.shortName() + " trace writer", e);
            }
            writer = null;
        }
        var name = Brand.clientCommand() + "-trace-" + LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl";
        var path = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve(name);
        try {
            Files.createDirectories(path.getParent());
            writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(path), StandardCharsets.UTF_8), BUFFER_CHARS);
            startMs = System.currentTimeMillis();
            lastFlushMs = startMs;
            charsWritten = 0;
            // Drop the net-event rate baselines: the first sample of a new trace must not
            // report a byte rate averaged over the gap since the previous one.
            ClientNetTrace.reset();
            enabled = true;
            return new Toggle(path, false);
        } catch (IOException e) {
            LSSLogger.error("Failed to open " + Brand.shortName() + " trace at " + path, e);
            writer = null;
            return new Toggle(null, true);
        }
    }

    /** Write one event line. Callers must pre-check {@link #enabled()} so the argument
     *  string is never built on the disabled path. {@code body} is the JSON tail after
     *  the common fields, e.g. {@code "\"from\":[1,2],\"to\":[2,2]"}. */
    static synchronized void event(String type, String body) {
        if (!enabled || writer == null) return;
        try {
            long now = System.currentTimeMillis();
            String line = "{\"t\":\"" + type + "\",\"ms\":" + (now - startMs)
                    + (body.isEmpty() ? "" : "," + body) + "}\n";
            writer.write(line);
            charsWritten += line.length();
            // Size ceiling. col/col_light fire PER COLUMN, so a backfill wall writes
            // ~700 lines/s ≈ 200 MB/h, and nothing stopped the trace on its own — it
            // survived disconnect and server switches too. A diagnostic must not be
            // able to fill the player's disk because they forgot it was on.
            if (charsWritten >= MAX_TRACE_CHARS) {
                LSSLogger.warn(Brand.shortName() + " trace reached its "
                        + (MAX_TRACE_CHARS / (1024 * 1024)) + " MB ceiling — trace stopped");
                stop();
                return;
            }
            if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                writer.flush();
                lastFlushMs = now;
            }
        } catch (IOException e) {
            enabled = false;
            try {
                writer.close();
            } catch (IOException closeFailure) {
                // best effort — the handle must not outlive the dead trace
            }
            writer = null;
            LSSLogger.error(Brand.shortName() + " trace write failed — trace stopped", e);
        }
    }
}
