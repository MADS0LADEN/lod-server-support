package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the Fabric wire format for all 6 payloads to an explicit byte reference built from raw
 * {@link FriendlyByteBuf} ops. The Paper module has a mirror test
 * ({@code dev.vox.lss.paper.WireParityTest}) asserting its codec against the IDENTICAL reference
 * ops — so if either implementation drifts, one of the two suites fails. This is the cross-impl
 * parity guard (a Fabric client must understand a Paper server's frames and vice-versa).
 */
class WireParityTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Build the reference wire bytes from explicit FriendlyByteBuf ops. */
    private static byte[] ref(Consumer<FriendlyByteBuf> ops) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        ops.accept(b);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private static <T> byte[] encode(StreamCodec<FriendlyByteBuf, T> codec, T payload) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(b, payload);
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

    // ---- C2S ----

    @Test
    void handshake() {
        for (int[] f : new int[][]{{15, 1}, {300, 0}, {15, -1}}) {
            byte[] expected = ref(b -> {
                b.writeVarInt(f[0]);
                b.writeVarInt(f[1]);
            });
            assertArrayEquals(expected, encode(HandshakeC2SPayload.CODEC, new HandshakeC2SPayload(f[0], f[1])),
                    "handshake " + f[0] + "," + f[1]);
            var d = decode(HandshakeC2SPayload.CODEC, expected);
            assertEquals(f[0], d.protocolVersion());
            assertEquals(f[1], d.capabilities());
        }
    }

    @Test
    void batchChunkRequest() {
        long[] pos = {0L, 0x8000000000000001L, -1L};
        long[] ts = {0L, 1700000000000L, Long.MIN_VALUE};
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            for (int i = 0; i < 3; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        assertArrayEquals(expected, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(pos, ts, 3)));
        // empty
        assertArrayEquals(new byte[]{0}, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(new long[0], new long[0], 0)));
    }

    @Test
    void batchChunkRequestExtremeCorners() {
        // ts=-1 (unknown/first request) and ts=Long.MAX_VALUE plus the (MAX,MAX)/(MAX,MIN)
        // corner positions — the matrix cells the base parity frame does not carry.
        long[] pos = {PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MAX_VALUE),
                PositionUtil.packPosition(Integer.MAX_VALUE, Integer.MIN_VALUE)};
        long[] ts = {-1L, Long.MAX_VALUE};
        byte[] expected = ref(b -> {
            b.writeVarInt(2);
            for (int i = 0; i < 2; i++) {
                b.writeLong(pos[i]);
                b.writeLong(ts[i]);
            }
        });
        assertArrayEquals(expected, encode(BatchChunkRequestC2SPayload.CODEC,
                new BatchChunkRequestC2SPayload(pos, ts, 2)));
        var d = decode(BatchChunkRequestC2SPayload.CODEC, expected);
        assertArrayEquals(pos, d.packedPositions());
        assertArrayEquals(ts, d.clientTimestamps());
    }

    // ---- S2C ----

    @Test
    void sessionConfig() {
        var p = new SessionConfigS2CPayload(3, true, 8, false);
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            b.writeBoolean(true);
            b.writeVarInt(8);
            b.writeBoolean(false);
        });
        assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC, p));
    }

    @Test
    void sessionConfigToleratesForeignVersionLayout() {
        // A v15 server's 10-field SessionConfig frame. The current decoder must consume the whole
        // frame (no trailing bytes -> no decoder kick) and surface the version so the
        // client-side protocol gate can fire.
        byte[] v15Frame = ref(b -> {
            b.writeVarInt(15);          // protocolVersion
            b.writeBoolean(true);       // enabled
            b.writeVarInt(8);           // lodDistanceChunks
            b.writeVarInt(1);           // serverCapabilities
            b.writeVarInt(40);          // syncOnLoadRateLimitPerPlayer
            b.writeVarInt(300);         // syncOnLoadConcurrencyLimitPerPlayer
            b.writeVarInt(20);          // generationRateLimitPerPlayer
            b.writeVarInt(16);          // generationConcurrencyLimitPerPlayer
            b.writeBoolean(true);       // generationEnabled
            b.writeVarLong(1_000_000L); // playerBandwidthLimit
        });
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(v15Frame));
        var p = SessionConfigS2CPayload.CODEC.decode(buf);
        assertEquals(0, buf.readableBytes(), "decoder must consume the full foreign frame");
        buf.release();
        assertEquals(15, p.protocolVersion());
        assertEquals(false, p.enabled());
    }

    @Test
    void frozenHandshakeShape_theV20AnnounceIsExactlyTwoVarInts() {
        // XVER §2.2 CRITICAL (found independently by two review lenses): every legacy
        // Fabric server's registered codec reads exactly two VarInts on
        // lss:handshake_c2s, and TRAILING BYTES ARE A DECODER KICK. The announce shape
        // is frozen for v20 and forever — new client facts ride lss:client_info. This
        // pin decodes the v20 announce under a strict legacy-shaped read and requires
        // full drain.
        byte[] frame = encode(HandshakeC2SPayload.CODEC,
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, 3));
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        try {
            assertEquals(LSSConstants.PROTOCOL_VERSION, buf.readVarInt());
            assertEquals(3, buf.readVarInt());
            assertEquals(0, buf.readableBytes(),
                    "a third byte on the announce hard-kicks the v20 client from every"
                            + " shipped Fabric server");
        } finally {
            buf.release();
        }
    }

    @Test
    void clientInfoEncodesOneVarIntAndDecodeToleratesTrailingBytes() {
        // The sidecar's own shape (XVER §2.2): one VarInt; the decode drains trailing
        // bytes so a future append never kicks.
        byte[] expected = ref(b -> b.writeVarInt(3955));
        assertArrayEquals(expected, encode(ClientInfoC2SPayload.CODEC,
                new ClientInfoC2SPayload(3955)));
        byte[] trailing = ref(b -> {
            b.writeVarInt(3955);
            b.writeVarInt(42);
        });
        assertEquals(3955, decode(ClientInfoC2SPayload.CODEC, trailing).dataVersion());
    }

    @Test
    void sessionConfigVarIntBoundaries() {
        // lodDistance crossing the 1->2 byte VarInt boundary (127/128) and the 2048 config
        // max — the one variable-width field left in the 4-field frame.
        int[] cases = {127, 128, 2048};
        for (int lod : cases) {
            byte[] expected = ref(b -> {
                b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
                b.writeBoolean(true);
                b.writeVarInt(lod);
                b.writeBoolean(false);
                b.writeVarInt(0);  // v20 data-version append (0 = unknown on the 4-arg ctor)
            });
            var p = new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true,
                    lod, false);
            assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC, p),
                    "sessionConfig lod=" + lod);
            var d = decode(SessionConfigS2CPayload.CODEC, expected);
            assertEquals(lod, d.lodDistanceChunks());
        }
    }

    @Test
    void batchResponse() {
        // Type 0 is the RETIRED-and-RESERVED v16 rate-limited tag and 200 is a hypothetical
        // future tag: neither has a constant, and both are here on purpose. The codec is
        // type-agnostic by contract — it must ship any byte through unaltered, or a future
        // response type could not be added without a second wire break. Keep both.
        byte[] types = {(byte) 0, LSSConstants.RESPONSE_UP_TO_DATE,
                LSSConstants.RESPONSE_NOT_GENERATED, (byte) 200};
        long[] positions = {0L, PositionUtil.packPosition(10, 20), -1L,
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        byte[] expected = ref(b -> {
            b.writeVarInt(4);
            for (int i = 0; i < 4; i++) {
                b.writeByte(types[i]);
                b.writeLong(positions[i]);
            }
        });
        assertArrayEquals(expected, encode(BatchResponseS2CPayload.CODEC,
                new BatchResponseS2CPayload(types, positions, 4)));
    }

    @Test
    void batchResponseAtMaxCountMatchesReference() {
        // 4096 entries puts the count VarInt in its 2-byte regime — the only count regime
        // production ever ships at full flush. Identical fill in the Paper twin.
        int max = LSSConstants.MAX_BATCH_RESPONSES;
        byte[] types = new byte[max];
        long[] positions = new long[max];
        for (int i = 0; i < max; i++) {
            types[i] = (byte) (i % 3);
            positions[i] = PositionUtil.packPosition(i - 2048, 2048 - i);
        }
        byte[] expected = ref(b -> {
            b.writeVarInt(max);
            for (int i = 0; i < max; i++) {
                b.writeByte(types[i]);
                b.writeLong(positions[i]);
            }
        });
        assertArrayEquals(expected, encode(BatchResponseS2CPayload.CODEC,
                new BatchResponseS2CPayload(types, positions, max)));
    }

    @Test
    void dirtyColumns() {
        long[] positions = {PositionUtil.packPosition(10, 20), PositionUtil.packPosition(-5, 100),
                PositionUtil.packPosition(Integer.MIN_VALUE, Integer.MAX_VALUE)};
        byte[] expected = ref(b -> {
            b.writeVarInt(3);
            for (long p : positions) b.writeLong(p);
        });
        assertArrayEquals(expected, encode(DirtyColumnsS2CPayload.CODEC, new DirtyColumnsS2CPayload(positions)));
    }

    @Test
    void voxelColumnVanillaDimensions() {
        byte[] sections = {1, 2, 3};
        record Case(ResourceKey<Level> key, String dim) {}
        for (Case c : new Case[]{new Case(Level.OVERWORLD, "minecraft:overworld"),
                new Case(Level.NETHER, "minecraft:the_nether"), new Case(Level.END, "minecraft:the_end")}) {
            byte[] expected = ref(b -> {
                b.writeInt(-5);
                b.writeInt(Integer.MAX_VALUE);
                b.writeUtf(c.dim());
                b.writeLong(-1L);
                b.writeByte(-1); // serve-source: unknown (source-less convenience path)
                b.writeByte(LSSConstants.COLUMN_CODEC_RAW);
                b.writeByteArray(sections);
            });
            assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                    new VoxelColumnS2CPayload(-5, Integer.MAX_VALUE, c.key(), -1L, sections)),
                    "voxelColumn dim " + c.dim());
        }
    }

    @Test
    void voxelColumnCustomDimension() {
        byte[] sections = {1, 2, 3};
        var custom = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lsstest:custom"));
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf("lsstest:custom");
            b.writeLong(42L);
            b.writeByte(-1); // serve-source: unknown (source-less convenience path)
            b.writeByte(LSSConstants.COLUMN_CODEC_RAW);
            b.writeByteArray(sections);
        });
        assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                new VoxelColumnS2CPayload(0, 0, custom, 42L, sections)));
    }

    @Test
    void voxelColumnEmptySectionBytes() {
        // sectionBytes=byte[0] is the legitimate "nothing visible" shape: it must encode as
        // a single 0x00 length VarInt and decode back to byte[0] — the client's defensive
        // empty-bytes ingest report depends on actually receiving it.
        byte[] expected = ref(b -> {
            b.writeInt(11);
            b.writeInt(-7);
            b.writeUtf("minecraft:overworld");
            b.writeLong(5L);
            b.writeByte(-1); // serve-source: unknown (source-less convenience path)
            b.writeByte(LSSConstants.COLUMN_CODEC_RAW);
            b.writeByteArray(new byte[0]);
        });
        assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC,
                new VoxelColumnS2CPayload(11, -7, Level.OVERWORLD, 5L, new byte[0])));
        assertEquals(0, expected[expected.length - 1],
                "empty section bytes must encode as a single 0x00 length VarInt");
        var d = decode(VoxelColumnS2CPayload.CODEC, expected);
        assertEquals(0, d.shippedSections().length);
    }

    // ---- v16 compat legacy shapes (docs/planning/v16-compat-design.md §2) ----
    // Reference ops mirror the v0.6.2 encoders VERBATIM (verified against the tag): the
    // 6-field SessionConfig layout and the source-less VoxelColumn frame — the golden
    // contract a legacy protocol-16 client decodes. The Paper twin asserts its encoders
    // against these IDENTICAL ops.

    @Test
    void sessionConfigV16LegacyEncodesTheSixFieldLayoutEchoingVersion16() {
        byte[] expected = ref(b -> {
            b.writeVarInt(16);     // MUST echo 16 — the v0.6.2 codec hard-gates on this VarInt
            b.writeBoolean(true);  // enabled
            b.writeVarInt(101);    // lodDistanceChunks
            b.writeVarInt(200);    // syncOnLoadConcurrencyLimitPerPlayer (the client's pacing)
            b.writeVarInt(7);      // generationConcurrencyLimitPerPlayer
            b.writeBoolean(false); // generationEnabled
        });
        assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC,
                SessionConfigS2CPayload.v16Legacy(true, 101, 200, 7, false)));
    }

    @Test
    void currentSessionConfigEncodeIsByteIdenticalWithTheLegacyFieldsDormant() {
        // (Renamed from v18SessionConfig... — "v18" was the CURRENT dialect's old name.)
        // Regression pin: the compat fields must not perturb the current 4-field frame.
        byte[] expected = ref(b -> {
            b.writeVarInt(LSSConstants.PROTOCOL_VERSION);
            b.writeBoolean(true);
            b.writeVarInt(256);
            b.writeBoolean(true);
            b.writeVarInt(3955);  // the v20-only data-version append (XVER §2.2)
        });
        assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC,
                new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, 256, true, 3955)));
        // The ECHO versions must stay 4-field — a trailing byte hard-kicks the strict
        // legacy clients (the reason the append is version-gated at the encoder).
        byte[] v19Echo = ref(b -> {
            b.writeVarInt(LSSConstants.V19_COMPAT_PROTOCOL_VERSION);
            b.writeBoolean(true);
            b.writeVarInt(256);
            b.writeBoolean(true);
        });
        assertArrayEquals(v19Echo, encode(SessionConfigS2CPayload.CODEC,
                new SessionConfigS2CPayload(LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                        true, 256, true, 3955)));
    }

    @Test
    void voxelColumnAsV16DropsExactlyTheSourceByteForEveryProducerTag() {
        // Dialect totality at the wire: whichever producer served the column (probe, disk,
        // generation, or the source-less rig path), asV16() must yield the IDENTICAL legacy
        // frame — a leaked source byte is parsed by the old client as the section-array
        // length VarInt and hard-kicks it.
        byte[] sections = {9, 8, 7};
        byte[] expectedLegacy = ref(b -> {
            b.writeInt(3);
            b.writeInt(-4);
            b.writeUtf("minecraft:overworld");
            b.writeLong(1234L);
            b.writeByteArray(sections);
        });
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
        byte[] sources = {LSSConstants.COLUMN_SOURCE_IN_MEMORY, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_SOURCE_GENERATION, (byte) -1};
        for (byte source : sources) {
            var p = new VoxelColumnS2CPayload(3, -4, dim, 1234L, source, sections);
            assertArrayEquals(expectedLegacy, encode(VoxelColumnS2CPayload.CODEC, p.asV16()),
                    "source tag " + source + " must vanish identically");
            assertArrayEquals(sections, p.asV16().shippedSections(),
                    "asV16 must not copy or alter the section bytes");
        }
    }

    @Test
    void voxelColumnAsV16KeepsTheGhostClearAndLongDimensions() {
        // The 0-section authoritative clear must survive the legacy rewrite (the v0.6.2
        // client handles it: isClearColumn + air-fill), and a >127-char dimension string
        // (2-byte VarInt UTF prefix) must not shift the layout.
        String longDim = "lsstest:" + "d".repeat(120);
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf(longDim);
            b.writeLong(42L);
            b.writeByteArray(new byte[0]);
        });
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(longDim));
        var p = new VoxelColumnS2CPayload(0, 0, dim, 42L,
                LSSConstants.COLUMN_SOURCE_IN_MEMORY, new byte[0]);
        assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC, p.asV16()));
    }

    // ---- v18 compat legacy shapes (docs/planning/v18-compat-design.md §2.6) ----
    // Reference ops mirror the v0.8.2 decoder VERBATIM (verified against the tag): the
    // protocol-18 column frame is the CURRENT layout minus the codec byte — readInt,
    // readInt, readUtf, readLong, readByte(source), readByteArray. The Paper twin
    // asserts rewriteColumnToV18 against these IDENTICAL ops.

    @Test
    void sessionConfigEchoing18IsTheCurrentFourFieldLayout() {
        // The v18 reply is NOT a new shape — the same 4-field encode with the version
        // value 18 (the v0.8.x gate hard-requires its own version; the encoder must not
        // branch on the value).
        byte[] expected = ref(b -> {
            b.writeVarInt(LSSConstants.V18_COMPAT_PROTOCOL_VERSION);
            b.writeBoolean(true);
            b.writeVarInt(256);
            b.writeBoolean(true);
        });
        assertArrayEquals(expected, encode(SessionConfigS2CPayload.CODEC,
                new SessionConfigS2CPayload(LSSConstants.V18_COMPAT_PROTOCOL_VERSION,
                        true, 256, true)));
    }

    @Test
    void voxelColumnAsV18DropsExactlyTheCodecByteForEveryProducerTag() {
        // Dialect totality at the wire: whichever producer served the column — INCLUDING
        // the store (source 3, a value no v0.8.x client ever saw; it passes through
        // verbatim under the forward-safety rule) — asV18() must keep the source byte and
        // drop only the codec byte. A leaked codec byte is parsed by the v18 client as
        // the section-array length VarInt and hard-kicks it.
        byte[] sections = {9, 8, 7};
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
        byte[] sources = {LSSConstants.COLUMN_SOURCE_IN_MEMORY, LSSConstants.COLUMN_SOURCE_DISK,
                LSSConstants.COLUMN_SOURCE_GENERATION, LSSConstants.COLUMN_SOURCE_STORE, (byte) -1};
        for (byte source : sources) {
            byte[] expectedV18 = ref(b -> {
                b.writeInt(3);
                b.writeInt(-4);
                b.writeUtf("minecraft:overworld");
                b.writeLong(1234L);
                b.writeByte(source); // kept verbatim — the v18 layout HAS the source byte
                b.writeByteArray(sections);
            });
            var p = new VoxelColumnS2CPayload(3, -4, dim, 1234L, source, sections);
            assertArrayEquals(expectedV18, encode(VoxelColumnS2CPayload.CODEC, p.asV18()),
                    "source tag " + source + " must survive with only the codec byte gone");
            assertArrayEquals(sections, p.asV18().shippedSections(),
                    "asV18 must not copy or alter the section bytes");
        }
    }

    @Test
    void voxelColumnAsV18DiffersFromCurrentByExactlyTheCodecByte() {
        // The splice-position pin (mirrors Paper's rewriteColumnToV18): removing the ONE
        // byte after the source byte from the CURRENT frame yields the v18 frame.
        byte[] sections = {5, 4, 3, 2};
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:the_end"));
        var p = new VoxelColumnS2CPayload(7, -9, dim, 99L,
                LSSConstants.COLUMN_SOURCE_DISK, sections);
        byte[] current = encode(VoxelColumnS2CPayload.CODEC, p);
        byte[] v18 = encode(VoxelColumnS2CPayload.CODEC, p.asV18());
        assertEquals(current.length - 1, v18.length);
        int codecIndex = 4 + 4 + 1 + "minecraft:the_end".length() + 8 + 1; // header + utf(1-byte len) + ts + source
        byte[] spliced = new byte[current.length - 1];
        System.arraycopy(current, 0, spliced, 0, codecIndex);
        System.arraycopy(current, codecIndex + 1, spliced, codecIndex,
                current.length - codecIndex - 1);
        assertArrayEquals(spliced, v18);
    }

    @Test
    void voxelColumnAsV18KeepsTheGhostClearAndLongDimensions() {
        // The 0-section authoritative clear must survive the v18 rewrite (the v0.8.x
        // client handles it: isClearColumn + air-fill), and a >127-char dimension string
        // (2-byte VarInt UTF prefix) must not shift the layout.
        String longDim = "lsstest:" + "d".repeat(120);
        byte[] expected = ref(b -> {
            b.writeInt(0);
            b.writeInt(0);
            b.writeUtf(longDim);
            b.writeLong(42L);
            b.writeByte(LSSConstants.COLUMN_SOURCE_IN_MEMORY);
            b.writeByteArray(new byte[0]);
        });
        var dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(longDim));
        var p = new VoxelColumnS2CPayload(0, 0, dim, 42L,
                LSSConstants.COLUMN_SOURCE_IN_MEMORY, new byte[0]);
        assertArrayEquals(expected, encode(VoxelColumnS2CPayload.CODEC, p.asV18()));
    }

    // ---- Meta: the parity corpus must cover the whole v17 payload surface ----

    @Test
    void referenceFramesCoverEveryDeclaredChannel() throws IllegalAccessException {
        // A 7th payload registered in LSSNetworking needs a new CHANNEL_* constant; this
        // set-equality trips then, forcing a reference frame in BOTH WireParityTests (and
        // the Paper twin's literal list) before the suite goes green again. Also pins that
        // each payload class binds exactly one declared channel — no extras, no orphans.
        var declared = new HashSet<String>();
        for (var f : LSSConstants.class.getDeclaredFields()) {
            if (f.getName().startsWith("CHANNEL_") && f.getType() == String.class) {
                declared.add((String) f.get(null));
            }
        }
        var covered = Set.of(
                HandshakeC2SPayload.TYPE.id().toString(),
                BatchChunkRequestC2SPayload.TYPE.id().toString(),
                ClientInfoC2SPayload.TYPE.id().toString(),
                SessionConfigS2CPayload.TYPE.id().toString(),
                DirtyColumnsS2CPayload.TYPE.id().toString(),
                VoxelColumnS2CPayload.TYPE.id().toString(),
                BatchResponseS2CPayload.TYPE.id().toString(),
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.TYPE.id().toString(),
                dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload.TYPE.id().toString(),
                dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.TYPE.id().toString(),
                dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload.TYPE.id().toString(),
                dev.vox.lss.networking.payloads.RegionSummaryS2CPayload.TYPE.id().toString(),
                dev.vox.lss.networking.payloads.ColumnStampsS2CPayload.TYPE.id().toString());
        assertEquals(covered, declared,
                "every LSS channel must map to exactly one payload with a reference frame in "
                + "this suite — a new payload requires frames in BOTH WireParityTests");
        assertEquals(13, declared.size());
    }

    // ---- Region summaries (P2): the carriers add NO framing — the FriendlyByteBuf
    // ---- body IS the RegionSummaryWire byte[] verbatim, in both directions (the
    // ---- far-player pattern; parity with Paper holds by construction).

    @Test
    void regionSummaryCarriersShipTheSharedCodecBytesVerbatim() {
        var req = dev.vox.lss.common.region.RegionSummaryWire.encodeRequest(
                new dev.vox.lss.common.region.RegionSummaryWire.Request(
                        "minecraft:overworld", -3, 7, 17));
        assertArrayEquals(req, encode(
                dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload.CODEC,
                new dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload(req)),
                "the C2S carrier must add zero framing around the shared body");

        var summary = dev.vox.lss.common.region.RegionSummaryWire.encodeSummary(
                new dev.vox.lss.common.region.RegionSummaryWire.Summary(
                        "minecraft:overworld", -3, 7, 1, new long[]{
                                0L, 1_750_000_000L,
                                dev.vox.lss.common.region.RegionSummaryWire.STAMP_NEVER_CLEAN,
                                1_750_000_100L, 0L, 1_750_000_050L,
                                1_750_000_000L, 0L,
                                dev.vox.lss.common.region.RegionSummaryWire.STAMP_NEVER_CLEAN}));
        assertArrayEquals(summary, encode(
                dev.vox.lss.networking.payloads.RegionSummaryS2CPayload.CODEC,
                new dev.vox.lss.networking.payloads.RegionSummaryS2CPayload(summary)),
                "the S2C carrier must add zero framing around the shared body");
    }

    // ---- Stamped up_to_date: the carrier adds NO framing — the FriendlyByteBuf body
    // ---- IS the ColumnStampsWire byte[] verbatim (same doctrine as above).

    @Test
    void columnStampsCarrierShipsTheSharedCodecBytesVerbatim() {
        var stamps = dev.vox.lss.common.region.ColumnStampsWire.encode(
                "minecraft:overworld",
                new long[]{dev.vox.lss.common.PositionUtil.packPosition(3, -4),
                        dev.vox.lss.common.PositionUtil.packPosition(-7, 12)},
                new long[]{1_750_000_000L, 1_750_000_042L}, 2);
        assertArrayEquals(stamps, encode(
                dev.vox.lss.networking.payloads.ColumnStampsS2CPayload.CODEC,
                new dev.vox.lss.networking.payloads.ColumnStampsS2CPayload(stamps)),
                "the S2C carrier must add zero framing around the shared body");
    }

    // ---- Far players (E1): the carriers add NO framing — the FriendlyByteBuf body IS
    // ---- the FarPlayerWire byte[] verbatim, in both directions. Parity with Paper
    // ---- holds by construction (one shared codec); these frames pin the CARRIER.

    @Test
    void farPlayerCarriersShipTheSharedCodecBytesVerbatim() {
        var prefs = dev.vox.lss.common.farplayers.FarPlayerWire.encodePrefs(
                new dev.vox.lss.common.farplayers.FarPlayerWire.Prefs(true, 2048, 0, true, 512));
        assertArrayEquals(prefs, encode(
                dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload.CODEC,
                new dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload(prefs)),
                "the C2S carrier must add zero framing around the shared body");

        var roster = dev.vox.lss.common.farplayers.FarPlayerWire.encodeRoster(
                new dev.vox.lss.common.farplayers.FarPlayerWire.Roster(1, true,
                        java.util.List.of(new dev.vox.lss.common.farplayers.FarPlayerWire.RosterEntry(
                                0, new java.util.UUID(1L, 2L), "Alice")), new int[0]));
        assertArrayEquals(roster, encode(
                dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload.CODEC,
                new dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload(roster)));

        var updates = dev.vox.lss.common.farplayers.FarPlayerWire.encodeUpdates(
                new dev.vox.lss.common.farplayers.FarPlayerWire.Updates(1,
                        "minecraft:overworld", 10, java.util.List.of()));
        byte[] carried = encode(
                dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.CODEC,
                new dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload(updates));
        assertArrayEquals(updates, carried);
        // Round-trip through the carrier decode: the body must come back byte-identical.
        var buf = new io.netty.buffer.UnpooledByteBufAllocator(false).buffer();
        var fbb = new net.minecraft.network.FriendlyByteBuf(buf);
        fbb.writeBytes(carried);
        var decoded = dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload.CODEC.decode(fbb);
        assertArrayEquals(updates, decoded.body());
        buf.release();
    }
}
