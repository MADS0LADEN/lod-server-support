package dev.vox.lss.networking.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Injected-value pins for the Phase 3 pool-side reconstruction
 * ({@link NbtSectionSerializer#parseRawChunk}) — vanilla's
 * {@code createChunkInputStream} three-branch shape over raw records: every
 * compression id round-trips, unknown ids and VERSION_CUSTOM resolve authoritative
 * not-found (never NPE — {@code RegionFileVersion.fromId} returns null for unknown
 * ids), and absence stays absence. The raw FETCH half ({@code RegionFileRawRead})
 * references mixin-package accessors and refuses classloading here; its coverage is
 * {@code SerializerParityGameTests}' disk-vs-live byte parity +
 * {@code RegionFaultGameTests}' corrupt-region containment (the external {@code .mcc}
 * file branch is a documented residual gap — no real-file coverage).
 */
class RawChunkParseTest {

    // RegionFileVersion ids, pinned by value (the enum-like registry is vanilla's):
    // 1=GZIP, 2=DEFLATE (the on-disk default), 3=NONE (uncompressed), 127=CUSTOM.
    private static final byte VERSION_GZIP = 1;
    private static final byte VERSION_DEFLATE = 2;
    private static final byte VERSION_NONE = 3;
    private static final byte VERSION_LZ4 = 4;
    private static final byte VERSION_CUSTOM = 127;

    private static CompoundTag chunkTag() {
        var tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        tag.putInt("xPos", 3);
        tag.putInt("zPos", -4);
        return tag;
    }

    private static byte[] nbtBytes(CompoundTag tag) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            NbtIo.write(tag, data);
        }
        return out.toByteArray();
    }

    private static NbtSectionSerializer.RawChunkRecord record(byte[] payload, byte version) {
        return new NbtSectionSerializer.RawChunkRecord(payload, version);
    }

    /** The round-trip pins target the WRAP RECONSTRUCTION (three branches), so they run
     *  the explicit FULL parse — the Phase 4 selective projection (which would sparse
     *  this fixture's xPos/zPos away) has its own SelectiveChunkNbtLoaderTest. */
    private static net.minecraft.nbt.CompoundTag parseFull(
            java.util.Optional<NbtSectionSerializer.RawChunkRecord> rec) throws Exception {
        return NbtSectionSerializer.parseRawChunk(rec, 0, 0, false);
    }

    /** Compress through VANILLA's own writer wrapper for the id (review F8: this is
     *  what the on-disk bytes actually are — no assumption that a JDK stream matches). */
    private static byte[] vanillaCompressed(CompoundTag tag, byte versionId) throws Exception {
        var version = net.minecraft.world.level.chunk.storage.RegionFileVersion.fromId(versionId);
        var out = new ByteArrayOutputStream();
        try (var wrapped = version.wrap((java.io.OutputStream) out)) {
            wrapped.write(nbtBytes(tag));
        }
        return out.toByteArray();
    }

    @Test
    void deflateRecordRoundTrips() throws Exception {
        var tag = chunkTag();
        var parsed = parseFull(Optional.of(record(vanillaCompressed(tag, VERSION_DEFLATE), VERSION_DEFLATE)));
        assertEquals(tag, parsed, "DEFLATE (the on-disk default) must round-trip");
        // A plain JDK DeflaterOutputStream is also inflatable by vanilla's wrapper —
        // pinned so foreign-tool-written regions keep working.
        var jdk = new ByteArrayOutputStream();
        try (var deflate = new DeflaterOutputStream(jdk)) {
            deflate.write(nbtBytes(tag));
        }
        assertEquals(tag, parseFull(Optional.of(record(jdk.toByteArray(), VERSION_DEFLATE))));
    }

    @Test
    void gzipRecordRoundTrips() throws Exception {
        var tag = chunkTag();
        var parsed = parseFull(Optional.of(record(vanillaCompressed(tag, VERSION_GZIP), VERSION_GZIP)));
        assertEquals(tag, parsed);
        var jdk = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(jdk)) {
            gzip.write(nbtBytes(tag));
        }
        assertEquals(tag, parseFull(Optional.of(record(jdk.toByteArray(), VERSION_GZIP))));
    }

    @Test
    void uncompressedRecordRoundTrips() throws Exception {
        // "payload", not "compressed": VERSION_NONE payloads are raw NBT bytes.
        var tag = chunkTag();
        var parsed = parseFull(Optional.of(record(nbtBytes(tag), VERSION_NONE)));
        assertEquals(tag, parsed);
    }

    @Test
    void lz4RecordRoundTrips() throws Exception {
        // 26.2 registers VERSION_LZ4 (id 4), selectable via region-file-compression —
        // a real server can hold lz4 records the split must inflate (review F8).
        var tag = chunkTag();
        var parsed = parseFull(Optional.of(record(vanillaCompressed(tag, VERSION_LZ4), VERSION_LZ4)));
        assertEquals(tag, parsed);
    }

    @Test
    void unknownVersionIdResolvesNotFoundNeverNpe() throws Exception {
        // RegionFileVersion.fromId returns NULL for unknown ids — a naive wrap() NPEs.
        // Vanilla logs and returns null; the split must match (authoritative not-found).
        assertNull(parseFull(Optional.of(record(new byte[]{1, 2, 3}, (byte) 99))));
    }

    @Test
    void customCompressionResolvesNotFound() throws Exception {
        // VERSION_CUSTOM carries a UTF id first — vanilla reads it for the log and
        // resolves null; the split mirrors both.
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeUTF("example:zstd");
        }
        assertNull(parseFull(Optional.of(record(out.toByteArray(), VERSION_CUSTOM))));
        // An unreadable id changes nothing (the id is for the log only).
        assertNull(parseFull(Optional.of(record(new byte[0], VERSION_CUSTOM))));
    }

    @Test
    void absentRecordStaysAbsent() throws Exception {
        assertNull(parseFull(Optional.empty()));
    }
}
