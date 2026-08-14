package dev.vox.lss.trace;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Row-format goldens (move-desync-tracer-plan.md §3): the fixture file under
 * {@code scripts/testdata/} is SHARED with {@code check_move_trace.py --selftest}, so the
 * writer (this module) and the reader (the validator) cannot drift — the
 * {@code check_soak.py} selftest culture applied to the tracer.
 *
 * <p>Absent-vs-null discipline pinned here: the {@code lss} block is an explicit
 * {@code null} on control-arm rows; uncapturable fields ({@code stop_block},
 * {@code entity_collide}, per-sample send state) are ABSENT keys, never null/zero;
 * {@code too_quickly} carries no {@code simulated}/{@code residual} at all.
 *
 * <p>Regenerate after a deliberate schema change by DELETING the fixture file and
 * re-running this test (it recreates the file, then fails once so the regeneration is
 * always a conscious, reviewed act); bump {@link MoveDesyncTracer#SCHEMA_VERSION} when
 * the change is more than additive, and update {@code check_move_trace.py} in the same
 * commit.
 */
class MoveRowGoldenTest {

    private static final String FIXTURE = "scripts/testdata/move-trace-rows.jsonl";

    /** The fixture inputs — fixed values, one row per type + edge variants. */
    static List<String> fixtureRows() {
        var rows = new ArrayList<String>();
        var config = new LinkedHashMap<String, Object>();
        config.put("bytesPerSecondLimitPerPlayer", 15728640);
        config.put("bytesPerSecondLimitGlobal", 62914560);
        config.put("lodDistanceChunks", 256);
        config.put("lodStore", "full");
        // Historical fixture value: the key was deleted from the live config echo
        // (2026-08-13) but stays in this map so the SHARED golden fixture
        // (scripts/testdata/move-trace-rows.jsonl) needs no regeneration — the
        // validator tolerates unknown config keys by design.
        config.put("outboundBufferCeilingKB", 0);
        config.put("lodYieldsToVanillaTransport", false);
        rows.add(MoveRow.boot("b0a1", 1754400000000L, 120, "0.10.0", "26.2",
                true, false, true, MoveRow.RUNG_MOONRISE, config));

        var env = new MoveRow.Envelope("b0a1", 1754400001000L, 200,
                "11111111-2222-3333-4444-555555555555", "Steve", "minecraft:overworld",
                65536, 48, 12.5, 3, 0);
        var lssNative = new MoveRow.LssBlock(75, 1, 19, null, 40, 123456789L, 0L);

        // flight: moonrise send state, registered native session, awaiting_tp captured.
        rows.add(MoveRow.flight(env, true, lssNative, 100.5, 65.0, -200.25, 21.7, false,
                50, 120,
                MoveRow.SendState.moonrise(6, -13, 0x1FFFFFF, 0x1FF, 5, 10, 6, -13, 3, 2),
                4096));

        // flight_ring: two samples — first with send state, second without (absent keys);
        // control-arm row (lss null).
        var ring = new FlightRing();
        ring.add(1754400000600L, 90.0, 64.0, -190.0, 20.0, 32768, 45, true, 5, -12,
                0x1000, 5, -12);
        ring.addNoSendState(1754400000800L, 95.0, 64.5, -195.0, 20.5, -1, 55);
        rows.add(MoveRow.flightRing(env, false, null, ring));

        // too_quickly: v16-dialect session; claimed chunk on the vanilla rung; NO
        // simulated/residual keys exist for this type (and no awaiting_tp — flight-only).
        var lssV16 = new MoveRow.LssBlock(12, 1, 16, "v16", 0, 999L, 7L);
        rows.add(MoveRow.tooQuickly(env, true, lssV16,
                new double[] {100.0, 64.0, -200.0}, new double[] {118.5, 64.0, -200.0},
                true, 30.2, 40, 90, 7, 1, 0.09,
                MoveRow.SendState.vanilla(7, -13, true)));

        // wrongly: entity collision + stop block captured; control-arm (lss null).
        rows.add(MoveRow.collisionEvent(MoveRow.TYPE_WRONGLY, env, false, null,
                new double[] {100.0, 64.0, -200.0}, new double[] {103.0, 64.0, -200.0},
                false, 8.1, 45, 100,
                new double[] {101.4, 64.0, -200.0}, 1.6, 1.6,
                new double[] {100.0, 64.0, -200.0},
                null, true, "minecraft:stone",
                MoveRow.SendState.moonrise(6, -13, 0, 0, 1, 10, 6, -13, 40, 1),
                MoveRow.SendState.moonrise(6, -13, 0, 0, 0, 10, 6, -13, 40, 1)));

        // too_quickly again, v19-dialect session (the C1 protocol-20 bump: v0.9.x
        // clients ride the v19 compat rung — the dialect set is writer/reader-shared,
        // so the fixture carries all three legacy values).
        var lssV19 = new MoveRow.LssBlock(45, 3, 19, "v19", 2, 512L, 0L);
        rows.add(MoveRow.tooQuickly(env, true, lssV19,
                new double[] {-40.0, 70.0, 80.0}, new double[] {-58.5, 70.0, 80.0},
                false, 28.4, 35, 80, 6, 2, 0.12,
                MoveRow.SendState.none()));

        // rejected: the SILENT path (logged_wrongly false), stop_block/entity_collide
        // uncapturable (absent), rung none, v18-dialect session.
        var lssV18 = new MoveRow.LssBlock(300, 1, 18, "v18", 5, 42L, 0L);
        rows.add(MoveRow.collisionEvent(MoveRow.TYPE_REJECTED, env, true, lssV18,
                new double[] {0.0, 70.0, 0.0}, new double[] {0.0, 66.0, 0.0},
                false, 2.0, 60, 60,
                new double[] {0.0, 68.5, 0.0}, 2.5, 0.0,
                new double[] {0.0, 70.0, 0.0},
                false, null, null,
                MoveRow.SendState.none(), MoveRow.SendState.none()));
        return rows;
    }

    @Test
    void renderedRowsMatchTheSharedFixture() throws Exception {
        Path fixture = locate(FIXTURE);
        var rendered = fixtureRows();
        if (!Files.exists(fixture)) {
            Files.createDirectories(fixture.getParent());
            Files.write(fixture, rendered);
            fail("fixture created at " + fixture + " — review it, commit it, and re-run"
                    + " (deleting the file is the deliberate regeneration act)");
        }
        assertEquals(Files.readAllLines(fixture), rendered,
                "writer output drifted from the shared fixture — if deliberate, regenerate"
                        + " (see class javadoc) and update check_move_trace.py in the same commit");
    }

    /** The GameTestEntrypointContractTest locate idiom — CWD-agnostic repo walk. */
    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve("scripts"))) {
                return dir.resolve(repoRelative);
            }
        }
        throw new IllegalStateException("could not locate repo root (scripts/) from CWD");
    }
}
