#!/usr/bin/env python3
"""JFR analysis for the disk-read profile harness (scripts/profile_disk_read.sh).

For each run dir (profile-results/<stamp>/<arm>-rep<N>/) containing
server-benchmark.jfr + cpu.jsonl, this filters JFR events to the ACTIVE serving
window (wire-slope detection reused from analyze_benchmark_compare.py) and reports:

  - execution samples per thread (CPU share) and top self-methods (leaf frames)
  - top hot stacks (leaf + calling context)
  - allocation by class (jdk.ObjectAllocationSample weights)
  - GC pauses, slow file reads, per-thread CPU load

Also writes <run>/flame.collapsed (root;...;leaf count) for flamegraph tooling.

Usage:
  analyze_profile_jfr.py run <run-dir> [--window MODE]        # one run, prints report
  analyze_profile_jfr.py compare <stamp-dir> [--window MODE]  # all runs + jfr-report.md

--window (PERF Phase 0 item 4a — client-less runs have no wire slope to detect):
  (absent)   wire-slope active window from cpu.jsonl (the historic default)
  full       no time filter (window_s = span of sampled events)
  walk       the backfill walk window from the run's meta.json (backfill_profile.sh)
  T0:T1      explicit TIME-OF-DAY seconds (the domain JFR startTime parses to)

PROFILE_MARKERS: comma-separated frame-prefix overrides for the per-marker counters
(default list in DEFAULT_MARKERS — the Phase 1/2/4 gate identifiers).

Stdlib only; shells out to `jfr` (JDK 21+ for `print`; found via PATH or JAVA_HOME).
"""

import datetime
import glob
import json
import os
import re
import shutil
import subprocess
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze_benchmark_compare as abc_mod


def find_jfr_tool():
    tool = shutil.which("jfr")
    if tool:
        return tool
    java_home = os.environ.get("JAVA_HOME", "")
    cand = os.path.join(java_home, "bin", "jfr")
    if java_home and os.path.exists(cand):
        return cand
    sys.exit("ERROR: `jfr` tool not found on PATH or JAVA_HOME")


def active_window(run_dir):
    """(t_first, t_last) epoch seconds of the serving window, from cpu.jsonl wire slope."""
    samples = abc_mod.load_jsonl(os.path.join(run_dir, "cpu.jsonl"))
    srv_rows = [r for r in samples if isinstance(r.get("srv_cpu"), (int, float))]
    ts = [r["t"] for r in srv_rows]
    wire = [r["wire_bytes"] or 0 for r in srv_rows]
    for i in range(1, len(wire)):
        if wire[i] < wire[i - 1]:
            wire[i] = wire[i - 1]
    slopes = abc_mod.smoothed_slopes(ts, wire)
    peak = max(slopes) if slopes else 0.0
    thresh = max(50_000.0, peak * 0.01)
    active = [i for i, s in enumerate(slopes) if s > thresh]
    if not active:
        raise RuntimeError("no active window in cpu.jsonl")
    return ts[active[0]], ts[active[-1]]


def epoch_to_timeofday(epoch):
    lt = datetime.datetime.fromtimestamp(epoch)
    return lt.hour * 3600 + lt.minute * 60 + lt.second + lt.microsecond / 1e6


TIME_RE = re.compile(r"(\d\d):(\d\d):(\d\d)\.(\d+)")


def parse_timeofday(val):
    m = TIME_RE.search(val)
    if not m:
        return None
    h, mnt, s, frac = m.groups()
    return int(h) * 3600 + int(mnt) * 60 + int(s) + float("0." + frac)


DUR_RE = re.compile(r"([\d.]+)\s*(ns|us|µs|ms|s|m|h)")
DUR_MULT = {"ns": 1e-9, "us": 1e-6, "µs": 1e-6, "ms": 1e-3, "s": 1.0, "m": 60.0, "h": 3600.0}


def parse_duration_s(val):
    m = DUR_RE.search(val)
    return float(m.group(1)) * DUR_MULT[m.group(2)] if m else None


SIZE_RE = re.compile(r"([\d.]+)\s*(bytes|B|kB|KB|KiB|MB|MiB|GB|GiB)")
SIZE_MULT = {"bytes": 1, "B": 1, "kB": 1000, "KB": 1000, "KiB": 1024,
             "MB": 1e6, "MiB": 1 << 20, "GB": 1e9, "GiB": 1 << 30}


def parse_size_bytes(val):
    m = SIZE_RE.search(val)
    return float(m.group(1)) * SIZE_MULT[m.group(2)] if m else None


THREAD_RE = re.compile(r'"([^"]*)"')


def parse_thread(val):
    m = THREAD_RE.search(val)
    return m.group(1) if m else val.strip()


def thread_group(name):
    """Collapse per-index worker names so pools aggregate into one row."""
    n = re.sub(r"[-# ]?\d+$", "", name).strip()
    return n or name


def stream_events(jfr_tool, jfr_file, events):
    """Yield (event_name, fields: dict, stack: list[str]) from `jfr print`."""
    # --stack-depth: jfr print defaults to 5 frames, which truncates attribution for deep
    # MC stacks (the 2026-07-29 profile initially mis-bucketed recalcBlockCounts because
    # its caller frames were cut). 64 matches the recording's stackdepth.
    cmd = [jfr_tool, "print", "--stack-depth", "64", "--events", ",".join(events), jfr_file]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, errors="replace", bufsize=1 << 20)
    name, fields, stack, in_stack = None, {}, [], False
    for line in proc.stdout:
        line = line.rstrip("\n")
        stripped = line.strip()
        if name is None:
            if stripped.endswith("{") and stripped.split(" ")[0].startswith("jdk."):
                name, fields, stack, in_stack = stripped.split(" ")[0], {}, [], False
            continue
        if in_stack:
            if stripped == "]":
                in_stack = False
            elif stripped and stripped != "...":
                frame = stripped.split(" line:")[0].strip()
                stack.append(frame)
            continue
        if stripped == "stackTrace = [":
            in_stack = True
            continue
        if stripped == "}":
            yield name, fields, stack
            name = None
            continue
        if " = " in stripped:
            k, _, v = stripped.partition(" = ")
            fields[k.strip()] = v.strip()
    proc.stdout.close()
    proc.wait()


def shorten(frame, width=90):
    """Trim argument lists; keep package tail + method."""
    frame = re.sub(r"\(.*\)$", "", frame)
    return frame if len(frame) <= width else "…" + frame[-(width - 1):]


# ---- LOD-store band attribution (plan §5 Phase 0 (b)) ----------------------------------
#
# Stack-PREFIX buckets, scanned leaf-first: each exec sample is attributed to the first
# frame (from the leaf upward) that matches a band. Thread-group bucketing cannot separate
# store hits from NBT reads (same reader pool); frame bands can. Order matters — a store
# hit's stack is org.sqlite -> common.store -> reader pool, and must land in `store`, not
# `lss-other`. The `serialize`/`nbt` split is what §0's work-elimination gate reads
# ("NBT-parse + serialization bands ≈ 0 samples on warm-join"); `zip` covers the codec
# decompress cost of store hits AND the deflate cost of deposits.
BANDS = [
    ("store", ("org.sqlite.", "dev.vox.lss.common.store.")),
    ("zip", ("java.util.zip.", "com.github.luben.zstd.", "net.jpountz.lz4.")),
    ("nbt", ("net.minecraft.nbt.",)),
    ("serialize", ("dev.vox.lss.networking.server.NbtSectionSerializer",
                   "dev.vox.lss.networking.server.SectionSerializer",
                   "dev.vox.lss.networking.server.MemoizedNbtCodec",
                   "dev.vox.lss.paper.PaperNbtSectionSerializer",
                   "dev.vox.lss.paper.PaperSectionSerializer",
                   "dev.vox.lss.paper.PaperMemoizedNbtCodec")),
    ("lss-other", ("dev.vox.lss",)),
]
# Stack markers of the DISK serve path — the work the LOD store can eliminate. The
# nbt/serialize prefix match alone conflates it with STRUCTURAL serializer work that
# runs store-or-not (probe-rung serves of loaded chunks + the save-hook's dirty-hash
# re-serialization, both via SectionSerializer, and vanilla's own save-side
# net.minecraft.nbt building). The Phase 2 methodology review showed that conflation
# distorted the §0 work-elimination measurement in BOTH directions, so nbt/serialize
# samples are sub-classified by whole-stack context:
#   nbt / serialize            -> on the disk path (store-ADDRESSABLE)
#   nbt-other / serialize-live -> structural (probe serves, save-hook hash, vanilla save)
DISK_PATH_MARKERS = ("dev.vox.lss.networking.server.NbtSectionSerializer",
                     "dev.vox.lss.networking.server.MemoizedNbtCodec",
                     "dev.vox.lss.networking.server.ChunkDiskReader",
                     "dev.vox.lss.paper.PaperNbtSectionSerializer",
                     "dev.vox.lss.paper.PaperMemoizedNbtCodec",
                     "dev.vox.lss.paper.PaperChunkDiskReader",
                     # Phase 0 item 4e: the shared reader base (pool lambdas/methods declared
                     # there render under this name, not the platform subclass) and the
                     # PROSPECTIVE Phase 3/4 classes — the moved/selective parse must keep
                     # classifying as disk-path work, or the Phase 3/4 gates break silently.
                     # Phase 3's raw-read site must land in one of these classes (or be
                     # added here in the same PR).
                     "dev.vox.lss.common.processing.AbstractChunkDiskReader",
                     "dev.vox.lss.networking.server.SelectiveChunkNbtLoader",
                     "dev.vox.lss.paper.PaperSelectiveChunkNbtLoader")
DERIVED_BANDS = ("nbt-other", "serialize-live")
# Bands summed into the "LSS-attributable" aggregate for §0 metric 2 (per-column CPU).
# serialize-live IS LSS code (probe serves / save-hook) and stays attributed;
# nbt-other is VANILLA save-pipeline work misattributed before the split — excluded.
LSS_ATTRIBUTED_BANDS = ("store", "zip", "nbt", "serialize", "serialize-live", "lss-other")

# ---- Per-marker × thread counters (Phase 0 item 4c) ------------------------------------
#
# A sample counts under marker M when ANY frame startswith M (whole-stack, so a sample can
# count under several markers — these are independent "samples under X" gauges, not a
# partition like the bands). This is exactly the number the Phase 1/2/4 gates read
# ("samples under MemoizedNbtCodec on the backfill thread"); this round's 76/80/~60 were
# ad-hoc scripts, the practice this counter ends. MemoizedNbtCodec's Key must stay a
# NESTED class (frames render as MemoizedNbtCodec$Key...) so the prefix keeps matching
# through Phase 4's band subtraction. Method-level prefixes (LodStoreService.contentHash)
# work because frames render as class.method(args).
DEFAULT_MARKERS = (
    "dev.vox.lss.networking.server.MemoizedNbtCodec",
    "dev.vox.lss.paper.PaperMemoizedNbtCodec",
    "dev.vox.lss.common.store.LodStoreService.contentHash",
    "dev.vox.lss.networking.server.SelectiveChunkNbtLoader",
    "dev.vox.lss.paper.PaperSelectiveChunkNbtLoader",
    "dev.vox.lss.networking.server.ChunkDiskReader",
    "dev.vox.lss.common.processing.AbstractChunkDiskReader",
)


def marker_list():
    env = os.environ.get("PROFILE_MARKERS", "").strip()
    if env:
        return tuple(m.strip() for m in env.split(",") if m.strip())
    return DEFAULT_MARKERS


def band_for_stack(stack):
    """Leaf-first first-match band for one exec-sample stack (frames un-shortened);
    nbt/serialize sub-classified by disk-path stack context (see DISK_PATH_MARKERS)."""
    for frame in stack:
        for name, prefixes in BANDS:
            for p in prefixes:
                if frame.startswith(p):
                    if name in ("nbt", "serialize"):
                        on_disk_path = any(f.startswith(m) for f in stack
                                           for m in DISK_PATH_MARKERS)
                        if name == "nbt":
                            return "nbt" if on_disk_path else "nbt-other"
                        return "serialize" if on_disk_path else "serialize-live"
                    return name
    return "unattributed"


def resolve_window(run_dir, window_spec):
    """(mode, w0, w1) in TIME-OF-DAY seconds; w0/w1 None = no time filter.

    Phase 0 item 4a: client-less runs (the backfill arm) have no wire slope for the
    default detection, so `full` disables filtering and `walk` reads the walk window
    backfill_profile.sh parsed into meta.json. Caveat (pre-existing in the tod domain):
    a run crossing midnight breaks the comparison — don't profile across midnight.
    """
    if window_spec == "full":
        return "full", None, None
    if window_spec == "walk":
        with open(os.path.join(run_dir, "meta.json")) as f:
            walk = (json.load(f).get("walk") or {})
        t0, t1 = walk.get("start_tod_s"), walk.get("end_tod_s")
        if t0 is None or t1 is None:
            raise RuntimeError("--window walk: meta.json carries no walk window")
        return "walk", float(t0), float(t1)
    if window_spec:
        t0, _, t1 = window_spec.partition(":")
        return "explicit", float(t0), float(t1)
    t_first, t_last = active_window(run_dir)
    return "wire-slope", epoch_to_timeofday(t_first), epoch_to_timeofday(t_last)


def analyze_jfr(run_dir, jfr_tool, window_spec=None):
    jfr_file = os.path.join(run_dir, "server-benchmark.jfr")
    window_mode, w0, w1 = resolve_window(run_dir, window_spec)
    seen_tod = [None, None]  # min/max sampled-event tod (window_s for `full` mode)

    def in_window(fields):
        t = parse_timeofday(fields.get("startTime", ""))
        if t is not None:
            if seen_tod[0] is None or t < seen_tod[0]:
                seen_tod[0] = t
            if seen_tod[1] is None or t > seen_tod[1]:
                seen_tod[1] = t
        if w0 is None:
            return True
        return t is not None and w0 <= t <= w1

    exec_by_thread = Counter()
    self_methods = Counter()
    self_methods_lss = Counter()
    bands = Counter()
    hot_stacks = Counter()
    collapsed = Counter()
    alloc_by_class = Counter()
    alloc_total = 0.0
    gc_pauses = []
    file_reads = defaultdict(lambda: [0, 0.0, 0.0])  # path_group -> [count, total_s, max_s]
    thread_cpu = defaultdict(lambda: [0.0, 0])       # group -> [sum user+system, n]
    total_exec = 0
    markers = marker_list()
    marker_by_thread = {m: Counter() for m in markers}      # Phase 0 item 4c
    thread_band_lss = defaultdict(lambda: defaultdict(lambda: [0, 0]))  # item 4b: th->band->[lss, other]

    # jdk.NativeMethodSample (compressed-columns plan §5.2, review B2): time inside
    # native methods — Deflater.deflateBytes, zstd-jni — is NOT in jdk.ExecutionSample,
    # which made the `zip` band read 0.5% while deflate-6 provably cost ~15-20% of the
    # serve path. Counted into the same band/self-method attribution as exec samples.
    # Quantitative caveats (4-agent round, tooling F5): (a) profile.jfc samples Java
    # @10ms but native @20ms, so 1:1 merged counts UNDER-weight native time ~2x —
    # A/B CUTS are unaffected (both arms native-dominated in zip), absolute us/col
    # understates; (b) NativeMethodSample also catches threads BLOCKED in native
    # (epoll waits) — the large `unattributed` share dilutes every band share;
    # (c) this change re-denominates the band metrics store_gate_check's floors were
    # calibrated against pre-change (2026-07-31) — cut-based gates mostly cancel it,
    # but never compare post-change absolutes to calibration-era numbers.
    events = ["jdk.ExecutionSample", "jdk.NativeMethodSample",
              "jdk.ObjectAllocationSample", "jdk.GCPhasePause",
              "jdk.FileRead", "jdk.ThreadCPULoad"]
    for name, fields, stack in stream_events(jfr_tool, jfr_file, events):
        if not in_window(fields):
            continue
        if name in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
            total_exec += 1
            th = thread_group(parse_thread(fields.get("sampledThread", "?")))
            exec_by_thread[th] += 1
            if stack:
                leaf = shorten(stack[0])
                self_methods[leaf] += 1
                band = band_for_stack(stack)
                bands[band] += 1
                has_lss = any(f.startswith("dev.vox.lss") for f in stack)
                thread_band_lss[th][band][0 if has_lss else 1] += 1
                for m in markers:
                    if any(f.startswith(m) for f in stack):
                        marker_by_thread[m][th] += 1
                for fr in stack:
                    if fr.startswith("dev.vox.lss"):
                        self_methods_lss[shorten(fr)] += 1
                        break
                sig = " <- ".join(shorten(f, 70) for f in stack[:5])
                hot_stacks[(th, sig)] += 1
                collapsed[";".join(shorten(f, 200) for f in reversed(stack))] += 1
        elif name == "jdk.ObjectAllocationSample":
            weight = parse_size_bytes(fields.get("weight", "")) or 0
            alloc_total += weight
            cls = fields.get("objectClass", "?")
            alloc_by_class[cls] += weight
        elif name == "jdk.GCPhasePause":
            d = parse_duration_s(fields.get("duration", ""))
            if d is not None:
                gc_pauses.append(d)
        elif name == "jdk.FileRead":
            path = fields.get("path", "?").strip('"')
            group = "region-files" if "/region/" in path or path.endswith(".mca") \
                else os.path.dirname(path) or path
            d = parse_duration_s(fields.get("duration", "")) or 0
            rec = file_reads[group]
            rec[0] += 1
            rec[1] += d
            rec[2] = max(rec[2], d)
        elif name == "jdk.ThreadCPULoad":
            th = thread_group(parse_thread(fields.get("eventThread", "?")))
            user = float(fields.get("user", "0%").rstrip("%"))
            system = float(fields.get("system", "0%").rstrip("%"))
            rec = thread_cpu[th]
            rec[0] += user + system
            rec[1] += 1

    with open(os.path.join(run_dir, "flame.collapsed"), "w") as f:
        for stackline, n in collapsed.most_common():
            f.write(f"{stackline} {n}\n")

    if w0 is not None:
        window_s = round(w1 - w0, 1)
    elif seen_tod[0] is not None:
        window_s = round(seen_tod[1] - seen_tod[0], 1)
    else:
        window_s = 0.0

    # Machine-readable band attribution for the store gates (§0 metric 2 reads
    # lss_attributed_samples × idle-corrected CPU ÷ columns from this file).
    # Phase 0 additions: thread_band_lss (item 4b — "IO-Worker samples carrying an LSS
    # frame" is what the Phase 3 gate reads), markers (item 4c — the Phase 1/2/4 gate
    # counters), alloc_by_class_mb (item 4d — was printed, never persisted; the Phase 4
    # allocation gate divides these by columns_received).
    band_summary = {
        "window_mode": window_mode,
        "window_tod": None if w0 is None else [round(w0, 1), round(w1, 1)],
        "window_s": window_s,
        "total_exec_samples": total_exec,
        "bands": {name: bands.get(name, 0)
                  for name in [b[0] for b in BANDS] + list(DERIVED_BANDS) + ["unattributed"]},
        "lss_attributed_samples": sum(bands.get(b, 0) for b in LSS_ATTRIBUTED_BANDS),
        "thread_band_lss": {th: {band: {"lss": v[0], "other": v[1]}
                                 for band, v in sorted(by_band.items())}
                            for th, by_band in sorted(thread_band_lss.items())},
        "markers": {m: {"total": sum(cnt.values()),
                        "by_thread": dict(cnt.most_common())}
                    for m, cnt in marker_by_thread.items()},
        "alloc_total_mb": round(alloc_total / 1e6, 1),
        "alloc_by_class_mb": {c: round(wt / 1e6, 2)
                              for c, wt in alloc_by_class.most_common(60)},
    }
    with open(os.path.join(run_dir, "bands.json"), "w") as f:
        json.dump(band_summary, f, indent=1)

    return {
        "run": os.path.basename(run_dir),
        "window_s": window_s,
        "total_exec_samples": total_exec,
        "bands": band_summary,
        "exec_by_thread": exec_by_thread.most_common(14),
        "self_methods": self_methods.most_common(22),
        "self_methods_lss": self_methods_lss.most_common(12),
        "hot_stacks": hot_stacks.most_common(15),
        "alloc_total_mb": round(alloc_total / 1e6, 1),
        "alloc_by_class": [(c, round(w / 1e6, 1)) for c, w in alloc_by_class.most_common(15)],
        "gc_pause_count": len(gc_pauses),
        "gc_pause_total_ms": round(sum(gc_pauses) * 1000, 1),
        "gc_pause_max_ms": round(max(gc_pauses) * 1000, 1) if gc_pauses else 0,
        "file_reads": sorted(((g, n, round(t * 1000, 1), round(mx * 1000, 1))
                              for g, (n, t, mx) in file_reads.items()),
                             key=lambda r: -r[2])[:10],
        "thread_cpu": sorted(((th, round(s / n, 1)) for th, (s, n) in thread_cpu.items() if n),
                             key=lambda r: -r[1])[:14],
    }


def render_report(r):
    lines = [f"## {r['run']} — {r['bands'].get('window_mode', 'wire-slope')} window "
             f"{r['window_s']}s, {r['total_exec_samples']} exec samples", ""]

    lines.append("### CPU share by thread (exec samples)")
    tot = r["total_exec_samples"] or 1
    for th, n in r["exec_by_thread"]:
        lines.append(f"- {th}: {n} ({100 * n / tot:.1f}%)")
    lines.append("")

    lines.append("### Band attribution (leaf-first stack-prefix buckets; store gates read these)")
    for name, n in r["bands"]["bands"].items():
        lines.append(f"- {name}: {n} ({100 * n / tot:.1f}%)")
    lines.append(f"- lss_attributed (store+zip+nbt+serialize+serialize-live+lss-other): "
                 f"{r['bands']['lss_attributed_samples']} "
                 f"({100 * r['bands']['lss_attributed_samples'] / tot:.1f}%)")
    lines.append("")

    lines.append("### Per-marker samples (any-frame prefix match; phase-gate counters)")
    for m, rec in r["bands"]["markers"].items():
        if rec["total"] == 0:
            continue
        top = ", ".join(f"{th}={n}" for th, n in list(rec["by_thread"].items())[:4])
        lines.append(f"- {m}: {rec['total']} ({top})")
    lines.append("")

    lines.append("### Top self methods (leaf frames)")
    for m, n in r["self_methods"]:
        lines.append(f"- {n:>6}  {m}")
    lines.append("")

    lines.append("### Top LSS frames (first dev.vox.lss frame on stack)")
    for m, n in r["self_methods_lss"]:
        lines.append(f"- {n:>6}  {m}")
    lines.append("")

    lines.append("### Hot stacks (leaf <- callers, top 5 frames)")
    for (th, sig), n in r["hot_stacks"]:
        lines.append(f"- {n:>6}  [{th}] {sig}")
    lines.append("")

    lines.append(f"### Allocation (sampled weight ~{r['alloc_total_mb']} MB)")
    for c, w in r["alloc_by_class"]:
        lines.append(f"- {w:>9.1f} MB  {c}")
    lines.append("")

    lines.append(f"### GC pauses: {r['gc_pause_count']} totaling {r['gc_pause_total_ms']} ms "
                 f"(max {r['gc_pause_max_ms']} ms)")
    lines.append("")

    lines.append("### Slow file reads (jdk.FileRead, threshold-gated) — group, count, total ms, max ms")
    for g, n, t, mx in r["file_reads"]:
        lines.append(f"- {g}: n={n} total={t}ms max={mx}ms")
    lines.append("")

    lines.append("### Mean thread CPU (jdk.ThreadCPULoad user+system %, window)")
    for th, pct in r["thread_cpu"]:
        lines.append(f"- {th}: {pct}%")
    lines.append("")
    return "\n".join(lines)


def main():
    args = sys.argv[1:]
    window_spec = None
    if "--window" in args:
        i = args.index("--window")
        window_spec = args[i + 1]
        del args[i:i + 2]
    if len(args) < 2 or args[0] not in ("run", "compare"):
        sys.exit(__doc__)
    jfr_tool = find_jfr_tool()
    if args[0] == "run":
        print(render_report(analyze_jfr(args[1], jfr_tool, window_spec)))
        return
    stamp_dir = args[1]
    out = ["# Disk-read profile — JFR report", ""]
    for run_dir in sorted(glob.glob(os.path.join(stamp_dir, "*-rep*"))):
        if not os.path.isdir(run_dir):
            continue
        if not os.path.exists(os.path.join(run_dir, "server-benchmark.jfr")):
            out.append(f"## {os.path.basename(run_dir)} — no server-benchmark.jfr")
            continue
        print(f"[jfr] analyzing {run_dir} ...", file=sys.stderr)
        try:
            # `walk` only resolves for backfill runs (meta.json carries the window);
            # a serve run in the same stamp dir falls back to its own wire slope.
            spec = window_spec
            if spec == "walk" and "backfill-" not in os.path.basename(run_dir):
                spec = None
            out.append(render_report(analyze_jfr(run_dir, jfr_tool, spec)))
        except Exception as e:  # noqa: BLE001 — a bad run must not kill the batch
            out.append(f"## {os.path.basename(run_dir)} — ERROR {e!r}")
    report = os.path.join(stamp_dir, "jfr-report.md")
    with open(report, "w") as f:
        f.write("\n".join(out))
    print(f"[jfr] wrote {report}", file=sys.stderr)


if __name__ == "__main__":
    main()
