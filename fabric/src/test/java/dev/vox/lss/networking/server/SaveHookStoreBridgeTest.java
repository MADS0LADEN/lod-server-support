package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 3 save-hook -> store bridge
 * ({@code LSSServerNetworking.applySaveObservationToStore}): a content-changing save
 * DEPOSITS the exact hashed bytes with the save's own timestamp; an all-air change
 * deposits byte[0] (never null); the serializer fail-open DELETES the row (a stale
 * pre-edit row must never outlive an edit that could not be re-serialized); unchanged
 * saves and a null store are no-ops.
 */
class SaveHookStoreBridgeTest {

    private record Call(String kind, String dim, long packed, byte[] bytes, long ts) {}

    private static final class RecordingStore implements LodStoreService {
        final List<Call> calls = new ArrayList<>();
        @Override public LodStoreMode mode() { return LodStoreMode.FULL; }
        @Override public StoreHit get(String dimension, long packed) { return null; }
        @Override public void deposit(String dimension, long packed, byte[] sectionBytes,
                                      long columnTimestamp) {
            this.calls.add(new Call("deposit", dimension, packed, sectionBytes, columnTimestamp));
        }
        @Override public void invalidate(String dimension, long[] positions) {
            this.calls.add(new Call("invalidate", dimension, positions[0], null, 0));
        }
        @Override public void delete(String dimension, long packed) {
            this.calls.add(new Call("delete", dimension, packed, null, 0));
        }
        @Override public LodStoreDiagnostics diagnostics() { return new LodStoreDiagnostics(); }
        @Override public void shutdown() { }
    }

    private static final String OW = "minecraft:overworld";

    @Test
    void changedSaveDepositsTheHashedBytesWithTheSaveTimestamp() {
        var store = new RecordingStore();
        byte[] bytes = {1, 2, 3};
        long before = System.currentTimeMillis() / 1000L;
        LSSServerNetworking.applySaveObservationToStore(store, OW, 7, -3,
                new DirtyContentFilter.SaveObservation(true, true, bytes));
        long after = System.currentTimeMillis() / 1000L;
        assertEquals(1, store.calls.size());
        Call c = store.calls.get(0);
        assertEquals("deposit", c.kind());
        assertEquals(PositionUtil.packPosition(7, -3), c.packed());
        assertArrayEquals(bytes, c.bytes());
        assertTrue(c.ts() >= before && c.ts() <= after,
                "deposit must carry the SAVE time as its column timestamp");
    }

    @Test
    void allAirChangeDepositsEmptyBytesNeverNull() {
        var store = new RecordingStore();
        LSSServerNetworking.applySaveObservationToStore(store, OW, 1, 1,
                new DirtyContentFilter.SaveObservation(true, true, null));
        assertEquals(1, store.calls.size());
        assertEquals("deposit", store.calls.get(0).kind());
        assertArrayEquals(new byte[0], store.calls.get(0).bytes(),
                "all-air must deposit byte[0] (the store contract: never null)");
    }

    @Test
    void failOpenDeletesTheRowInsteadOfDepositing() {
        var store = new RecordingStore();
        LSSServerNetworking.applySaveObservationToStore(store, OW, 2, 2,
                new DirtyContentFilter.SaveObservation(true, false, null));
        assertEquals(1, store.calls.size());
        assertEquals("delete", store.calls.get(0).kind(),
                "an edit that could not be re-serialized must kill the stale row");
    }

    @Test
    void unchangedSaveAndNullStoreAreNoOps() {
        var store = new RecordingStore();
        LSSServerNetworking.applySaveObservationToStore(store, OW, 3, 3,
                new DirtyContentFilter.SaveObservation(false, false, null));
        assertEquals(0, store.calls.size(), "a suppressed save must not touch the store");
        LSSServerNetworking.applySaveObservationToStore(null, OW, 3, 3,
                new DirtyContentFilter.SaveObservation(true, true, new byte[]{1}));
    }
}
