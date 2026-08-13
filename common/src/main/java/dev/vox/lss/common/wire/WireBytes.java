package dev.vox.lss.common.wire;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minecraft-wire-format primitives over plain {@code byte[]} — the {@code common/}
 * module has no Minecraft dependency, so the VarInt and big-endian scalar codecs are
 * hand-rolled here to be BIT-IDENTICAL to {@code FriendlyByteBuf}'s
 * ({@code writeVarInt}/{@code readVarInt}, netty big-endian shorts/longs, and the
 * {@code writeUtf} shape of VarInt byte-length + UTF-8). Equivalence is pinned by a
 * Fabric-side parity test against the real {@code FriendlyByteBuf}, which common
 * cannot reference.
 *
 * <p>{@link Reader} is a bounds-checked cursor: every violation throws
 * {@link WireFormatException}; it never throws {@code ArrayIndexOutOfBoundsException}
 * and never allocates from an unvalidated wire-claimed count (callers bound-check
 * counts against {@link Reader#remaining()} before sizing anything).
 * {@link Writer} is a growable buffer with the matching write shapes.
 */
public final class WireBytes {
    private WireBytes() {}

    /** Maximum encoded VarInt width (Minecraft rejects a 6th continuation byte). */
    public static final int MAX_VARINT_BYTES = 5;

    /** Encoded width of {@code value} as a Minecraft VarInt (1-5 bytes; negatives are 5). */
    public static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    public static final class Reader {
        private final byte[] buf;
        private int pos;
        private final int limit;

        public Reader(byte[] buf) {
            this(buf, 0, buf.length);
        }

        public Reader(byte[] buf, int offset, int limit) {
            if (offset < 0 || limit > buf.length || offset > limit) {
                throw new WireFormatException("reader window [" + offset + ", " + limit
                        + ") outside buffer of " + buf.length);
            }
            this.buf = buf;
            this.pos = offset;
            this.limit = limit;
        }

        public int position() {
            return pos;
        }

        public int remaining() {
            return limit - pos;
        }

        private void need(int n, String what) {
            if (limit - pos < n) {
                throw new WireFormatException("truncated " + what + ": need " + n
                        + " bytes, have " + (limit - pos));
            }
        }

        public int readByte() {
            need(1, "byte");
            return buf[pos++];
        }

        public int readUnsignedByte() {
            return readByte() & 0xFF;
        }

        /** Big-endian signed short, the netty {@code readShort} shape. */
        public int readShort() {
            need(2, "short");
            int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            return (short) v;
        }

        /** Big-endian signed int, the netty {@code readInt} shape. */
        public int readInt() {
            need(4, "int");
            int v = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
                    | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        /** Big-endian long, the netty {@code readLong} shape. */
        public long readLong() {
            need(8, "long");
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (buf[pos + i] & 0xFFL);
            }
            pos += 8;
            return v;
        }

        /** Minecraft VarInt: 7-bit groups little-endian-first, at most 5 bytes. */
        public int readVarInt() {
            int value = 0;
            for (int i = 0; i < MAX_VARINT_BYTES; i++) {
                need(1, "VarInt");
                int b = buf[pos++] & 0xFF;
                value |= (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new WireFormatException("VarInt wider than " + MAX_VARINT_BYTES + " bytes");
        }

        /** A VarInt that must be non-negative (counts, lengths). */
        public int readVarIntCount(String what) {
            int v = readVarInt();
            if (v < 0) {
                throw new WireFormatException("negative " + what + ": " + v);
            }
            return v;
        }

        public byte[] readBytes(int n) {
            need(n, n + " raw bytes");
            byte[] out = Arrays.copyOfRange(buf, pos, pos + n);
            pos += n;
            return out;
        }

        public void skip(int n, String what) {
            need(n, what);
            pos += n;
        }

        /** VarInt byte-length + UTF-8 bytes — the {@code writeUtf} shape. */
        public String readUtf(int maxBytes) {
            int len = readVarIntCount("string length");
            if (len > maxBytes) {
                throw new WireFormatException("string of " + len + " bytes exceeds cap " + maxBytes);
            }
            need(len, "string body");
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }
    }

    public static final class Writer {
        private byte[] buf;
        private int pos;

        public Writer(int initialCapacity) {
            this.buf = new byte[Math.max(16, initialCapacity)];
        }

        private void ensure(int n) {
            if (pos + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + n));
            }
        }

        public int size() {
            return pos;
        }

        public Writer writeByte(int v) {
            ensure(1);
            buf[pos++] = (byte) v;
            return this;
        }

        public Writer writeShort(int v) {
            ensure(2);
            buf[pos++] = (byte) (v >>> 8);
            buf[pos++] = (byte) v;
            return this;
        }

        public Writer writeInt(int v) {
            ensure(4);
            buf[pos++] = (byte) (v >>> 24);
            buf[pos++] = (byte) (v >>> 16);
            buf[pos++] = (byte) (v >>> 8);
            buf[pos++] = (byte) v;
            return this;
        }

        public Writer writeLong(long v) {
            ensure(8);
            for (int i = 7; i >= 0; i--) {
                buf[pos++] = (byte) (v >>> (8 * i));
            }
            return this;
        }

        public Writer writeVarInt(int value) {
            ensure(MAX_VARINT_BYTES);
            while ((value & ~0x7F) != 0) {
                buf[pos++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            buf[pos++] = (byte) value;
            return this;
        }

        public Writer writeBytes(byte[] src) {
            ensure(src.length);
            System.arraycopy(src, 0, buf, pos, src.length);
            pos += src.length;
            return this;
        }

        /** VarInt byte-length + UTF-8 bytes — the {@code writeUtf} shape. */
        public Writer writeUtf(String s) {
            byte[] body = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(body.length);
            return writeBytes(body);
        }

        public byte[] toByteArray() {
            return Arrays.copyOf(buf, pos);
        }
    }
}
