package dev.vox.lss.networking.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 (R2) contract suite: the selective root-whitelist parse must be
 * OBSERVATIONALLY IDENTICAL to the full parse for everything the serializer reads —
 * pinned by tag equality on the whitelisted keys AND byte equality of the production
 * serializer's output over both parses — across shuffled root-key orderings. The
 * documented one-directional leniency divergence (a corrupt NON-section root subtree
 * full parse throws on, selective skips) is pinned POSITIVELY, and the
 * fallback-to-full path negatively (truncated payloads throw identically). The
 * whitelist-drift pin lives here too (same package as the package-private loader).
 */
class SelectiveChunkNbtLoaderTest {

    /** A realistic-shaped chunk NBT: the two consumed keys + junk LSS never reads. */
    private static CompoundTag chunkNbt() {
        var root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        var sections = new ListTag();
        var section = new CompoundTag();
        section.putByte("Y", (byte) -4);
        var blockStates = new CompoundTag();
        var palette = new ListTag();
        var stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        blockStates.put("palette", palette);
        section.put("block_states", blockStates);
        sections.add(section);
        root.put("sections", sections);
        root.putInt("DataVersion", 4189);
        root.putInt("xPos", 3);
        root.putInt("zPos", -4);
        root.putLong("LastUpdate", 123456789L);
        var heightmaps = new CompoundTag();
        heightmaps.putLongArray("WORLD_SURFACE", new long[]{1, 2, 3});
        root.put("Heightmaps", heightmaps);
        var blockEntities = new ListTag();
        var chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        blockEntities.add(chest);
        root.put("block_entities", blockEntities);
        return root;
    }

    private static byte[] nbtBytes(CompoundTag tag) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            NbtIo.write(tag, data);
        }
        return out.toByteArray();
    }

    private static CompoundTag selective(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return SelectiveChunkNbtLoader.load(in);
        }
    }

    private static CompoundTag full(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in);
        }
    }

    @Test
    void selectiveMatchesFullOnEverythingTheSerializerReads() throws Exception {
        byte[] bytes = nbtBytes(chunkNbt());
        var s = selective(bytes);
        var f = full(bytes);
        assertEquals(f.get("Status"), s.get("Status"));
        assertEquals(f.get("sections"), s.get("sections"));
        assertEquals(SelectiveChunkNbtLoader.ROOT_KEY_WHITELIST, s.keySet(),
                "the sparse tag must hold exactly the whitelisted keys");
        // Serializer byte-equality FOLLOWS from the two asserts above plus the
        // whitelist-drift pin below: serializeChunkNbt is a pure function of exactly
        // {Status, sections} (the pin proves that is all it reads), and both parses
        // agree tag-for-tag on those subtrees. The serializer's own bytes are the
        // corpus suite's job; re-proving them here would duplicate its registry rig.
    }

    /** Emit root entries MANUALLY in an exact order — CompoundTag.write iterates its
     *  HashMap, so re-inserting shuffled barely reorders the bytes (review B4-2: the
     *  original fuzz produced 4 layouts with Status always first — vacuous on the axis
     *  that matters). */
    private static byte[] bytesInOrder(CompoundTag base, List<String> order) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeByte(10);
            data.writeUTF("");
            for (String k : order) {
                var t = base.get(k);
                data.writeByte(t.getId());
                data.writeUTF(k);
                t.write(data);   // payload only — the vanilla per-entry layout
            }
            data.writeByte(0);
        }
        return out.toByteArray();
    }

    @Test
    void rootKeyOrderingNeverChangesTheOutcome() throws Exception {
        var base = chunkNbt();
        var keys = new ArrayList<>(base.keySet());
        var rng = new Random(42);
        var expectedStatus = base.get("Status");
        var expectedSections = base.get("sections");
        for (int round = 0; round < 24; round++) {
            Collections.shuffle(keys, rng);
            byte[] bytes = bytesInOrder(base, keys);
            var s = selective(bytes);
            var f = full(bytes);
            assertEquals(expectedStatus, s.get("Status"), "round " + round + " order " + keys);
            assertEquals(expectedSections, s.get("sections"), "round " + round);
            assertEquals(f.get("Status"), s.get("Status"));
            assertEquals(f.get("sections"), s.get("sections"));
            assertEquals(SelectiveChunkNbtLoader.ROOT_KEY_WHITELIST, s.keySet());
        }
        // Directed extremes the shuffle may not hit: sections FIRST / Status LAST, and
        // both whitelisted keys adjacent after a skipped array-carrying subtree.
        var directed = new ArrayList<>(keys);
        directed.remove("sections");
        directed.remove("Status");
        directed.add(0, "sections");
        directed.add("Status");
        byte[] bytes = bytesInOrder(base, directed);
        var s = selective(bytes);
        assertEquals(expectedStatus, s.get("Status"));
        assertEquals(expectedSections, s.get("sections"));
    }

    /**
     * The DOCUMENTED one-directional divergence, pinned positively: a root string with
     * invalid modified-UTF8 payload — full parse's readUTF validates and THROWS, the
     * selective skip (length + skipBytes, no validation) sails past and the column
     * serves off its intact sections. More lenient by design (full parse would condemn
     * a servable column to the error→generation ladder over data LSS never reads).
     */
    @Test
    void corruptNonSectionSubtreeSkipsWhereFullParseThrows() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeByte(10);          // root compound
            data.writeUTF("");           // root name
            data.writeByte(8);           // TAG_String
            data.writeUTF("junk");
            data.writeShort(2);          // declared string length 2...
            data.write(0xC0);            // ...invalid modified-UTF8 sequence
            data.write(0x00);
            data.writeByte(8);           // a whitelisted key after the corruption
            data.writeUTF("Status");
            data.writeUTF("minecraft:full");
            data.writeByte(0);           // end
        }
        byte[] bytes = out.toByteArray();
        assertThrows(Exception.class, () -> full(bytes),
                "precondition: full parse must reject the invalid UTF payload");
        var s = selective(bytes);
        assertEquals(StringTag.valueOf("minecraft:full"), s.get("Status"),
                "selective parse skips the corrupt junk and still reads Status");
    }

    @Test
    void truncatedPayloadThrowsIdenticallyAndTheFallbackStaysOneDirectional() throws Exception {
        byte[] bytes = nbtBytes(chunkNbt());
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        assertThrows(Exception.class, () -> selective(truncated));
        assertThrows(Exception.class, () -> full(truncated));
        // Through parseRawChunk (VERSION_NONE = raw payload): the selective throw falls
        // back to full parse, which also throws -> the IOException reaches the normal
        // triage; the record path never invents a tag from truncated bytes.
        assertThrows(Exception.class, () -> NbtSectionSerializer.parseRawChunk(
                Optional.of(new NbtSectionSerializer.RawChunkRecord(truncated, (byte) 3)),
                0, 0, true));
    }

    @Test
    void parseRawChunkParsesTheDivergentPayloadToAnEmptySparseTagWithTheFlagOffPin() throws Exception {
        // The corrupt-junk payload through the production entry point with the flag ON:
        // the selective parse serves it (no fallback needed). With the flag OFF the full
        // parse throws — the exact pre-Phase-4 behavior, preserved behind the rollback.
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeByte(10);
            data.writeUTF("");
            data.writeByte(8);
            data.writeUTF("junk");
            data.writeShort(2);
            data.write(0xC0);
            data.write(0x00);
            data.writeByte(0);
        }
        byte[] bytes = out.toByteArray();
        var viaSelective = NbtSectionSerializer.parseRawChunk(
                Optional.of(new NbtSectionSerializer.RawChunkRecord(bytes, (byte) 3)), 0, 0, true);
        assertTrue(viaSelective != null && viaSelective.isEmpty(),
                "selective path parses the divergent payload to an empty sparse tag");
        assertThrows(Exception.class, () -> NbtSectionSerializer.parseRawChunk(
                Optional.of(new NbtSectionSerializer.RawChunkRecord(bytes, (byte) 3)), 0, 0, false),
                "flag OFF restores the full parse's strictness (the rollback)");
    }

    /** B4-9: the selective path over a COMPRESSED wrap (the wrap-ladder round-trips
     *  moved to full parse; Tier 1 must still drive selective through DEFLATE), with
     *  corrupt junk present — the whole Phase 4 shape in one compressed record. */
    @Test
    void selectiveParsesCorruptJunkOverDeflateThroughParseRawChunk() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeByte(10);
            data.writeUTF("");
            data.writeByte(8);
            data.writeUTF("junk");
            data.writeShort(2);
            data.write(0xC0);
            data.write(0x00);
            data.writeByte(8);
            data.writeUTF("Status");
            data.writeUTF("minecraft:full");
            data.writeByte(0);
        }
        var deflated = new ByteArrayOutputStream();
        try (var wrapped = net.minecraft.world.level.chunk.storage.RegionFileVersion
                .fromId(2).wrap((java.io.OutputStream) deflated)) {
            wrapped.write(out.toByteArray());
        }
        var parsed = NbtSectionSerializer.parseRawChunk(Optional.of(
                new NbtSectionSerializer.RawChunkRecord(deflated.toByteArray(), (byte) 2)),
                0, 0, true);
        assertEquals(StringTag.valueOf("minecraft:full"), parsed.get("Status"));
    }

    /** B4-1's exhaustion check makes trailing bytes a selective THROW while full parse
     *  ignores them — the one payload family where the fallback SUCCEEDS, pinned
     *  positively (the fallback path returns the full tag). */
    @Test
    void trailingBytesRouteThroughTheFallbackWhichSucceeds() throws Exception {
        byte[] valid = nbtBytes(chunkNbt());
        byte[] withTrailing = java.util.Arrays.copyOf(valid, valid.length + 4);
        withTrailing[valid.length] = 42;
        assertThrows(IOException.class, () -> selective(withTrailing),
                "trailing bytes must throw (the B4-1 desync guard)");
        var viaParseRawChunk = NbtSectionSerializer.parseRawChunk(Optional.of(
                new NbtSectionSerializer.RawChunkRecord(withTrailing, (byte) 3)), 0, 0, true);
        assertEquals(full(valid), viaParseRawChunk,
                "the fallback full parse serves the tag (vanilla ignores trailing bytes)");
    }

    /** B4-1 directly: a negative array length that skipBytes silently ignores — the
     *  desynced loop must END IN A THROW (exhaustion or invalid id), never return a
     *  garbage-shaped sparse tag without throwing. */
    @Test
    void corruptArrayLengthNeverYieldsASilentGarbageTag() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(out)) {
            data.writeByte(10);
            data.writeUTF("");
            data.writeByte(7);            // TAG_Byte_Array (skipped key)
            data.writeUTF("junkArr");
            data.writeInt(-8);            // negative length: skip consumes NOTHING
            data.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});  // the "array" bytes
            data.writeByte(8);
            data.writeUTF("Status");
            data.writeUTF("minecraft:full");
            data.writeByte(0);
        }
        byte[] bytes = out.toByteArray();
        assertThrows(Exception.class, () -> selective(bytes),
                "a skip-desynced stream must throw, never silently mis-shape the tag");
    }

    @Test
    void nonCompoundRootThrowsVanillasMessage() {
        assertThrows(IOException.class, () -> selective(new byte[]{1, 0, 0, 0}),
                "a non-compound root must throw, mirroring NbtIo.read");
    }

    /**
     * The whitelist-drift pin (plan): every root-level chunkNbt accessor in the
     * serializer must name a whitelisted key — a new root getter without a whitelist
     * update would silently read an absent key on the sparse tag. Same package as the
     * loader (the whitelist is package-private).
     */
    @Test
    void whitelistCoversEverySerializerRootAccessor() throws Exception {
        Path src = dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/NbtSectionSerializer.java");
        String serializer = Files.readString(src);
        var accessor = Pattern.compile("chunkNbt\\.(?:get|contains)\\w*\\(\"([^\"]+)\"");
        var found = new java.util.HashSet<String>();
        var m = accessor.matcher(serializer);
        while (m.find()) {
            found.add(m.group(1));
        }
        assertFalse(found.isEmpty(), "the regex must find the serializer's root accessors");
        assertTrue(SelectiveChunkNbtLoader.ROOT_KEY_WHITELIST.containsAll(found),
                "serializer root keys " + found + " must all be whitelisted");
        // Hardening (review B4-6): the regex only sees `chunkNbt.<accessor>("...")` —
        // an alias (`var c = chunkNbt`) would evade it entirely. Pin the evasion shape.
        assertFalse(Pattern.compile("=\\s*chunkNbt\\s*;").matcher(serializer).find(),
                "chunkNbt must never be re-bound to an alias (whitelist-pin evasion)");
    }

    @Test
    void absentRecordStillAbsentWithSelectiveOn() throws Exception {
        assertNull(NbtSectionSerializer.parseRawChunk(Optional.empty(), 0, 0, true));
    }
}
