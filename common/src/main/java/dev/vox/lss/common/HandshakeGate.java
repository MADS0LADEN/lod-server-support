package dev.vox.lss.common;

/**
 * Pure decision ladder for the C2S handshake, shared by the Fabric
 * ({@code LSSServerNetworking}) and Paper ({@code LSSPaperPlugin}) receivers so
 * the reply/registration policy cannot drift between platforms.
 *
 * <p>Ladder order is load-bearing: the version check must run first because a
 * mismatched client must receive NO reply at all, and the capability check
 * outranks the enabled check so a consumer-less client is classified (and
 * logged) the same way whether or not LSS is enabled.
 */
public final class HandshakeGate {
    private HandshakeGate() {}

    /** Why the gate settled on its decision; call sites key their log lines off this. */
    public enum Outcome {
        /**
         * Protocol skew — must not reply: a mismatched client's SessionConfig codec
         * has a different field layout on the same channel id, so any frame we send
         * decodes as a DecoderException and kicks the player. Sending nothing leaves
         * its LSS disabled (no LodRequestManager is created).
         */
        VERSION_MISMATCH,
        /**
         * No LOD consumer on the client — reply with the session config, but never
         * register: registering would only create a zombie state that ignores every
         * request.
         */
        NO_CONSUMER,
        /** LSS off (config disabled or service absent) — advertise disabled, no registration. */
        DISABLED,
        /** Compatible, capable, enabled — reply and register. */
        REGISTER
    }

    /**
     * Which wire shapes the reply (and the whole session) must use. Decided by the version
     * rung ONLY — the rest of the ladder is dialect-agnostic, so reply/registration policy
     * stays identical for legacy and current clients.
     */
    public enum WireDialect {
        /** Current protocol ({@link LSSConstants#PROTOCOL_VERSION}) — canonical shapes.
         *  (Named V18 through protocol 18; renamed so version bumps stop renaming the
         *  dialect. The {@link #V18} constant below is the protocol-18 COMPAT dialect, a
         *  different thing.) */
        CURRENT,
        /**
         * Protocol 19 under {@code enableV19Compat}
         * (docs/planning/cross-version-identity-encoding-plan.md §4.2): the reply is the
         * CURRENT 4-field SessionConfig ECHOING VERSION 19 (the v19 client's gate
         * hard-requires its own version), C2S is byte-identical, and the column BODY is
         * translated per serve from the v20 identity-dictionary layout back to native
         * global-id palettes — a body translation, NOT a header splice like the two
         * rungs below. Membership rides {@code WireDialectTracker}.
         */
        V19,
        /**
         * Protocol 18 under {@code enableV18Compat} (docs/planning/v18-compat-design.md):
         * the reply is the CURRENT 4-field SessionConfig ECHOING VERSION 18 (the old
         * client's gate hard-requires its own version), the session is forced codec-RAW,
         * and column frames ship without the codec byte. Everything else — the whole C2S
         * direction included — is the current dialect; membership is tracked by
         * {@code WireDialectTracker}, no v16-style synthetic want-set.
         */
        V18,
        /**
         * Legacy protocol 16 under {@code enableV16Compat}: the reply must be the 6-field
         * SessionConfig ECHOING VERSION 16 (the old client's codec hard-gates on that leading
         * VarInt and disables itself otherwise), columns must ship source-less, and the
         * session is tracked by the v16 compat shim (docs/planning/v16-compat-design.md).
         */
        V16
    }

    /**
     * @param outcome          classification driving call-site logging
     * @param effectiveEnabled the enabled flag to advertise in the session config
     * @param dialect          wire shapes the reply/session must use (version rung's choice)
     */
    public record Decision(Outcome outcome, boolean effectiveEnabled, WireDialect dialect) {
        /** Whether any reply may be sent at all (false only on version skew). */
        public boolean sendSessionConfig() {
            return outcome != Outcome.VERSION_MISMATCH;
        }

        /** Whether to register the player for LOD request processing. */
        public boolean registerPlayer() {
            return outcome == Outcome.REGISTER;
        }
    }

    /**
     * Evaluates the handshake against {@link LSSConstants#PROTOCOL_VERSION}, with the three
     * compat rungs: protocol-19 under {@code v19CompatEnabled}, protocol-18 under
     * {@code v18CompatEnabled}, and protocol-16 under {@code v16CompatEnabled} each take
     * the SAME ladder with their compat dialect instead of the silent mismatch.
     *
     * @param clientProtocolVersion protocol version the client handshook with
     * @param clientCapabilities    client capabilities bitmask
     * @param configEnabled         server config {@code enabled} flag
     * @param servicePresent        whether the request processing service is running
     * @param v16CompatEnabled      server config {@code enableV16Compat} flag
     * @param v18CompatEnabled      server config {@code enableV18Compat} flag
     * @param v19CompatEnabled      server config {@code enableV19Compat} flag
     */
    public static Decision evaluate(int clientProtocolVersion, int clientCapabilities,
                                    boolean configEnabled, boolean servicePresent,
                                    boolean v16CompatEnabled, boolean v18CompatEnabled,
                                    boolean v19CompatEnabled) {
        WireDialect dialect;
        if (clientProtocolVersion == LSSConstants.PROTOCOL_VERSION) {
            dialect = WireDialect.CURRENT;
        } else if (clientProtocolVersion == LSSConstants.V19_COMPAT_PROTOCOL_VERSION
                && v19CompatEnabled) {
            dialect = WireDialect.V19;
        } else if (clientProtocolVersion == LSSConstants.V18_COMPAT_PROTOCOL_VERSION
                && v18CompatEnabled) {
            dialect = WireDialect.V18;
        } else if (clientProtocolVersion == LSSConstants.V16_COMPAT_PROTOCOL_VERSION
                && v16CompatEnabled) {
            dialect = WireDialect.V16;
        } else {
            return new Decision(Outcome.VERSION_MISMATCH, false, WireDialect.CURRENT);
        }
        boolean effectiveEnabled = configEnabled && servicePresent;
        if ((clientCapabilities & LSSConstants.CAPABILITY_VOXEL_COLUMNS) == 0) {
            return new Decision(Outcome.NO_CONSUMER, effectiveEnabled, dialect);
        }
        if (!effectiveEnabled) {
            return new Decision(Outcome.DISABLED, false, dialect);
        }
        return new Decision(Outcome.REGISTER, true, dialect);
    }

    /** Pre-v19-compat overload (v19 rung disabled): kept so existing gate tests keep
     *  pinning the two-rung ladder unchanged. Production call sites use the 7-arg form —
     *  a call site left on this overload silently drops v19 clients to the v16 fallback,
     *  which is why each platform pins its PRODUCTION handshake path with a v19 frame. */
    public static Decision evaluate(int clientProtocolVersion, int clientCapabilities,
                                    boolean configEnabled, boolean servicePresent,
                                    boolean v16CompatEnabled, boolean v18CompatEnabled) {
        return evaluate(clientProtocolVersion, clientCapabilities, configEnabled,
                servicePresent, v16CompatEnabled, v18CompatEnabled, false);
    }

    /** Pre-v18-compat overload (v18 + v19 rungs disabled): kept so existing gate tests
     *  keep pinning the v16-era ladder unchanged. */
    public static Decision evaluate(int clientProtocolVersion, int clientCapabilities,
                                    boolean configEnabled, boolean servicePresent,
                                    boolean v16CompatEnabled) {
        return evaluate(clientProtocolVersion, clientCapabilities, configEnabled,
                servicePresent, v16CompatEnabled, false, false);
    }

    /** Pre-v16-compat overload (all compat rungs disabled): kept so existing gate tests
     *  keep pinning the strict ladder unchanged. */
    public static Decision evaluate(int clientProtocolVersion, int clientCapabilities,
                                    boolean configEnabled, boolean servicePresent) {
        return evaluate(clientProtocolVersion, clientCapabilities, configEnabled,
                servicePresent, false, false, false);
    }
}
