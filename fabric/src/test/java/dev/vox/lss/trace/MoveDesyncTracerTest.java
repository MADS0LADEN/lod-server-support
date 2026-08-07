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
        // rows= counts WRITTEN rows (review A-5) — nothing is written while the writer
        // is parked; the two queued rows are not "rows" yet.
        assertEquals(0, tracer.rowCount());
        assertEquals(2, tracer.droppedCount());
    }

    @Test
    void nullRowFromAFailedRenderCountsDropped(@TempDir Path dir) {
        var tracer = new MoveDesyncTracer(dir.resolve("trace.jsonl"), 1L << 20, 2, false);
        tracer.emit(null);
        assertEquals(1, tracer.droppedCount(), "a failed render is a lost row, never a throw");
    }

    @Test
    void rotationFiresOnceThenTheCapStopsTracing(@TempDir Path dir) throws Exception {
        var file = dir.resolve("trace.jsonl");
        var rotated = dir.resolve("trace.1.jsonl");
        // ~100-byte rotation threshold; each row is exactly 30 bytes with the newline.
        var tracer = new MoveDesyncTracer(file, 100, MoveDesyncTracer.QUEUE_CAPACITY, true);
        String row = "{\"pad\":\"0123456789012345678\"}";
        for (int i = 0; i < 12; i++) {
            tracer.emit(row);
            // Pace emissions so the single-writer drain keeps up and the rotation point
            // is crossed by writes, not queue backlog.
            Thread.sleep(5);
        }
        // The cap accounting must be visible BEFORE close (review C-2): rows 1-4 land in
        // .1 (the 120-byte crossing rotates), rows 5-8 in the live file, and rows 9-12
        // are DROPPED at the second crossing — an unbounded-rotation regression would
        // instead write them and drop nothing.
        long deadline = System.currentTimeMillis() + 5_000;
        while (tracer.droppedCount() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(4, tracer.droppedCount(),
                "the cap-triggering row and everything after it must be counted dropped");
        tracer.close();
        assertTrue(Files.exists(rotated), "first crossing must rotate to .1");
        assertTrue(Files.exists(file), "a fresh live file must be reopened after rotation");
        assertEquals(8, Files.readAllLines(rotated).size() + Files.readAllLines(file).size(),
                "exactly 8 of 12 rows survive across the pair — the 2x cap is a real bound");
        assertEquals(8, tracer.rowCount(), "rows= counts written rows only (review A-5)");
        // After the cap latches, further rows drop (counted), nothing is written.
        long sizeBefore = Files.size(file);
        tracer.emit(row);
        assertEquals(sizeBefore, Files.size(file));
    }

    @Test
    void staleRotatedFileFromAPreviousBootDoesNotInheritTheCap(@TempDir Path dir) throws Exception {
        // The B-5 shape: the operator collected and deleted only the LIVE file; a
        // leftover .1 must not turn this boot's first rotation into the final cap.
        var file = dir.resolve("trace.jsonl");
        var rotated = dir.resolve("trace.1.jsonl");
        Files.writeString(rotated, "{\"stale\":true}\n");
        var tracer = new MoveDesyncTracer(file, 100, MoveDesyncTracer.QUEUE_CAPACITY, true);
        String row = "{\"pad\":\"0123456789012345678\"}";
        for (int i = 0; i < 6; i++) {
            tracer.emit(row);
            Thread.sleep(5);
        }
        tracer.close();
        assertEquals(0, tracer.droppedCount(),
                "6 rows must all land — rotation replaced the stale .1 instead of capping");
        assertEquals(6, tracer.rowCount());
        assertFalse(Files.readString(rotated).contains("stale"),
                "the stale .1 was replaced by this boot's rotation");
    }

    @Test
    void closedTracerDropsEmitsAndLeavesTheFileAlone(@TempDir Path dir) throws Exception {
        // The real §3 "disabled instance writes nothing" pin (reviews B-4/C-5): drive an
        // emit against a closed tracer and prove it lands nowhere but the drop counter.
        var file = dir.resolve("trace.jsonl");
        var tracer = new MoveDesyncTracer(file, 1L << 20);
        tracer.emit("{\"a\":1}");
        tracer.close();
        long sizeAfterClose = Files.size(file);
        tracer.emit("{\"late\":true}");
        assertEquals(1, tracer.droppedCount(), "an emit after close is a counted drop");
        assertEquals(sizeAfterClose, Files.size(file), "nothing is written after close");
        // And the static gate defaults off: hook bodies bail on active() == null.
        assertFalse(MoveDesyncTracer.enabled());
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
