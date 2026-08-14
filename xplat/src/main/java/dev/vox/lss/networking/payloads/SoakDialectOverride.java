package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;

/**
 * Dev-only legacy-client EMULATION for the soak harness (C2, XVER §4.2's gate):
 * {@code -Dlss.soak.dialect=19} makes this client behave like a protocol-19 install —
 * announce 19 at handshake, accept the server's 19 echo as a live session, and skip
 * the v20→native decode translation (a v19 session's column bodies arrive in the
 * native section layout, which is exactly what the legacy egress translator emits).
 * That makes {@code SOAK_DIALECT=19 ./scripts/soak.sh fresh-backfill} an end-to-end
 * proof of the server's legacy egress: every conservation law runs against translated
 * bodies, and a translation byte-bug fails decode → ingest-failure → law coverage.
 *
 * <p>Same production-code-property-gate pattern as {@code lss.test.integratedServer}:
 * the class ships (the soak client is the production client) but is inert unless the
 * property is set, which only {@code scripts/soak.sh} does. Read once at class load —
 * the soak client is a fresh JVM per run.
 */
public final class SoakDialectOverride {
    private SoakDialectOverride() {}

    private static final int DIALECT = Integer.getInteger("lss.soak.dialect", 0);

    /** Emulating a protocol-19 client (dev-only; false in every production launch). */
    public static boolean isV19() {
        return DIALECT == LSSConstants.V19_COMPAT_PROTOCOL_VERSION;
    }

    /** The protocol version this client announces and requires echoed. */
    public static int announceVersion() {
        return isV19() ? LSSConstants.V19_COMPAT_PROTOCOL_VERSION : LSSConstants.PROTOCOL_VERSION;
    }
}
