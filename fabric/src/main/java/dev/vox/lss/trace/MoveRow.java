package dev.vox.lss.trace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Row assembly + rendering for the move-desync tracer — the writer half of the schema
 * contract whose reader is {@code scripts/check_move_trace.py} (shared fixtures under
 * {@code scripts/testdata/} pin both sides; move-desync-tracer-plan.md §3).
 *
 * <p>MC-free by design (§3 pure-core split): the hook bodies capture MC-typed values and
 * hand primitives here. Absent-vs-null discipline (§0.5): a field that could not be
 * captured is ABSENT (key not present), never zeroed; the {@code lss} block is an
 * explicit {@code null} for unregistered/LSS-off players because that null is itself the
 * control-arm A/B label. Schema is split by row type (review U-14): {@code too_quickly}
 * returns before {@code move()} runs, so {@code simulated}/{@code residual} do not exist
 * there rather than being null.
 */
public final class MoveRow {

    public static final String TYPE_BOOT = "boot";
    public static final String TYPE_FLIGHT = "flight";
    public static final String TYPE_FLIGHT_RING = "flight_ring";
    public static final String TYPE_TOO_QUICKLY = "too_quickly";
    public static final String TYPE_WRONGLY = "wrongly";
    public static final String TYPE_REJECTED = "rejected";

    public static final String RUNG_MOONRISE = "moonrise";
    public static final String RUNG_VANILLA = "vanilla";
    public static final String RUNG_NONE = "none";

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private MoveRow() {}

    /** Envelope fields present on every non-boot row (§1.4). {@code obuf} and
     *  {@code latencyMs} use -1 = no signal (the probe contract). */
    public record Envelope(String bootId, long wallMs, long tick, String player, String name,
                           String dim, long obuf, int latencyMs, double mspt, int online,
                           long dropped) {}

    /** The LSS context block; null at the row level when the player is unregistered or
     *  LSS is disabled — that null is the control-arm label (§1.4). {@code dialect} is
     *  null for a native-protocol session (key omitted). {@code bwTotal} is the CUMULATIVE
     *  per-player bytes sent — no per-player rolling window exists server-side, and a
     *  different quantity gets a different name than the plan's sketched {@code bw_window}
     *  (the U-12 discipline; deviation logged in v0.10.0-progress.md 2026-08-06). */
    public record LssBlock(long sinceS, int caps, int proto, String dialect, int sendQueue,
                           long bwTotal, long yielded) {}

    /**
     * Chunk-delivery state for one queried chunk (§1.3). Exactly one of the three rungs;
     * fields beyond {@code rung}/{@code anchor} are per-rung — the vanilla rung's
     * {@code not_pending} is deliberately NOT named {@code sent} (review U-12: a strictly
     * weaker predicate must not aggregate silently with the Moonrise mask).
     */
    public record SendState(String rung, int anchorCx, int anchorCz,
                            // moonrise rung
                            Integer mask5x5, Integer maskR1, Integer stage, Integer sendRadius,
                            Integer loaderCx, Integer loaderCz, Integer sendQueue,
                            Integer sendHeadStage,
                            // vanilla rung
                            Boolean notPending) {

        public static SendState moonrise(int anchorCx, int anchorCz, int mask5x5, int maskR1,
                                         Integer stage, Integer sendRadius, Integer loaderCx,
                                         Integer loaderCz, Integer sendQueue, Integer sendHeadStage) {
            return new SendState(RUNG_MOONRISE, anchorCx, anchorCz, mask5x5, maskR1, stage,
                    sendRadius, loaderCx, loaderCz, sendQueue, sendHeadStage, null);
        }

        public static SendState vanilla(int anchorCx, int anchorCz, boolean notPending) {
            return new SendState(RUNG_VANILLA, anchorCx, anchorCz, null, null, null, null,
                    null, null, null, null, notPending);
        }

        public static SendState none() {
            return new SendState(RUNG_NONE, 0, 0, null, null, null, null, null, null, null,
                    null, null);
        }
    }

    // ---- builders ----

    /** Boot row (§1.4, review U-7): the analysis key for every later row of this boot. */
    public static String boot(String bootId, long wallMs, int tzOffsetMin, String lssVersion,
                              String mcVersion, boolean moonrise, boolean c2me, boolean chunky,
                              String rung, Map<String, Object> configSnapshot) {
        var row = new LinkedHashMap<String, Object>();
        row.put("v", MoveDesyncTracer.SCHEMA_VERSION);
        row.put("type", TYPE_BOOT);
        row.put("bootId", bootId);
        row.put("wallMs", wallMs);
        row.put("tz_offset_min", tzOffsetMin);
        row.put("lss_version", lssVersion);
        row.put("mc_version", mcVersion);
        row.put("moonrise", moonrise);
        row.put("c2me", c2me);
        row.put("chunky", chunky);
        row.put("rung", rung);
        row.put("config", configSnapshot);
        return render(row);
    }

    /** 1 Hz flight row for armed players (§1.5). {@code awaitingTp} lives HERE, not on
     *  event rows: every event site sits inside the not-awaiting branch of
     *  {@code updateAwaitingTeleport()}, so the field is structurally false there and
     *  would be confidently wrong (review A-2); on the 1 Hz cadence it can vary. Null =
     *  could not read (absent). */
    public static String flight(Envelope env, boolean lssPresent, LssBlock lss, double x,
                                double y, double z, double speed, Boolean awaitingTp,
                                long gapMs, long gapMax5sMs,
                                SendState sendState, int loadedChunks) {
        var row = envelope(TYPE_FLIGHT, env, lssPresent, lss);
        row.put("pos", pos(x, y, z));
        row.put("speed", speed);
        if (awaitingTp != null) row.put("awaiting_tp", awaitingTp);
        row.put("move_gap_ms", gapMs);
        row.put("move_gap_max_5s_ms", gapMax5sMs);
        row.put("send_state", sendState(sendState));
        row.put("loaded_chunks", loadedChunks);
        return render(row);
    }

    /** The 5 Hz trailing ring, flushed ahead of an event row (§1.5, Fable F2-4). */
    public static String flightRing(Envelope env, boolean lssPresent, LssBlock lss,
                                    FlightRing ring) {
        var row = envelope(TYPE_FLIGHT_RING, env, lssPresent, lss);
        var samples = new ArrayList<Map<String, Object>>(ring.size());
        ring.forEachOldestFirst((wallMs, x, y, z, speed, obuf, gapMs, hasSendState,
                                 anchorCx, anchorCz, mask5x5, loaderCx, loaderCz) -> {
            var s = new LinkedHashMap<String, Object>();
            s.put("wallMs", wallMs);
            s.put("pos", pos(x, y, z));
            s.put("speed", speed);
            s.put("obuf", obuf);
            s.put("gap_ms", gapMs);
            if (hasSendState) {
                s.put("anchor", chunk(anchorCx, anchorCz));
                s.put("sent_mask_5x5", mask5x5);
                s.put("loader_center", chunk(loaderCx, loaderCz));
            }
            samples.add(s);
        });
        row.put("samples", samples);
        return render(row);
    }

    /** {@code too_quickly} event (§1.5): fires BEFORE {@code move()} — no simulated stop,
     *  no residual; the packet-count pair carries both the raw burst and the value the
     *  check actually applied (review F-8). {@code expected_dist_sq} is SQUARED — it is
     *  the check's own {@code getDeltaMovement().lengthSqr()} input, and a squared
     *  quantity gets a squared name (U-12/§0.5; review B-6). */
    public static String tooQuickly(Envelope env, boolean lssPresent, LssBlock lss,
                                    double[] origin, double[] claimed, boolean fallFlying,
                                    double speed, long gapMs,
                                    long gapMax5sMs, int deltaPackets, int deltaPacketsUsed,
                                    double expectedDistSq, SendState claimedState) {
        var row = envelope(TYPE_TOO_QUICKLY, env, lssPresent, lss);
        common(row, origin, claimed, fallFlying, speed, gapMs, gapMax5sMs);
        row.put("delta_packets", deltaPackets);
        row.put("delta_packets_used", deltaPacketsUsed);
        row.put("expected_dist_sq", expectedDistSq);
        row.put("send_state_claimed", sendState(claimedState));
        return render(row);
    }

    /** {@code wrongly} / {@code rejected} events (§1.5). {@code loggedWrongly} is only
     *  meaningful on {@code rejected} rows (pass null on {@code wrongly}); {@code stopBlock}
     *  null = could not sample (absent); {@code restored} null = the HEAD capture never ran
     *  (a partially-applied mixin) — absent, never a zero triple (review C-10). */
    public static String collisionEvent(String type, Envelope env, boolean lssPresent,
                                        LssBlock lss, double[] origin, double[] claimed,
                                        boolean fallFlying, double speed,
                                        long gapMs, long gapMax5sMs, double[] simulated,
                                        double residual, double residualH, double[] restored,
                                        Boolean loggedWrongly, Boolean entityCollide,
                                        String stopBlock, SendState simulatedState,
                                        SendState claimedState) {
        var row = envelope(type, env, lssPresent, lss);
        common(row, origin, claimed, fallFlying, speed, gapMs, gapMax5sMs);
        row.put("simulated", pos(simulated[0], simulated[1], simulated[2]));
        row.put("residual", residual);
        row.put("residual_h", residualH);
        if (restored != null) row.put("restored", pos(restored[0], restored[1], restored[2]));
        if (loggedWrongly != null) row.put("logged_wrongly", loggedWrongly);
        if (entityCollide != null) row.put("entity_collide", entityCollide);
        if (stopBlock != null) row.put("stop_block", stopBlock);
        row.put("send_state", sendState(simulatedState));
        row.put("send_state_claimed", sendState(claimedState));
        return render(row);
    }

    // ---- shared assembly ----

    private static LinkedHashMap<String, Object> envelope(String type, Envelope env,
                                                          boolean lssPresent, LssBlock lss) {
        var row = new LinkedHashMap<String, Object>();
        row.put("v", MoveDesyncTracer.SCHEMA_VERSION);
        row.put("type", type);
        row.put("bootId", env.bootId());
        row.put("wallMs", env.wallMs());
        row.put("tick", env.tick());
        row.put("player", env.player());
        row.put("name", env.name());
        row.put("dim", env.dim());
        row.put("obuf", env.obuf());
        row.put("latency_ms", env.latencyMs());
        row.put("mspt", env.mspt());
        row.put("online", env.online());
        row.put("dropped", env.dropped());
        if (lssPresent && lss != null) {
            var block = new LinkedHashMap<String, Object>();
            block.put("registered", true);
            block.put("since_s", lss.sinceS());
            block.put("caps", lss.caps());
            block.put("proto", lss.proto());
            if (lss.dialect() != null) block.put("dialect", lss.dialect());
            block.put("send_queue", lss.sendQueue());
            block.put("bw_total", lss.bwTotal());
            block.put("yielded", lss.yielded());
            row.put("lss", block);
        } else {
            // Explicit null, not absent: the control-arm label (§1.4).
            row.put("lss", null);
        }
        return row;
    }

    private static void common(LinkedHashMap<String, Object> row, double[] origin,
                               double[] claimed, boolean fallFlying, double speed,
                               long gapMs, long gapMax5sMs) {
        row.put("origin", pos(origin[0], origin[1], origin[2]));
        row.put("claimed", pos(claimed[0], claimed[1], claimed[2]));
        row.put("fall_flying", fallFlying);
        row.put("speed", speed);
        row.put("move_gap_ms", gapMs);
        row.put("move_gap_max_5s_ms", gapMax5sMs);
    }

    /** All rendering funnels here: a non-renderable row (a NaN/Infinity double reaches
     *  Gson, any assembly surprise) returns null and the tracer counts it dropped —
     *  a lost row, never a thrown-into-the-tick-loop exception (review A-6). */
    private static String render(LinkedHashMap<String, Object> row) {
        try {
            return GSON.toJson(row);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, Object> sendState(SendState st) {
        var block = new LinkedHashMap<String, Object>();
        block.put("rung", st.rung());
        if (RUNG_NONE.equals(st.rung())) return block;
        block.put("anchor", chunk(st.anchorCx(), st.anchorCz()));
        if (RUNG_MOONRISE.equals(st.rung())) {
            block.put("sent_mask_5x5", st.mask5x5());
            block.put("sent_r1", st.maskR1());
            if (st.stage() != null) block.put("stage", st.stage());
            if (st.sendRadius() != null) block.put("send_radius", st.sendRadius());
            if (st.loaderCx() != null && st.loaderCz() != null) {
                block.put("loader_center", chunk(st.loaderCx(), st.loaderCz()));
            }
            if (st.sendQueue() != null) block.put("send_queue", st.sendQueue());
            if (st.sendHeadStage() != null) block.put("send_head_stage", st.sendHeadStage());
        } else {
            block.put("not_pending", st.notPending());
        }
        return block;
    }

    private static List<Double> pos(double x, double y, double z) {
        return List.of(x, y, z);
    }

    private static List<Integer> chunk(int cx, int cz) {
        return List.of(cx, cz);
    }
}
