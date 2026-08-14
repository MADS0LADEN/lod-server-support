package dev.vox.lss.networking.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reflective bridge from the production serve path to the dev-only soak probe recorder
 * ({@code BenchmarkMetricsExporter.recordServedColumnBytes} — the {@code probe_hashes}
 * FNV of the exact wire bytes served per armed position, the store byte-parity gate's
 * evidence). The benchmark package is excluded from release jars, so this resolves by
 * name once, only when {@code -Dlss.soak.probes} is non-blank (soak runs only), and
 * no-ops everywhere else — same convention as {@code BenchmarkBridge}/{@code
 * PaperSoakBridge}. Production cost when unarmed: one static null check per serve.
 */
final class SoakProbeBridge {

    private static final MethodHandle RECORD = resolve();

    private SoakProbeBridge() {}

    private static MethodHandle resolve() {
        String probes = System.getProperty("lss.soak.probes");
        if (probes == null || probes.isBlank()) return null;
        try {
            var exporter = Class.forName("dev.vox.lss.benchmark.BenchmarkMetricsExporter");
            return MethodHandles.lookup().findStatic(exporter, "recordServedColumnBytes",
                    MethodType.methodType(void.class, int.class, int.class, byte[].class));
        } catch (Throwable t) {
            return null; // release jar without the harness classes — probes stay -1
        }
    }

    /** True when probes are armed — callers gate expensive argument materialization
     *  (e.g. decompressing a store frame to raw) behind this. */
    static boolean armed() {
        return RECORD != null;
    }

    /** Record one served column's exact RAW section bytes (no-op unless probes are armed). */
    static void recordServed(int cx, int cz, byte[] wireBytes) {
        var h = RECORD;
        if (h == null) return;
        try {
            h.invokeExact(cx, cz, wireBytes);
        } catch (Throwable ignored) {
            // diagnostic instrument only — never let it touch the serve path
        }
    }
}
