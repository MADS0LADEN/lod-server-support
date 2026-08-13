package dev.vox.lss.common.farplayers;

import dev.vox.lss.common.wire.WireBytes;
import dev.vox.lss.common.wire.WireFormatException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import java.util.UUID;

/**
 * The far-player wire codec (v0.11.0 stage E1 — far-player-proxies-plan.md §3.1 as
 * amended by the mega plan's R-7/R-10). BOTH platforms encode/decode through these
 * byte[] bodies verbatim — Fabric wraps them in a StreamCodec, Paper in the plugin
 * message frame — so wire parity holds by construction (one codec, two carriers).
 *
 * <p>MC-VERSION-NEUTRAL by design (R-7): equipment and vehicle types cross the wire as
 * IDENTITY STRINGS via a per-payload first-seen dictionary (the v20 column pattern),
 * never numeric registry ids. Self-contained per payload — no cross-payload dictionary
 * state, so a dropped frame can never poison later decodes.
 *
 * <p>Quantization: positions are fixed-point 1/16 block int32 (±134M blocks — beyond
 * the ±30M border), angles are 1/256-turn bytes (~1.4°), velocities are blocks/s × 256
 * shorts clamped ±64 m/s (elytra ≈ 40). Sub-block precision is invisible at proxy
 * distances.
 *
 * <p>Layouts (all counts VarInt, all strings VarInt-UTF):
 * <pre>
 * prefs   := enabled:byte maxDist:varint minDist:varint shareSelf:byte shareDist:varint
 * roster  := epoch:varint full:byte addCount added* leaveCount leaveIndex:varint*
 * added   := index:varint uuidMsb:long uuidLsb:long name:utf
 * updates := epoch:varint dimension:utf cadenceTicks:varint dictCount dictEntry:utf*
 *            count entry*
 * entry   := index:varint presence:byte x:int y:int z:int yaw:byte headYaw:byte
 *            pitch:byte poseFlags:byte vx:short vy:short vz:short
 *            [equipment: 6 x (slot:varint(dictIdx+1, 0=empty) [count:varint])]
 *            [vehicle: typeDictIdx:varint uuidMsb:long uuidLsb:long
 *             x:int y:int z:int yaw:byte pitch:byte]
 * </pre>
 * The vehicle block (R-10 v1.3): type identity + UUID + pos/yaw/pitch — deliberately
 * NO seat index (the rider renders at its OWN wire position; the server-side seated
 * position already encodes the seat offset).
 */
public final class FarPlayerWire {

    // Decode bounds — a hostile frame must cost bounded allocation, never OOM.
    public static final int MAX_ROSTER_ENTRIES = 1024;
    public static final int MAX_UPDATE_ENTRIES = 1024;
    public static final int MAX_DICT_ENTRIES = 512;
    public static final int MAX_IDENTITY_UTF_BYTES = 256;
    public static final int MAX_NAME_UTF_BYTES = 64;
    public static final int MAX_DIMENSION_UTF_BYTES = 256;

    public static final int EQUIPMENT_SLOTS = 6;

    // Entry presence bits
    public static final int PRESENCE_EQUIPMENT = 1;
    public static final int PRESENCE_VEHICLE = 2;

    public static final double POS_SCALE = 16.0;
    public static final double VEL_SCALE = 256.0;
    public static final double MAX_VELOCITY_BLOCKS_PER_SECOND = 64.0;

    /** C2S prefs — the SeeU hello fields minus the version (LSS's handshake owns
     *  versioning). Distances in blocks. */
    public record Prefs(boolean enabled, int maxDistanceBlocks, int minDistanceBlocks,
                        boolean shareSelf, int shareDistanceBlocks) {}

    /** One roster binding: a compact per-epoch index for a (uuid, name) identity. */
    public record RosterEntry(int index, UUID uuid, String name) {}

    /** S2C roster frame: {@code full} replaces the client's whole roster (subscribe,
     *  re-handshake, dimension change, prefs receipt — R-3); incremental frames carry
     *  joins in {@code added} and leaves in {@code removedIndices}. Index reuse is only
     *  valid within an epoch. */
    public record Roster(int epoch, boolean full, List<RosterEntry> added, int[] removedIndices) {}

    /** R-10 vehicle block: the player's DIRECT mount (stacks collapse; a player-vehicle
     *  is dropped to unmounted server-side, never recursed). */
    public record Vehicle(String typeIdentity, UUID uuid,
                          int quantX, int quantY, int quantZ, byte yaw, byte pitch) {}

    /** One per-player update. {@code equipmentDictIdxPlus1}/{@code equipmentCounts} are
     *  null when equipment is omitted (hash unchanged — the common case); a present
     *  array has exactly {@link #EQUIPMENT_SLOTS} entries, 0 = empty slot. */
    public record UpdateEntry(int rosterIndex,
                              int quantX, int quantY, int quantZ,
                              byte yaw, byte headYaw, byte pitch,
                              byte poseFlags,
                              short velX, short velY, short velZ,
                              int[] equipmentDictIdxPlus1, int[] equipmentCounts,
                              String[] equipmentIdentities,
                              Vehicle vehicle) {}

    /** S2C updates frame. {@code cadenceTicks} is the server-declared nominal interval
     *  for this viewer's tier — the client's interpolation window derives from it
     *  (never from a measured gap; delta suppression makes measured gaps unbounded). */
    public record Updates(int epoch, String dimension, int cadenceTicks, List<UpdateEntry> entries) {}

    // ---- prefs ----

    public static byte[] encodePrefs(Prefs p) {
        var w = new WireBytes.Writer(16);
        w.writeByte(p.enabled() ? 1 : 0);
        w.writeVarInt(p.maxDistanceBlocks());
        w.writeVarInt(p.minDistanceBlocks());
        w.writeByte(p.shareSelf() ? 1 : 0);
        w.writeVarInt(p.shareDistanceBlocks());
        return w.toByteArray();
    }

    public static Prefs decodePrefs(byte[] body) {
        var r = new WireBytes.Reader(body);
        var p = new Prefs(r.readByte() != 0, nonNegative(r.readVarInt(), "maxDistance"),
                nonNegative(r.readVarInt(), "minDistance"), r.readByte() != 0,
                nonNegative(r.readVarInt(), "shareDistance"));
        requireDrained(r, "prefs");
        return p;
    }

    // ---- roster ----

    public static byte[] encodeRoster(Roster roster) {
        var w = new WireBytes.Writer(32 + roster.added().size() * 32);
        w.writeVarInt(roster.epoch());
        w.writeByte(roster.full() ? 1 : 0);
        w.writeVarInt(roster.added().size());
        for (var e : roster.added()) {
            w.writeVarInt(e.index());
            w.writeLong(e.uuid().getMostSignificantBits());
            w.writeLong(e.uuid().getLeastSignificantBits());
            w.writeUtf(e.name());
        }
        w.writeVarInt(roster.removedIndices().length);
        for (int idx : roster.removedIndices()) {
            w.writeVarInt(idx);
        }
        return w.toByteArray();
    }

    public static Roster decodeRoster(byte[] body) {
        var r = new WireBytes.Reader(body);
        int epoch = nonNegative(r.readVarInt(), "epoch");
        boolean full = r.readByte() != 0;
        int addCount = boundedCount(r.readVarInt(), MAX_ROSTER_ENTRIES, "roster adds");
        var added = new ArrayList<RosterEntry>(addCount);
        for (int i = 0; i < addCount; i++) {
            int index = nonNegative(r.readVarInt(), "roster index");
            var uuid = new UUID(r.readLong(), r.readLong());
            added.add(new RosterEntry(index, uuid, r.readUtf(MAX_NAME_UTF_BYTES)));
        }
        int removeCount = boundedCount(r.readVarInt(), MAX_ROSTER_ENTRIES, "roster removes");
        int[] removed = new int[removeCount];
        for (int i = 0; i < removeCount; i++) {
            removed[i] = nonNegative(r.readVarInt(), "removed index");
        }
        requireDrained(r, "roster");
        return new Roster(epoch, full, added, removed);
    }

    // ---- updates ----

    public static byte[] encodeUpdates(Updates updates) {
        var w = new WireBytes.Writer(64 + updates.entries().size() * 32);
        w.writeVarInt(updates.epoch());
        w.writeUtf(updates.dimension());
        w.writeVarInt(updates.cadenceTicks());

        // Per-payload first-seen dictionary (the v20 pattern): collect every identity
        // string this frame references, in deterministic first-reference order.
        var dict = new LinkedHashMap<String, Integer>();
        for (var e : updates.entries()) {
            if (e.equipmentIdentities() != null) {
                for (String id : e.equipmentIdentities()) {
                    if (id != null) dict.computeIfAbsent(id, k -> dict.size());
                }
            }
            if (e.vehicle() != null) {
                dict.computeIfAbsent(e.vehicle().typeIdentity(), k -> dict.size());
            }
        }
        if (dict.size() > MAX_DICT_ENTRIES) {
            throw new WireFormatException("updates dictionary " + dict.size()
                    + " exceeds " + MAX_DICT_ENTRIES);
        }
        w.writeVarInt(dict.size());
        for (String id : dict.keySet()) {
            w.writeUtf(id);
        }

        w.writeVarInt(updates.entries().size());
        for (var e : updates.entries()) {
            w.writeVarInt(e.rosterIndex());
            int presence = (e.equipmentIdentities() != null ? PRESENCE_EQUIPMENT : 0)
                    | (e.vehicle() != null ? PRESENCE_VEHICLE : 0);
            w.writeByte(presence);
            w.writeInt(e.quantX());
            w.writeInt(e.quantY());
            w.writeInt(e.quantZ());
            w.writeByte(e.yaw());
            w.writeByte(e.headYaw());
            w.writeByte(e.pitch());
            w.writeByte(e.poseFlags());
            w.writeShort(e.velX());
            w.writeShort(e.velY());
            w.writeShort(e.velZ());
            if (e.equipmentIdentities() != null) {
                for (int slot = 0; slot < EQUIPMENT_SLOTS; slot++) {
                    String id = e.equipmentIdentities()[slot];
                    if (id == null) {
                        w.writeVarInt(0);
                    } else {
                        w.writeVarInt(dict.get(id) + 1);
                        w.writeVarInt(e.equipmentCounts()[slot]);
                    }
                }
            }
            if (e.vehicle() != null) {
                var v = e.vehicle();
                w.writeVarInt(dict.get(v.typeIdentity()));
                w.writeLong(v.uuid().getMostSignificantBits());
                w.writeLong(v.uuid().getLeastSignificantBits());
                w.writeInt(v.quantX());
                w.writeInt(v.quantY());
                w.writeInt(v.quantZ());
                w.writeByte(v.yaw());
                w.writeByte(v.pitch());
            }
        }
        return w.toByteArray();
    }

    public static Updates decodeUpdates(byte[] body) {
        var r = new WireBytes.Reader(body);
        int epoch = nonNegative(r.readVarInt(), "epoch");
        String dimension = r.readUtf(MAX_DIMENSION_UTF_BYTES);
        int cadence = nonNegative(r.readVarInt(), "cadence");
        int dictCount = boundedCount(r.readVarInt(), MAX_DICT_ENTRIES, "dictionary");
        String[] dict = new String[dictCount];
        for (int i = 0; i < dictCount; i++) {
            dict[i] = r.readUtf(MAX_IDENTITY_UTF_BYTES);
        }
        int count = boundedCount(r.readVarInt(), MAX_UPDATE_ENTRIES, "updates");
        var entries = new ArrayList<UpdateEntry>(count);
        for (int i = 0; i < count; i++) {
            int index = nonNegative(r.readVarInt(), "update index");
            int presence = r.readUnsignedByte();
            int x = r.readInt();
            int y = r.readInt();
            int z = r.readInt();
            byte yaw = (byte) r.readByte();
            byte headYaw = (byte) r.readByte();
            byte pitch = (byte) r.readByte();
            byte pose = (byte) r.readByte();
            short vx = (short) r.readShort();
            short vy = (short) r.readShort();
            short vz = (short) r.readShort();
            int[] eqIdx = null;
            int[] eqCounts = null;
            String[] eqIds = null;
            if ((presence & PRESENCE_EQUIPMENT) != 0) {
                eqIdx = new int[EQUIPMENT_SLOTS];
                eqCounts = new int[EQUIPMENT_SLOTS];
                eqIds = new String[EQUIPMENT_SLOTS];
                for (int slot = 0; slot < EQUIPMENT_SLOTS; slot++) {
                    int idxPlus1 = nonNegative(r.readVarInt(), "equipment index");
                    eqIdx[slot] = idxPlus1;
                    if (idxPlus1 != 0) {
                        eqIds[slot] = dictEntry(dict, idxPlus1 - 1, "equipment");
                        eqCounts[slot] = nonNegative(r.readVarInt(), "equipment count");
                    }
                }
            }
            Vehicle vehicle = null;
            if ((presence & PRESENCE_VEHICLE) != 0) {
                String type = dictEntry(dict, nonNegative(r.readVarInt(), "vehicle type"), "vehicle");
                var uuid = new UUID(r.readLong(), r.readLong());
                vehicle = new Vehicle(type, uuid, r.readInt(), r.readInt(), r.readInt(),
                        (byte) r.readByte(), (byte) r.readByte());
            }
            entries.add(new UpdateEntry(index, x, y, z, yaw, headYaw, pitch, pose,
                    vx, vy, vz, eqIdx, eqCounts, eqIds, vehicle));
        }
        requireDrained(r, "updates");
        return new Updates(epoch, dimension, cadence, entries);
    }

    // ---- pose flag bits (UpdateEntry.poseFlags — shared by the snapshot builders
    //      and the E2 renderer; the SeeU trio) ----

    public static final byte POSE_SNEAK = 1;
    public static final byte POSE_GLIDE = 2;
    public static final byte POSE_SWIM = 4;

    // ---- quantization helpers ----

    public static int quantizePos(double blocks) {
        return (int) Math.round(blocks * POS_SCALE);
    }

    public static double dequantizePos(int quant) {
        return quant / POS_SCALE;
    }

    /** Degrees → 1/256-turn byte (the vanilla angle-byte shape). */
    public static byte angleToByte(float degrees) {
        return (byte) Math.floor(degrees * 256.0f / 360.0f);
    }

    public static float byteToAngle(byte b) {
        return b * 360.0f / 256.0f;
    }

    /** Blocks/s → x256 short, clamped to ±{@link #MAX_VELOCITY_BLOCKS_PER_SECOND}. */
    public static short velocityToShort(double blocksPerSecond) {
        double clamped = Math.clamp(blocksPerSecond,
                -MAX_VELOCITY_BLOCKS_PER_SECOND, MAX_VELOCITY_BLOCKS_PER_SECOND);
        return (short) Math.round(clamped * VEL_SCALE);
    }

    public static double shortToVelocity(short s) {
        return s / VEL_SCALE;
    }

    // ---- decode guards ----

    private static String dictEntry(String[] dict, int idx, String what) {
        if (idx < 0 || idx >= dict.length) {
            throw new WireFormatException(what + " dictionary index " + idx
                    + " outside table of " + dict.length);
        }
        return dict[idx];
    }

    private static int nonNegative(int v, String what) {
        if (v < 0) throw new WireFormatException(what + " negative: " + v);
        return v;
    }

    private static int boundedCount(int v, int max, String what) {
        if (v < 0 || v > max) {
            throw new WireFormatException(what + " count " + v + " outside [0, " + max + "]");
        }
        return v;
    }

    private static void requireDrained(WireBytes.Reader r, String what) {
        if (r.remaining() != 0) {
            throw new WireFormatException(what + " frame has " + r.remaining()
                    + " trailing bytes");
        }
    }

    private FarPlayerWire() {}
}
