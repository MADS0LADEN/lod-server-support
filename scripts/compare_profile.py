#!/usr/bin/env python3
"""Pooled base/change cuts over profile harness runs (PERF Phase 0 item 5).

This is the arithmetic every phase gate states, computed ONE way instead of ad hoc:
pooled band counts, pooled per-marker counters (with per-thread splits), per-column
allocation weight, LSS-attributed µs/col, Poisson CIs on every cut, and the hibw
ceiling metric (pooled sources.disk_read ÷ pooled window seconds).

Usage:
  compare_profile.py <stamp-dir> [--arms A,B]        # both arms in one stamp dir
  compare_profile.py <base-stamp> <change-stamp>     # arms in separate stamp dirs

Default arms: base,change when present, else vanilla,c2me (profile_disk_read.sh's two
modes), else backfill,backfill (a backfill A/B across two stamp dirs). Every run dir
needs bands.json — run `analyze_profile_jfr.py compare <stamp-dir>` (with --window walk
for backfill stamps) first; this script is a pure aggregator and never shells out.

Reading the output:
  - cut% = 1 - change/base pooled counts; CI is a log-normal Poisson approximation
    (se = sqrt(1/a + 1/b)). A gate "≥30% cut with the CI lower bound above 0" means
    cut_lo > 0 and cut >= 30.
  - CLASSIFIER TRIPWIRE: nbt-other and serialize-live are vanilla/probe work no phase
    touches — a cut whose CI excludes 0 there means the classifier moved, not the code.
  - µs/col = lss_attributed_samples × 10 ms ÷ columns. profile.jfc samples Java @10 ms
    but native @20 ms, so native-heavy bands (zip) are under-weighted ~2x in ABSOLUTE
    terms; cuts are unaffected when both arms have similar native mix (the F5 caveat).

Exit: 0 on success, 2 when any pooled run is arm-invalid or artifacts are missing.
"""

import json
import math
import os
import sys
from collections import Counter, defaultdict
from glob import glob

SAMPLE_PERIOD_MS = 10.0          # profile.jfc Java exec-sample period
TRIPWIRE_BANDS = ("nbt-other", "serialize-live")


def die(msg):
    print(f"[compare-profile] ERROR: {msg}", file=sys.stderr)
    sys.exit(2)


def load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def collect_arm(stamp_dir, arm):
    """Pool all <arm>-rep* run dirs into one record."""
    run_dirs = sorted(glob(os.path.join(stamp_dir, f"{arm}-rep*")))
    run_dirs = [d for d in run_dirs if os.path.isdir(d)]
    if not run_dirs:
        die(f"no {arm}-rep* run dirs in {stamp_dir}")
    pooled = {
        "arm": arm, "reps": 0, "refs": set(), "bands": Counter(),
        "lss_attributed": 0, "total_exec": 0, "window_s": 0.0,
        "markers": defaultdict(lambda: {"total": 0, "by_thread": Counter()}),
        "alloc_by_class_mb": Counter(), "alloc_total_mb": 0.0,
        "columns": 0, "disk_read": 0, "deposited": 0, "walk_seconds": 0.0,
        "is_backfill": False,
    }
    for d in run_dirs:
        meta = load_json(os.path.join(d, "meta.json")) or {}
        if meta.get("arm_valid") is not True:
            die(f"{d}: arm_valid is not true — an invalid arm must never be pooled "
                f"(echo='{meta.get('config_echo', '')}')")
        bands = load_json(os.path.join(d, "bands.json"))
        if bands is None:
            die(f"{d}: no bands.json — run analyze_profile_jfr.py compare first")
        pooled["reps"] += 1
        pooled["refs"].add(meta.get("ref", "?"))
        for k, v in bands["bands"].items():
            pooled["bands"][k] += v
        pooled["lss_attributed"] += bands["lss_attributed_samples"]
        pooled["total_exec"] += bands["total_exec_samples"]
        pooled["window_s"] += bands.get("window_s") or 0.0
        for m, rec in bands.get("markers", {}).items():
            pooled["markers"][m]["total"] += rec["total"]
            for th, n in rec.get("by_thread", {}).items():
                pooled["markers"][m]["by_thread"][th] += n
        for c, mb in bands.get("alloc_by_class_mb", {}).items():
            pooled["alloc_by_class_mb"][c] += mb
        pooled["alloc_total_mb"] += bands.get("alloc_total_mb") or 0.0
        client = load_json(os.path.join(d, "client.json"))
        if client:
            pooled["columns"] += client.get("columns_received") or 0
        server = load_json(os.path.join(d, "server.json"))
        if server:
            pooled["disk_read"] += (server.get("sources") or {}).get("disk_read") or 0
        walk = meta.get("walk")
        if walk:
            pooled["is_backfill"] = True
            pooled["deposited"] += walk.get("deposited") or 0
            pooled["walk_seconds"] += walk.get("walk_seconds") or 0
    if pooled["is_backfill"] and pooled["columns"] == 0:
        # Backfill runs are client-less: the per-column denominator is deposits.
        pooled["columns"] = pooled["deposited"]
    return pooled


def cut_ci(a, b):
    """(cut_pct, lo_pct, hi_pct) for pooled Poisson counts base=a, change=b; None = n/a."""
    if a <= 0 or b <= 0:
        return None
    r = b / a
    se = math.sqrt(1.0 / a + 1.0 / b)
    lo_r, hi_r = r * math.exp(-1.96 * se), r * math.exp(1.96 * se)
    return (100 * (1 - r), 100 * (1 - hi_r), 100 * (1 - lo_r))


def fmt_cut(a, b):
    c = cut_ci(a, b)
    if c is None:
        return "n/a"
    cut, lo, hi = c
    return f"{cut:+6.1f}% [{lo:+.1f}, {hi:+.1f}]"


def main():
    args = [a for a in sys.argv[1:]]
    arms_arg = None
    if "--arms" in args:
        i = args.index("--arms")
        arms_arg = args[i + 1]
        del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    if len(args) == 1:
        base_dir = change_dir = args[0]
    else:
        base_dir, change_dir = args[0], args[1]

    if arms_arg:
        arm_a, arm_b = arms_arg.split(",")
    else:
        def has(d, arm):
            return bool(glob(os.path.join(d, f"{arm}-rep*")))
        if has(base_dir, "base") and has(change_dir, "change"):
            arm_a, arm_b = "base", "change"
        elif has(base_dir, "vanilla") and has(change_dir, "c2me"):
            arm_a, arm_b = "vanilla", "c2me"
        elif has(base_dir, "backfill") and has(change_dir, "backfill"):
            arm_a = arm_b = "backfill"
        else:
            die(f"cannot auto-detect arms in {base_dir} / {change_dir} — pass --arms A,B")
    if base_dir == change_dir and arm_a == arm_b:
        die("one stamp dir needs two distinct arms (or pass two stamp dirs)")

    base = collect_arm(base_dir, arm_a)
    change = collect_arm(change_dir, arm_b)

    print(f"# compare_profile: base={arm_a} ({base['reps']} reps, refs {sorted(base['refs'])})"
          f" vs change={arm_b} ({change['reps']} reps, refs {sorted(change['refs'])})")
    if base["reps"] != change["reps"]:
        print(f"WARNING: unbalanced reps ({base['reps']} vs {change['reps']}) — "
              f"pooled counts are still valid, but check why an arm lost a rep")
    print(f"pooled window: base {base['window_s']:.0f}s, change {change['window_s']:.0f}s"
          f" | columns: base {base['columns']}, change {change['columns']}")
    print()

    print("## Pooled bands (samples; cut = 1 - change/base, Poisson 95% CI)")
    band_names = sorted(set(base["bands"]) | set(change["bands"]),
                        key=lambda k: -base["bands"].get(k, 0))
    for name in band_names:
        a, b = base["bands"].get(name, 0), change["bands"].get(name, 0)
        trip = "   <- TRIPWIRE (classifier drift if CI excludes 0)" if name in TRIPWIRE_BANDS else ""
        print(f"- {name:<16} {a:>7} -> {b:<7} cut {fmt_cut(a, b)}{trip}")
    a, b = base["lss_attributed"], change["lss_attributed"]
    print(f"- {'lss_attributed':<16} {a:>7} -> {b:<7} cut {fmt_cut(a, b)}")
    tripped = [n for n in TRIPWIRE_BANDS
               if (c := cut_ci(base["bands"].get(n, 0), change["bands"].get(n, 0)))
               and (c[1] > 0 or c[2] < 0)]
    if tripped:
        print(f"WARNING: tripwire bands moved significantly: {', '.join(tripped)} — "
              f"the CLASSIFIER moved, not the code; do not trust the other cuts")
    print()

    print("## Pooled markers (any-frame counters; the phase-gate numbers)")
    for m in sorted(set(base["markers"]) | set(change["markers"])):
        a = base["markers"].get(m, {}).get("total", 0) if m in base["markers"] else 0
        b = change["markers"].get(m, {}).get("total", 0) if m in change["markers"] else 0
        if a == 0 and b == 0:
            continue
        print(f"- {m}")
        print(f"    total {a:>7} -> {b:<7} cut {fmt_cut(a, b)}")
        threads = sorted(set(base["markers"].get(m, {}).get("by_thread", {}))
                         | set(change["markers"].get(m, {}).get("by_thread", {})),
                         key=lambda th: -(base["markers"].get(m, {}).get("by_thread", {}).get(th, 0)))
        for th in threads:
            ta = base["markers"].get(m, {}).get("by_thread", {}).get(th, 0)
            tb = change["markers"].get(m, {}).get("by_thread", {}).get(th, 0)
            print(f"    {th:<24} {ta:>7} -> {tb:<7} cut {fmt_cut(ta, tb)}")
    print()

    print("## Per-column metrics (µs/col caveat: native sampled @20ms vs Java @10ms —"
          " absolutes understate native, cuts are fair)")
    for rec in (base, change):
        cols = rec["columns"] or 1
        us_col = rec["lss_attributed"] * SAMPLE_PERIOD_MS * 1000.0 / cols
        alloc_col = rec["alloc_total_mb"] * 1e6 / cols
        print(f"- {rec['arm']:<8} lss µs/col {us_col:8.1f} | alloc/col {alloc_col:9.0f} B"
              f" | columns {rec['columns']}")
    if base["columns"] and change["columns"]:
        base_us = base["lss_attributed"] * SAMPLE_PERIOD_MS * 1000.0 / base["columns"]
        change_us = change["lss_attributed"] * SAMPLE_PERIOD_MS * 1000.0 / change["columns"]
        if base_us > 0:
            print(f"- µs/col cut: {100 * (1 - change_us / base_us):+.1f}%"
                  f" (count CI applies: {fmt_cut(base['lss_attributed'], change['lss_attributed'])}"
                  f" before the column normalization)")
        print("- top allocation movers (per-column bytes, change - base):")
        movers = []
        for c in set(base["alloc_by_class_mb"]) | set(change["alloc_by_class_mb"]):
            pa = base["alloc_by_class_mb"].get(c, 0.0) * 1e6 / base["columns"]
            pb = change["alloc_by_class_mb"].get(c, 0.0) * 1e6 / change["columns"]
            movers.append((pb - pa, c, pa, pb))
        movers.sort(key=lambda t: -abs(t[0]))
        for delta, cls, pa, pb in movers[:8]:
            print(f"    {delta:+10.0f} B/col  {cls}  ({pa:.0f} -> {pb:.0f})")
    print()

    if base["is_backfill"] or change["is_backfill"]:
        print("## Backfill walk (deposits/s = the Phase 2 gate metric)")
        for rec in (base, change):
            if rec["walk_seconds"] > 0:
                print(f"- {rec['arm']:<8} deposited {rec['deposited']} in {rec['walk_seconds']:.0f}s"
                      f" = {rec['deposited'] / rec['walk_seconds']:.1f}/s")
        print()

    if base["disk_read"] or change["disk_read"]:
        print("## hibw ceiling (pooled sources.disk_read / pooled window seconds)")
        for rec in (base, change):
            if rec["window_s"] > 0:
                print(f"- {rec['arm']:<8} {rec['disk_read']} disk-read serves / {rec['window_s']:.0f}s"
                      f" = {rec['disk_read'] / rec['window_s']:.1f} col/s")
        print()


if __name__ == "__main__":
    main()
