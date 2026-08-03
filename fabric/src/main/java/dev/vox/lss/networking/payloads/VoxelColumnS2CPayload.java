package dev.vox.lss.networking.payloads;

import dev.vox.lss.common.LSSConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server-to-client payload carrying MC-native chunk section data.
 * <p>
 * Since protocol 19 the section bytes carry a one-byte codec tag
 * ({@code COLUMN_CODEC_RAW}/{@code COLUMN_CODEC_ZSTD} — compressed-columns-design.md):
 * codec 1 ships a zstd-1 frame of the raw section bytes, decompressed off-thread by
 * {@code ClientColumnProcessor}; codec 0 ships raw bytes as before (Minecraft's
 * connection zlib still wraps either transparently). Unknown codec values are accepted
 * at decode (the section array is length-prefixed, so alignment is safe) and rejected
 * at the drain through the ingest-failure path — NOT the source tag's pass-through
 * rule, because the codec byte changes how the section bytes must be read.
 * <p>
 * Dimension is encoded as a length-capped UTF resource-location string (v16+).
 */
public final class VoxelColumnS2CPayload implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<VoxelColumnS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.parse(LSSConstants.CHANNEL_VOXEL_COLUMN));

    public static final StreamCodec<FriendlyByteBuf, VoxelColumnS2CPayload> CODEC =
            StreamCodec.of(
                    VoxelColumnS2CPayload::write,
                    VoxelColumnS2CPayload::read
            );

    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte source;
    private final byte codec;
    /** The SHIPPED section bytes: raw for codec 0, a zstd-1 frame for codec 1. */
    private final byte[] sectionBytes;
    /**
     * The raw (uncompressed) section byte size this payload accounts for. Server-side:
     * the builder's known raw length. Client-side (memoized at {@link #read} — it is
     * consulted at least twice, on the netty thread via {@link #estimatedBytes} and at
     * the decode-queue offer): {@code max(shipped, clamp(declared, 0, MAX_SECTIONS_SIZE))}
     * — the hostile-retention charge rule (plan §0.5): a lying tiny/negative frame header
     * can never under-charge below resident bytes nor drive a gauge negative, while
     * honest frames keep the raw-work denomination the pressure gates are calibrated in.
     */
    private final int rawSize;
    /** v16 compat: encode the pre-18 layout WITHOUT the source and codec bytes.
     *  Server-encode-only — {@link #read} never produces it — and set exclusively by
     *  {@link #asV16()} at the per-player column send seam, whose egress guard drops any
     *  non-RAW payload first (a v18/v19-shaped or zstd-framed column reaching a v16
     *  client hard-kicks it). */
    private final boolean v16Wire;

    /** Source-less convenience (tests/legacy rigs): tags the column with source -1
     *  ("unknown"), a legal wire value the client passes through verbatim. Production
     *  always uses the full constructor with a COLUMN_SOURCE_* tag. */
    public VoxelColumnS2CPayload(int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte[] sectionBytes) {
        this(chunkX, chunkZ, dimension, columnTimestamp, (byte) -1, sectionBytes);
    }

    /** Raw-shipping constructor (codec 0): rawSize == sectionBytes.length. Null bytes
     *  (a defensive-path rig shape the drain reports as ingest failure) size as 0. */
    public VoxelColumnS2CPayload(int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte source, byte[] sectionBytes) {
        this(chunkX, chunkZ, dimension, columnTimestamp, source,
                LSSConstants.COLUMN_CODEC_RAW, sectionBytes,
                sectionBytes == null ? 0 : sectionBytes.length, false);
    }

    /** Full server-build constructor: {@code sectionBytes} are the SHIPPED bytes for the
     *  given codec; {@code rawSize} is the raw section byte length they decode to. */
    public VoxelColumnS2CPayload(int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte source, byte codec, byte[] sectionBytes, int rawSize) {
        this(chunkX, chunkZ, dimension, columnTimestamp, source, codec, sectionBytes,
                rawSize, false);
    }

    private VoxelColumnS2CPayload(int chunkX, int chunkZ,
                                  ResourceKey<Level> dimension, long columnTimestamp,
                                  byte source, byte codec, byte[] sectionBytes, int rawSize,
                                  boolean v16Wire) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.source = source;
        this.codec = codec;
        this.sectionBytes = sectionBytes;
        this.rawSize = rawSize;
        this.v16Wire = v16Wire;
    }

    /** The v16 compat copy: same column, legacy source-less/codec-less wire layout.
     *  Section bytes are shared, not copied — the payload is immutable after
     *  construction. PRECONDITION (enforced by the egress guard, not here): codec is
     *  {@code COLUMN_CODEC_RAW} — the legacy layout has nowhere to carry a codec, so a
     *  framed payload must be dropped at the seam, never converted. */
    public VoxelColumnS2CPayload asV16() {
        return new VoxelColumnS2CPayload(this.chunkX, this.chunkZ, this.dimension,
                this.columnTimestamp, this.source, this.codec, this.sectionBytes,
                this.rawSize, true);
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public ResourceKey<Level> dimension() { return dimension; }
    public long columnTimestamp() { return columnTimestamp; }

    /** Serve-source tag (COLUMN_SOURCE_*): where the server got this column. Diagnostic
     *  attribution only — unknown values pass through untouched (forward-safe). */
    public byte source() { return source; }

    /** Codec tag (COLUMN_CODEC_*): how {@link #shippedSections()} must be read. Values
     *  outside {0,1} are rejected at the decode drain (ingest-failure), never here. */
    public byte codec() { return codec; }

    /** The shipped section bytes: raw for codec 0, a zstd-1 frame for codec 1. */
    public byte[] shippedSections() { return sectionBytes; }

    /** Raw section byte size (see the field doc for the client-side charge rule). */
    public int rawSize() { return rawSize; }

    /** RAW-denominated size estimate — the bandwidth/queue-work charge (design §5 keeps
     *  raw semantics regardless of the shipped codec). */
    public int estimatedBytes() {
        return rawSize + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    /** Shipped-size estimate — the {@code wire_bytes} counter's input. Null-tolerant
     *  like its raw sibling (the null-bytes rig shape the raw ctor documents). */
    public int wireEstimatedBytes() {
        return (sectionBytes == null ? 0 : sectionBytes.length)
                + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    private static void write(FriendlyByteBuf buf, VoxelColumnS2CPayload payload) {
        buf.writeInt(payload.chunkX);
        buf.writeInt(payload.chunkZ);
        buf.writeUtf(payload.dimension.identifier().toString(), LSSConstants.MAX_DIMENSION_STRING_LENGTH);
        buf.writeLong(payload.columnTimestamp);
        if (!payload.v16Wire) {
            buf.writeByte(payload.source);
            buf.writeByte(payload.codec);
        }
        buf.writeByteArray(payload.sectionBytes);
    }

    private static VoxelColumnS2CPayload read(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int cz = buf.readInt();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(buf.readUtf(LSSConstants.MAX_DIMENSION_STRING_LENGTH)));
        long columnTimestamp = buf.readLong();
        // Client v16 backward compat: a protocol-16 server's column frame has NO source
        // byte (added in protocol 18) and NO codec byte (added in protocol 19). The frame
        // carries no version marker, so the flag set by the preceding SessionConfig decode
        // (same netty thread, frame order) is the only signal; default source to -1
        // ("unknown", passed through verbatim) and codec to RAW. When the flag is false —
        // every current session, and every client that never announced 16 — this reads
        // both bytes exactly as the v19 layout defines.
        boolean sourceless = V16ClientWire.isColumnSourceless();
        byte source = sourceless ? (byte) -1 : buf.readByte();
        byte codec = sourceless ? LSSConstants.COLUMN_CODEC_RAW : buf.readByte();
        byte[] sectionBytes = buf.readByteArray(LSSConstants.MAX_SECTIONS_SIZE);

        // Memoize the raw-size charge at read (plan §0.5): shipped length for raw/unknown
        // codecs; for codec 1 the frame's declared content size under the
        // max(shipped, clamp(declared, 0, MAX)) rule — hostile headers can never
        // under-charge below resident bytes, negative/undeclared reads as shipped.
        int rawSize = sectionBytes.length;
        if (codec == LSSConstants.COLUMN_CODEC_ZSTD) {
            long declared = ZstdWireSupport.declaredContentSize(sectionBytes);
            long clamped = Math.min(declared, LSSConstants.MAX_SECTIONS_SIZE);
            rawSize = (int) Math.max(sectionBytes.length, clamped);
        }

        return new VoxelColumnS2CPayload(cx, cz, dim, columnTimestamp, source, codec,
                sectionBytes, rawSize, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
