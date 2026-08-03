package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side v16 backward-compat DECODE pins (a v0.7.0 client reading a v0.4.x–v0.6.2
 * protocol-16 server's frames). {@code WireParityTest} already pins the server-side ENCODE
 * ({@code v16Legacy} / {@code asV16}); this suite is its mirror — the client decode of those
 * exact legacy frames, plus the {@link V16ClientWire} flag that resolves the source-less
 * VoxelColumn layout on the stateless netty thread.
 *
 * <p>The reference frames are the byte-verbatim v0.6.2 legacy encoders (the same ops the
 * server's {@code v16Legacy} / {@code asV16} produce), so a decode drift here or an encode
 * drift there breaks one of the two suites.
 *
 * <p>{@code V16ClientWire.columnSourceless} is a process-global static, so every test resets
 * it before and after — a leaked {@code true} would misalign an unrelated suite's VoxelColumn
 * decode (it would skip the source byte).
 *
 * <p>Arming is announce-gated: a v16 config arms the flag only when this client itself last
 * announced 16 ({@code markAnnouncedVersion} — the discovery fallback always precedes a
 * genuine v16 reply), so tests that model a real v16 session mark the announce first, and
 * {@link #unsolicitedV16SessionConfigDoesNotArmTheSourcelessFlag} pins the inverse — the
 * Paper /reload re-attach prompt (an unsolicited v16 frame on a live v18 session) must not
 * flip column decode mid-stream.
 */
class V16ClientWireDecodeTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    @AfterEach
    void clearFlag() {
        V16ClientWire.reset();
    }

    private static byte[] ref(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private static <T> T decode(StreamCodec<FriendlyByteBuf, T> codec, byte[] frame) {
        var b = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        T r = codec.decode(b);
        b.release();
        return r;
    }

    // ---- SessionConfig: the self-describing frame (version VarInt) ----

    @Test
    void v16SessionConfigDecodesTheSixFieldLayoutAndArmsTheSourcelessFlag() {
        // A genuine v16 reply is always preceded by this client's own discovery fallback.
        V16ClientWire.markAnnouncedVersion(16);
        byte[] frame = ref(b -> {
            b.writeVarInt(16);     // version — the legacy client hard-gates on this
            b.writeBoolean(true);  // enabled
            b.writeVarInt(101);    // lodDistanceChunks
            b.writeVarInt(200);    // syncOnLoad cap (legacy pacing field)
            b.writeVarInt(7);      // generation cap (legacy pacing field)
            b.writeBoolean(false); // generationEnabled
        });

        var p = decode(SessionConfigS2CPayload.CODEC, frame);

        assertEquals(16, p.protocolVersion(), "the decoded frame echoes the legacy version");
        assertTrue(p.enabled());
        assertEquals(101, p.lodDistanceChunks());
        assertFalse(p.generationEnabled());
        assertEquals(200, p.legacySyncCap(), "the two cap VarInts are consumed in order");
        assertEquals(7, p.legacyGenCap());
        assertTrue(p.v16Wire(), "a v16-decoded config is tagged legacy-wire");
        assertTrue(V16ClientWire.isColumnSourceless(),
                "decoding a v16 SessionConfig arms the source-less flag for the columns that follow");
    }

    @Test
    void v18SessionConfigDecodesTheFourFieldLayoutAndDisarmsTheFlag() {
        // Arm it first so the disarm is observable, not just a default.
        V16ClientWire.markAnnouncedVersion(16);
        V16ClientWire.observeSessionConfigVersion(16);
        assertTrue(V16ClientWire.isColumnSourceless());

        byte[] frame = ref(b -> {
            b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
            b.writeBoolean(true);
            b.writeVarInt(256);
            b.writeBoolean(true);
        });

        var p = decode(SessionConfigS2CPayload.CODEC, frame);

        assertEquals(LSSConstants.PROTOCOL_VERSION, p.protocolVersion());
        assertTrue(p.enabled());
        assertEquals(256, p.lodDistanceChunks());
        assertTrue(p.generationEnabled());
        assertFalse(p.v16Wire(), "a v18 decode is never legacy-wire");
        assertFalse(V16ClientWire.isColumnSourceless(),
                "a v18 SessionConfig disarms the source-less flag — columns carry the source byte");
    }

    @Test
    void foreignVersionSessionConfigDrainsAndLeavesTheFlagClear() {
        // A v15-shaped config (10 trailing fields the codec cannot parse): the version VarInt is
        // read, the rest drained, and — version != 16 — the source-less flag stays clear.
        V16ClientWire.markAnnouncedVersion(16);
        V16ClientWire.observeSessionConfigVersion(16); // prove it gets cleared, not merely defaulted
        byte[] frame = ref(b -> {
            b.writeVarInt(15);
            for (int i = 0; i < 10; i++) b.writeVarInt(1234 + i);
        });

        var p = decode(SessionConfigS2CPayload.CODEC, frame);

        assertEquals(15, p.protocolVersion());
        assertFalse(p.enabled(), "the drain branch yields a disabled shape");
        assertFalse(V16ClientWire.isColumnSourceless(),
                "any non-16 version clears the flag — only version 16 arms it");
    }

    // ---- VoxelColumn: the NON-self-describing frame (flag-resolved) ----

    @Test
    void voxelColumnDecodeSkipsTheSourceByteWhenTheFlagIsArmed() {
        V16ClientWire.markAnnouncedVersion(16);
        V16ClientWire.observeSessionConfigVersion(16); // arm: this is a v16 session
        byte[] sections = {9, 8, 7};
        // A legacy source-LESS frame — exactly what asV16() encodes.
        byte[] legacyFrame = ref(b -> {
            b.writeInt(4);
            b.writeInt(-6);
            b.writeUtf("minecraft:overworld");
            b.writeLong(4321L);
            b.writeByteArray(sections); // NO source byte between the long and the array
        });

        var p = decode(VoxelColumnS2CPayload.CODEC, legacyFrame);

        assertEquals(4, p.chunkX());
        assertEquals(-6, p.chunkZ());
        assertEquals("minecraft:overworld", p.dimension().identifier().toString());
        assertEquals(4321L, p.columnTimestamp());
        assertEquals((byte) -1, p.source(), "no source byte on the wire → the 'unknown' sentinel");
        assertArrayEquals(sections, p.shippedSections(),
                "the byte array aligns only if the source byte was correctly skipped");
    }

    @Test
    void voxelColumnDecodeReadsTheSourceByteWhenTheFlagIsClear() {
        // Default (v18) path: byte-identical to the pre-compat decoder.
        byte[] sections = {1, 2, 3, 4};
        byte[] v18Frame = ref(b -> {
            b.writeInt(10);
            b.writeInt(20);
            b.writeUtf("minecraft:the_nether");
            b.writeLong(99L);
            b.writeByte(LSSConstants.COLUMN_SOURCE_DISK); // present in v18+
            b.writeByte(LSSConstants.COLUMN_CODEC_RAW);   // present in v19+
            b.writeByteArray(sections);
        });

        var p = decode(VoxelColumnS2CPayload.CODEC, v18Frame);

        assertEquals(10, p.chunkX());
        assertEquals(20, p.chunkZ());
        assertEquals(99L, p.columnTimestamp());
        assertEquals(LSSConstants.COLUMN_SOURCE_DISK, p.source(),
                "the source byte is read verbatim when the flag is clear");
        assertArrayEquals(sections, p.shippedSections());
    }

    @Test
    void frameOrderSessionConfigThenColumnDecodesTheWholeLegacyExchange() {
        // The load-bearing sequence, in the netty thread's frame order: the client announced
        // 16 (discovery fallback), then a v16 SessionConfig arms the flag, and the
        // source-less column that follows decodes correctly off it.
        V16ClientWire.markAnnouncedVersion(16);
        byte[] cfgFrame = ref(b -> {
            b.writeVarInt(16);
            b.writeBoolean(true);
            b.writeVarInt(64);
            b.writeVarInt(200);
            b.writeVarInt(7);
            b.writeBoolean(true);
        });
        byte[] sections = {5, 5};
        byte[] colFrame = ref(b -> {
            b.writeInt(1);
            b.writeInt(1);
            b.writeUtf("minecraft:overworld");
            b.writeLong(7L);
            b.writeByteArray(sections); // source-less
        });

        var cfg = decode(SessionConfigS2CPayload.CODEC, cfgFrame);
        assertTrue(cfg.v16Wire());
        var col = decode(VoxelColumnS2CPayload.CODEC, colFrame);

        assertEquals(1, col.chunkX());
        assertEquals((byte) -1, col.source());
        assertArrayEquals(sections, col.shippedSections(),
                "the column read off the SessionConfig-armed flag aligns");
    }

    @Test
    void resetClearsTheSourcelessFlagAndTheAnnounceBit() {
        V16ClientWire.markAnnouncedVersion(16);
        V16ClientWire.observeSessionConfigVersion(16);
        assertTrue(V16ClientWire.isColumnSourceless());

        V16ClientWire.reset();

        assertFalse(V16ClientWire.isColumnSourceless(),
                "a connection boundary (JOIN/DISCONNECT) must leave no v16 decode state behind");
        V16ClientWire.observeSessionConfigVersion(16);
        assertFalse(V16ClientWire.isColumnSourceless(),
                "reset must also clear the announce bit — a v16 frame on the NEXT connection "
                        + "cannot arm off a stale announce from this one");
    }

    // ---- The unsolicited v16 frame: the Paper /reload re-attach prompt (R2-3) ----

    @Test
    void unsolicitedV16SessionConfigDoesNotArmTheSourcelessFlag() {
        // A v18 client (last announce: 18) with a live column stream receives a v16-dialect
        // SessionConfig it never asked for — the Paper /reload re-attach prompt. The frame
        // itself is self-describing and must decode (the session gate's downgrade guard
        // answers it); but it must NOT arm source-less column decode: on Folia the prompt
        // can land mid-stream between live v18 columns, and an armed flag there misreads
        // every subsequent source byte until the v18 config reply — a decoder kick of a
        // healthy client.
        V16ClientWire.markAnnouncedVersion(LSSConstants.PROTOCOL_VERSION);
        byte[] promptFrame = ref(b -> {
            b.writeVarInt(16);
            b.writeBoolean(true);
            b.writeVarInt(64);
            b.writeVarInt(200);
            b.writeVarInt(7);
            b.writeBoolean(true);
        });

        var prompt = decode(SessionConfigS2CPayload.CODEC, promptFrame);

        assertEquals(16, prompt.protocolVersion());
        assertTrue(prompt.v16Wire(), "the prompt frame itself decodes fine (self-describing)");
        assertFalse(V16ClientWire.isColumnSourceless(),
                "an unsolicited v16 config must not flip column decode out from under a "
                        + "live v18 stream");

        // The live v18 column right behind the prompt still decodes with its source byte.
        byte[] sections = {6, 6, 6};
        byte[] v18Frame = ref(b -> {
            b.writeInt(2);
            b.writeInt(3);
            b.writeUtf("minecraft:overworld");
            b.writeLong(11L);
            b.writeByte(LSSConstants.COLUMN_SOURCE_GENERATION);
            b.writeByte(LSSConstants.COLUMN_CODEC_RAW);
            b.writeByteArray(sections);
        });
        var col = decode(VoxelColumnS2CPayload.CODEC, v18Frame);
        assertEquals(LSSConstants.COLUMN_SOURCE_GENERATION, col.source(),
                "the in-flight v18 column behind the prompt reads its source byte verbatim");
        assertArrayEquals(sections, col.shippedSections());
    }
}
