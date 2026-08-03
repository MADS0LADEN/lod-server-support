package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.ColumnBytes;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Fabric build's per-recipient codec choice (compressed-columns plan §2): a capable
 * session ships the holder's frame as codec 1, every other session — flag off, no codec
 * attached (the server-side latch, §0.11), sub-threshold clears — ships codec 0 raw; a
 * mixed dedup fan-out compresses ONCE off the shared holder; and the wire_bytes counter
 * records shipped size at send success while the limiter keeps charging raw.
 */
class CompressedColumnBuildTest {

    private static final long BIG_ALLOCATION = 1_000_000_000L;
    private static StoreCodec codec;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        codec = StoreCodec.zstdOrNull();
        assumeTrue(codec != null, "zstd native unavailable on this platform");
    }

    private record Harness(FabricOffThreadProcessor processor, PlayerRequestState state) {}

    private static Harness harness(boolean attachCodec, boolean sessionWants) {
        var state = new PlayerRequestState(UUID.randomUUID(), 4, 4);
        state.setWantsCompressedColumns(sessionWants);
        var players = new ConcurrentHashMap<UUID, PlayerRequestState>();
        players.put(state.getPlayerUUID(), state);
        var proc = new FabricOffThreadProcessor(players, null, false, null, 1, 0);
        if (attachCodec) proc.attachWireCodec(codec);
        return new Harness(proc, state);
    }

    private static List<CustomPacketPayload> flush(PlayerRequestState state, TickDiagnostics diag)
            throws InterruptedException {
        Thread.sleep(50);
        var sent = new ArrayList<CustomPacketPayload>();
        state.flushSendQueue(BIG_ALLOCATION, new SharedBandwidthLimiter(BIG_ALLOCATION),
                diag, sent::add);
        return sent;
    }

    private static byte[] compressibleRaw() {
        return new byte[8192]; // zeros — compresses far below raw
    }

    @Test
    void capableSessionShipsCodec1Frame() throws InterruptedException {
        var h = harness(true, true);
        byte[] raw = compressibleRaw();
        var holder = ColumnBytes.ofRaw(codec, raw);
        assertTrue(h.processor().buildAndEnqueueColumnPayload(h.state(), 1, 2,
                LSSConstants.DIM_STR_OVERWORLD, 5L, 1L, holder,
                raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                LSSConstants.COLUMN_SOURCE_DISK));
        var diag = new TickDiagnostics();
        var sent = flush(h.state(), diag);
        var col = (VoxelColumnS2CPayload) sent.get(0);
        assertEquals(LSSConstants.COLUMN_CODEC_ZSTD, col.codec());
        assertSame(holder.frame(), col.shippedSections(),
                "the shipped frame IS the holder's memoized frame — no copy, no recompress");
        assertEquals(raw.length, col.rawSize());
        // Limiter charge stays RAW-denominated; wire counter records shipped size.
        assertEquals(raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                diag.getTotalBytesSent());
        assertEquals(holder.frame().length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                diag.getTotalWireBytesSent());
        assertEquals(1, h.processor().getDiagnostics().getTotalColumnsCompressed());
        assertEquals(0, h.processor().getDiagnostics().getTotalColumnsRaw());
    }

    @Test
    void nonCapableSessionShipsRawEvenWithCodecAttached() throws InterruptedException {
        var h = harness(true, false);
        byte[] raw = compressibleRaw();
        var holder = ColumnBytes.ofRaw(codec, raw);
        assertTrue(h.processor().buildAndEnqueueColumnPayload(h.state(), 1, 2,
                LSSConstants.DIM_STR_OVERWORLD, 5L, 1L, holder,
                raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                LSSConstants.COLUMN_SOURCE_DISK));
        var col = (VoxelColumnS2CPayload) flush(h.state(), new TickDiagnostics()).get(0);
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, col.codec());
        assertSame(raw, col.shippedSections());
        assertFalse(holder.frameMaterialized(), "no compress ran for a raw-only recipient");
        assertEquals(1, h.processor().getDiagnostics().getTotalColumnsRaw());
    }

    @Test
    void noCodecAttachedShipsRawForAWantingSession() throws InterruptedException {
        // The server-side latch (plan §0.11): natives unavailable / config off attaches no
        // codec, and even a capability-declaring session ships raw — never a build throw.
        var h = harness(false, true);
        byte[] raw = compressibleRaw();
        assertTrue(h.processor().buildAndEnqueueColumnPayload(h.state(), 1, 2,
                LSSConstants.DIM_STR_OVERWORLD, 5L, 1L, ColumnBytes.ofRaw(null, raw),
                raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                LSSConstants.COLUMN_SOURCE_DISK));
        var col = (VoxelColumnS2CPayload) flush(h.state(), new TickDiagnostics()).get(0);
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, col.codec());
    }

    @Test
    void zeroSectionClearShipsRawForACapableSession() throws InterruptedException {
        // The §0.8 pin through the real build: the 1-byte clear body is sub-threshold, so
        // the holder refuses a frame and the client's decompress-free clear detection holds.
        var h = harness(true, true);
        byte[] clear = {0};
        assertTrue(h.processor().buildAndEnqueueColumnPayload(h.state(), 0, 0,
                LSSConstants.DIM_STR_OVERWORLD, 5L, 1L, ColumnBytes.ofRaw(codec, clear),
                clear.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                LSSConstants.COLUMN_SOURCE_DISK));
        var col = (VoxelColumnS2CPayload) flush(h.state(), new TickDiagnostics()).get(0);
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, col.codec());
        assertArrayEquals(clear, col.shippedSections());
    }

    @Test
    void oversizedUsizeStoreFrameRefusesOnRawSizeAloneWithoutADecompress() {
        // The review-A5 guard in FRAME shape (4-agent round, tooling F1): getFrame's
        // bound is MAX_ROW_USIZE (16 MiB), so store rows over the send cap are legal —
        // and every raw-shaped oversize pin has rawSize == raw.length, so only this
        // case reds a guard rewritten to bytes.raw().length (which would also pay a
        // 16 MiB decompress per re-ask of the same unserveable row).
        var h = harness(true, true);
        byte[] smallFrame = codec.compress(new byte[1024]); // frame size is irrelevant
        var holder = ColumnBytes.ofFrame(codec, smallFrame,
                LSSConstants.MAX_SEND_SECTIONS_SIZE + 1);
        assertFalse(h.processor().buildAndEnqueueColumnPayload(h.state(), 1, 2,
                LSSConstants.DIM_STR_OVERWORLD, 5L, 1L, holder,
                LSSConstants.MAX_SEND_SECTIONS_SIZE + 1 + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                LSSConstants.COLUMN_SOURCE_STORE),
                "an over-cap store row must refuse (the !sent caller answers up_to_date)");
        assertFalse(holder.rawMaterialized(),
                "the refusal fires on rawSize() alone — never a decompress");
        assertEquals(0, h.state().getSendQueueSize());
    }

    @Test
    void mixedFanOutCompressesOnceOffTheSharedHolder() throws InterruptedException {
        var capable = harness(true, true);
        var legacy = new PlayerRequestState(UUID.randomUUID(), 4, 4); // wants=false
        capable.processor().getClass(); // (same processor builds for both recipients)
        byte[] raw = compressibleRaw();
        var holder = ColumnBytes.ofRaw(codec, raw);

        assertTrue(capable.processor().buildAndEnqueueColumnPayload(capable.state(), 1, 2,
                LSSConstants.DIM_STR_OVERWORLD, 5L, 1L, holder,
                raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                LSSConstants.COLUMN_SOURCE_DISK));
        assertTrue(capable.processor().buildAndEnqueueColumnPayload(legacy, 1, 2,
                LSSConstants.DIM_STR_OVERWORLD, 5L, 2L, holder,
                raw.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                LSSConstants.COLUMN_SOURCE_DISK));

        var capCol = (VoxelColumnS2CPayload) flush(capable.state(), new TickDiagnostics()).get(0);
        var rawCol = (VoxelColumnS2CPayload) flush(legacy, new TickDiagnostics()).get(0);
        assertEquals(LSSConstants.COLUMN_CODEC_ZSTD, capCol.codec());
        assertEquals(LSSConstants.COLUMN_CODEC_RAW, rawCol.codec());
        assertSame(capCol.shippedSections(), holder.frame(), "one compress, shared frame");
        assertSame(raw, rawCol.shippedSections(), "the raw recipient shares the original array");
        assertEquals(1, capable.processor().getDiagnostics().getTotalColumnsCompressed());
        assertEquals(1, capable.processor().getDiagnostics().getTotalColumnsRaw());

        // The v16 egress guard, pinned on the SAME two payloads the build just
        // produced. This decision is the only thing between a codec-1 payload and a
        // hard-kicked v16 client — the legacy layout has nowhere to carry a codec byte,
        // so a converted zstd body decodes as garbage — and it had no Fabric-side test
        // at all, while Paper pins its equivalent twice. Reachable in one narrow but
        // real window: an established compressed session downgrading to v16 via the
        // discovery re-handshake can drain already-queued codec-1 payloads into it.
        assertFalse(RequestProcessingService.isV16Convertible(capCol),
                "a compressed column must NEVER be converted to the legacy shape");
        assertTrue(RequestProcessingService.isV16Convertible(rawCol),
                "a raw column is what the legacy shape can carry");
    }

    /** Codec 0 IS the raw constant the guard tests for, and it is the byte a v16 client
     *  never sees. Pins the constant the predicate above is written against, so a
     *  renumbering cannot silently make every column look convertible. */
    @Test
    void rawCodecConstantIsZero() {
        assertEquals(0, LSSConstants.COLUMN_CODEC_RAW);
        assertNotEquals(LSSConstants.COLUMN_CODEC_RAW, LSSConstants.COLUMN_CODEC_ZSTD);
    }
}
