#!/usr/bin/env python3
"""Estimate wire-size delta of identity-keyed palettes vs numeric-id palettes.

Parses real anvil region files, extracts per-section block-state/biome palettes,
and models three encodings of the palette layer (packed index arrays + light data
ship verbatim in all of them):
  A) current: palette entries as VarInt global ids (~2-3 B each)
  B) identity-inline: each palette entry as a canonical identity string
  C) identity-dict: per-column string dictionary + 1-B indices in section palettes
Compression proxy: zlib-6 over the synthesized palette blob + the real packed data.
"""
import sys, os, zlib, struct, io, json, statistics

# ---------- minimal NBT ----------
def read_nbt(buf):
    pos = [0]
    def u8():
        v = buf[pos[0]]; pos[0] += 1; return v
    def i16():
        v = struct.unpack_from(">h", buf, pos[0])[0]; pos[0] += 2; return v
    def i32():
        v = struct.unpack_from(">i", buf, pos[0])[0]; pos[0] += 4; return v
    def i64():
        v = struct.unpack_from(">q", buf, pos[0])[0]; pos[0] += 8; return v
    def rstr():
        n = struct.unpack_from(">H", buf, pos[0])[0]; pos[0] += 2
        s = buf[pos[0]:pos[0]+n].decode("utf-8", "replace"); pos[0] += n; return s
    def payload(t):
        if t == 1: return u8()
        if t == 2: return i16()
        if t == 3: return i32()
        if t == 4: return i64()
        if t == 5: pos[0] += 4; return 0.0
        if t == 6: pos[0] += 8; return 0.0
        if t == 7:
            n = i32(); pos[0] += n; return n  # byte array: return length only
        if t == 8: return rstr()
        if t == 9:
            et = u8(); n = i32(); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u8()
                if tt == 0: return d
                name = rstr()
                d[name] = payload(tt)
        if t == 11:
            n = i32(); pos[0] += 4 * n; return n
        if t == 12:
            n = i32(); vals = n  # long array: keep length only
            pos[0] += 8 * n; return {"__longs__": n}
        raise ValueError(f"tag {t}")
    t = u8()
    assert t == 10, t
    rstr()
    return payload(10)

# ---------- region ----------
def iter_chunks(path):
    data = open(path, "rb").read()
    if len(data) < 8192: return
    for i in range(1024):
        entry = struct.unpack_from(">I", data, i * 4)[0]
        off = (entry >> 8) * 4096
        if off == 0: continue
        ln = struct.unpack_from(">I", data, off)[0]
        comp = data[off + 4]
        raw = data[off + 5: off + 4 + ln]
        try:
            if comp == 2: nbt = zlib.decompress(raw)
            elif comp == 1: nbt = zlib.decompress(raw, 31)
            elif comp == 3: nbt = raw
            else: continue
            yield read_nbt(nbt)
        except Exception:
            continue

def varint_size(v):
    if v < 0: return 5
    n = 1
    while v >= 0x80:
        v >>= 7; n += 1
    return n

def canonical_identity(entry):
    """minecraft:name[k=v,...] with sorted property keys."""
    if isinstance(entry, str):
        return entry
    name = entry.get("Name", "?")
    props = entry.get("Properties")
    if not props:
        return name
    inner = ",".join(f"{k}={props[k]}" for k in sorted(props))
    return f"{name}[{inner}]"

def main(paths, max_cols):
    cols = []
    # Assume ~30k block states -> ids need up to 15 bits -> varints 1-3 B. Use a
    # conservative-low 2.5 B avg for block ids, 1.5 B for biome ids (~64-1300 ids).
    BLOCK_ID_B, BIOME_ID_B = 2.5, 1.5
    for path in paths:
        for chunk in iter_chunks(path):
            secs = chunk.get("sections") or chunk.get("Level", {}).get("sections")
            if not secs: continue
            served = []
            for s in secs:
                bs = s.get("block_states")
                if not bs: continue
                pal = bs.get("palette", [])
                ids = [canonical_identity(p) for p in pal]
                # LSS culls all-air sections
                if len(ids) == 1 and ids[0] == "minecraft:air": continue
                bio = s.get("biomes", {})
                bpal = [canonical_identity(p) for p in bio.get("palette", [])]
                nlongs = (bs.get("data") or {"__longs__": 0})
                nlongs = nlongs["__longs__"] if isinstance(nlongs, dict) else 0
                blongs = (bio.get("data") or {"__longs__": 0})
                blongs = blongs["__longs__"] if isinstance(blongs, dict) else 0
                served.append((ids, bpal, nlongs, blongs))
            if not served: continue
            # per-column stats
            n_entries = sum(len(s[0]) for s in served)
            n_bio = sum(len(s[1]) for s in served)
            data_bytes = sum(8 * (s[2] + s[3]) for s in served)
            distinct = sorted({i for s in served for i in s[0]} | {b for s in served for b in s[1]})
            cost_A = n_entries * BLOCK_ID_B + n_bio * BIOME_ID_B
            cost_B = sum(len(i) + varint_size(len(i)) for s in served for i in s[0]) + \
                     sum(len(b) + varint_size(len(b)) for s in served for b in s[1])
            dict_bytes = sum(len(i) + varint_size(len(i)) for i in distinct)
            cost_C = dict_bytes + n_entries * 1 + n_bio * 1  # 1-B dict indices
            # compression proxy: real palette strings + pseudo data payload
            # (data arrays are identical across encodings; include a stand-in of
            # random-ish bytes proportional to real data length so the ratio is
            # column-shaped, using the chunk's own NBT bytes would be circular)
            pal_blob_A = b"".join(bytes([1] * int(BLOCK_ID_B)) for _ in range(n_entries + n_bio))
            pal_blob_B = "".join(i for s in served for i in s[0]).encode()
            pal_blob_C = "".join(distinct).encode()
            zA = len(zlib.compress(pal_blob_A, 6))
            zB = len(zlib.compress(pal_blob_B, 6))
            zC = len(zlib.compress(pal_blob_C, 6))
            cols.append(dict(sections=len(served), entries=n_entries, bio=n_bio,
                             distinct=len(distinct), data=data_bytes,
                             A=cost_A, B=cost_B, C=cost_C,
                             zA=zA, zB=zB, zC=zC,
                             dict_raw=dict_bytes))
            if len(cols) >= max_cols: return cols
    return cols

if __name__ == "__main__":
    import glob
    region_dir = sys.argv[1]
    max_cols = int(sys.argv[2]) if len(sys.argv) > 2 else 400
    paths = sorted(glob.glob(os.path.join(region_dir, "*.mca")))
    cols = main(paths, max_cols)
    if not cols:
        print("no columns parsed"); sys.exit(1)
    def agg(key):
        vals = [c[key] for c in cols]
        return f"mean={statistics.mean(vals):8.1f}  median={statistics.median(vals):8.1f}  p90={sorted(vals)[int(0.9*len(vals))]:8.1f}"
    print(f"columns parsed: {len(cols)}  (from {region_dir})")
    for k, label in [("sections","served sections/col"), ("entries","block palette entries/col"),
                     ("bio","biome palette entries/col"), ("distinct","distinct identities/col"),
                     ("data","packed data bytes/col"),
                     ("A","A: varint-id palette bytes/col"),
                     ("B","B: inline identity bytes/col"),
                     ("C","C: dict identity bytes/col"),
                     ("dict_raw","C dictionary raw bytes/col"),
                     ("zB","zlib(inline strings)/col"), ("zC","zlib(dict strings)/col")]:
        print(f"{label:34s} {agg(k)}")
    mA = statistics.mean([c["A"] for c in cols]); mB = statistics.mean([c["B"] for c in cols]); mC = statistics.mean([c["C"] for c in cols])
    mD = statistics.mean([c["data"] for c in cols])
    mzB = statistics.mean([c["zB"] for c in cols]); mzC = statistics.mean([c["zC"] for c in cols])
    print()
    print(f"RAW overhead vs current: inline +{mB-mA:.0f} B/col ({100*(mB-mA)/(mD+mA):.2f}% of column), "
          f"dict +{mC-mA:.0f} B/col ({100*(mC-mA)/(mD+mA):.2f}%)")
    print(f"compressed-domain proxy: identity strings compress to ~{mzB:.0f} B (inline) / ~{mzC:.0f} B (dict) per column")
