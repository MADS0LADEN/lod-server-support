#!/usr/bin/env python3
"""Region-header timestamp scanner for the LOD store's freshness design
(docs/planning/lod-store-implementation-plan.md §1): the per-column `src_stamp` freshness
mechanism relies on chunk writers maintaining the .mca header timestamp table (the second
4 KiB block — one epoch-seconds int per chunk). Vanilla maintains it; this tool VERIFIES a
given writer did (Phase 0 requires per-platform evidence for vanilla / Moonrise / C2ME —
an unverifiable writer degrades that world to startup-sweep-only).

Usage:
  mca_timestamps.py scan <region-dir>              # per-dir summary: nonzero/zero stamps,
                                                   # min/max stamp, per-file rollup
  mca_timestamps.py compare <before-dir> <after-dir>
        # For chunks present in both: count stamps that ADVANCED, went BACKWARD, or held.
        # A writer that rewrites chunks without advancing header stamps (advanced==0 while
        # the region file mtime moved) fails the freshness prerequisite.

Stdlib only.
"""

import os
import re
import struct
import sys
import time

NAME = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca$")


def read_header(path):
    """{chunk_index: timestamp} for present chunks (location != 0)."""
    out = {}
    with open(path, "rb") as f:
        header = f.read(8192)
    if len(header) < 8192:
        return out
    for idx in range(1024):
        loc = struct.unpack_from(">I", header, idx * 4)[0]
        if loc == 0:
            continue
        ts = struct.unpack_from(">I", header, 4096 + idx * 4)[0]
        out[idx] = ts
    return out


def scan(region_dir):
    total = nonzero = zero = 0
    lo, hi = None, 0
    files = sorted(p for p in os.listdir(region_dir) if NAME.search(p))
    if not files:
        sys.exit(f"no region files in {region_dir}")
    for name in files:
        stamps = read_header(os.path.join(region_dir, name))
        nz = sum(1 for t in stamps.values() if t)
        z = len(stamps) - nz
        total += len(stamps)
        nonzero += nz
        zero += z
        for t in stamps.values():
            if t:
                lo = t if lo is None else min(lo, t)
                hi = max(hi, t)
    print(f"{region_dir}: files={len(files)} chunks={total} "
          f"nonzero_stamps={nonzero} zero_stamps={zero}")
    if lo is not None:
        fmt = "%Y-%m-%d %H:%M:%S"
        print(f"  stamp range: {time.strftime(fmt, time.localtime(lo))} .. "
              f"{time.strftime(fmt, time.localtime(hi))}")
    if zero:
        print(f"  WARNING: {zero} present chunks have ZERO header stamps — this writer "
              "does not maintain the timestamp table; freshness degrades to "
              "startup-sweep-only for such worlds")
    return 0 if zero == 0 else 1


def compare(before_dir, after_dir):
    advanced = backward = held = only_after = 0
    after_files = {p for p in os.listdir(after_dir) if NAME.search(p)}
    for name in sorted(after_files):
        b_path = os.path.join(before_dir, name)
        a = read_header(os.path.join(after_dir, name))
        b = read_header(b_path) if os.path.exists(b_path) else {}
        for idx, ts in a.items():
            if idx not in b:
                only_after += 1
            elif ts > b[idx]:
                advanced += 1
            elif ts < b[idx]:
                backward += 1
            else:
                held += 1
    print(f"compare {before_dir} -> {after_dir}: advanced={advanced} held={held} "
          f"backward={backward} new_chunks={only_after}")
    if advanced == 0 and only_after == 0:
        print("  WARNING: no stamp advanced and no new chunks — either nothing was "
              "rewritten (inconclusive) or this writer does not maintain the header "
              "timestamps (check file mtimes to distinguish)")
        return 1
    return 0


def main(argv):
    if len(argv) >= 3 and argv[1] == "scan":
        return scan(argv[2])
    if len(argv) >= 4 and argv[1] == "compare":
        return compare(argv[2], argv[3])
    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
