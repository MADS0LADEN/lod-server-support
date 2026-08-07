package dev.vox.lss.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writer lifecycle pins (move-desync-tracer-plan.md §3): rows land, overflow drops are
 * counted (never waited on), rotation fires once and the second rotation point is the
 * final cap, a disabled tracer writes nothing.
 */
class MoveDesyncTracerTest {

    @AfterEach
    void tearDown() {
        MoveDesyncTracer.disableForTest();
    }

    @Test
    void rowsReachTheFileAndSurviveClose(@TempDir Path dir) throws Exception {
        var file = dir.resolve("trace.jsonl");
        var tracer = new MoveDesyncTracer(file, MoveDesyncTracer.DEFAULT_ROTATE_BYTES);
        tracer.emit("{\"a\":1}");
        tracer.emit("{\"b\":2}");
        tracer.close();
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), Files.readAllLines(file));
        assertEquals(2, tracer.rowCount());
        assertEquals(0, tracer.droppedCount());
    }

    @Test
    void appendsAcrossRestartsSameFile(@TempDir Path dir) throws Exception {
        var file = dir.resolve("trace.jsonl");
        var first = new MoveDesyncTracer(file, MoveDesyncTracer.DEFAULT_ROTATE_BYTES);
        first.emit("{\"boot\":1}");
        first.close();
        var second = new MoveDesyncTracer(file, MoveDesyncTracer.DEFAULT_ROTATE_BYTES);
        second.emit("{\"boot\":2}");
        second.close();
        assertEquals(List.of("{\"boot\":1}", "{\"boot\":2}"), Files.readAllLines(file));
    }

    @Test
    void overflowDropsAreCountedNeverWaitedOn(@TempDir Path dir) {
        // Writer deliberately not started: the queue (capacity 2) fills deterministically.
        var tracer = new MoveDesyncTracer(dir.resolve("trace.jsonl"), 1L << 20, 2, false);
        tracer.emit("{\"r\":1}");
        tracer.emit("{\"r\":2}");
        tracer.emit("{\"r\":3}");
        tracer.emit("{\"r\":4}");
        assertEquals(2, tracer.rowCount());
        assertEquals(2, tracer.droppedCount());
    }

    @Test
    void rotationFiresOnceThenTheCapStopsTracing(@TempDir Path dir) throws Exception {
        var file = dir.resolve("trace.jsonl");
        var rotated = dir.resolve("trace.1.jsonl");
        // ~100-byte rotation threshold; each row is ~30 bytes.
        var tracer = new MoveDesyncTracer(file, 100, MoveDesyncTracer.QUEUE_CAPACITY, true);
        String row = "{\"pad\":\"0123456789012345678\"}";
        for (int i = 0; i < 12; i++) {
            tracer.emit(row);
            // Pace emissions so the single-writer drain keeps up and the rotation point
            // is crossed by writes, not queue backlog.
            Thread.sleep(5);
        }
        tracer.close();
        assertTrue(Files.exists(rotated), "first crossing must rotate to .1");
        assertTrue(Files.exists(file), "a fresh live file must be reopened after rotation");
        long total = Files.size(rotated) + Files.size(file);
        assertTrue(total <= 2 * 100 + 2L * row.length(),
                "the pair must stay near the 2x cap, got " + total);
        // After the cap latches, further rows drop (counted), nothing is written.
        long sizeBefore = Files.size(file);
        tracer.emit(row);
        assertEquals(sizeBefore, Files.size(file));
    }

    @Test
    void disabledTracerWritesNothing(@TempDir Path dir) {
        assertFalse(MoveDesyncTracer.enabled());
        // Static emit path: hooks check enabled() and bail — nothing to write, and the
        // trace file is never created.
        assertFalse(Files.exists(dir.resolve("logs")));
    }

    @Test
    void testSinkReceivesRowsSynchronously() {
        var rows = new ArrayList<String>();
        var tracer = MoveDesyncTracer.enableForTest(rows::add);
        assertTrue(MoveDesyncTracer.enabled());
        tracer.emit("{\"x\":1}");
        assertEquals(List.of("{\"x\":1}"), rows);
        MoveDesyncTracer.disableForTest();
        assertFalse(MoveDesyncTracer.enabled());
    }

    @Test
    void diagLineCarriesRungRowsDropsAndEventCounts() {
        var tracer = MoveDesyncTracer.enableForTest(r -> { });
        tracer.setRung("moonrise");
        tracer.emit("{}");
        tracer.countEvent(MoveRow.TYPE_TOO_QUICKLY, false);
        tracer.countEvent(MoveRow.TYPE_WRONGLY, false);
        tracer.countEvent(MoveRow.TYPE_REJECTED, true);
        tracer.countEvent(MoveRow.TYPE_REJECTED, false);
        assertEquals("MoveTrace: rung=moonrise rows=1 drops=0 tooquick=1 wrongly=1 rejected=2 silent=1",
                tracer.diagLine());
    }
}
