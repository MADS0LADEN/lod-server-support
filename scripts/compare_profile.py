#!/usr/bin/env python3
"""Pooled base/change cuts over profile harness runs (PERF Phase 0 item 5).

This is the arithmetic every phase gate states, computed ONE way instead of ad hoc:
pooled band counts, pooled per-marker counters (with per-thread splits), pooled
per-thread LSS-frame counts (the Phase 3 gate metric), per-column allocation weight,
LSS-attributed µs/col, Poisson CIs on every cut, and the hibw ceiling metric
(pooled sources.disk_read ÷ pooled window seconds).

Usage:
  compare_profile.py <stamp-dir> [--arms A,B] [--skip-reps N]
  compare_profile.py <base-stamp> <change-stamp> [--arms A,B] [--skip-reps N]
  compare_profile.py --selftest

Default arms: base,change when present, else vanilla,c2me (profile_disk_read.sh's two
modes), else backfill,backfill (a backfill A/B across two stamp dirs). Every run dir
needs bands.json — run `analyze_profile_jfr.py compare <stamp-dir>` (with --window walk
for backfill stamps) first; this script is a pure aggregator and never shells out.

--skip-reps N drops reps 1..N from every arm — the round's "one discarded warm-up rep"
protocol is `--skip-reps 1`; the default 0 pools everything and says so.

Reading the output:
  - rows are labelled by ROLE (base/change); the arm names appear in the header.
  - cut% = 1 - change/base pooled counts; CI is a log-normal Poisson approximation
    (se = sqrt(1/a + 1/b)). A gate "≥30% cut with the CI lower bound above 0" means
    cut_lo > 0 and cut >= 30. change==0 reports a +100% cut with a rule-of-three
    lower bound (1 - 3/base); base==0 with change>0 is reported as "appeared".
  - CLASSIFIER TRIPWIRE: nbt-other and serialize-live are vanilla/probe work no phase
    touches — a significant move there means the classifier moved, not the code. The
    tripwire test is DISPERSION-AWARE (a t-test on per-rep rates), because the
    b0-aa-control A/A showed these bands are bursty window-edge work, 5-20x
    over-dispersed vs Poisson on code-identical arms — the pooled Poisson CI would
    fire on every A/B. When a tripwire band is 0 in both arms the drift check is
    UNDETECTABLE and says so.
  - µs/col = lss_attributed_samples × 10 ms ÷ columns. profile.jfc samples Java @10 ms
    but native @20 ms, so native-heavy bands (zip) are under-weighted ~2x in ABSOLUTE
    terms; cuts are unaffected when both arms have similar native mix (the F5 caveat).
  - alloc_by_class pools each run's top-60 classes (analyze's persistence cap), so a
    class hovering at the tail may be slightly undercounted; totals use alloc_total.

Exit: 0 on success, 2 when any pooled run is arm-invalid, artifacts are missing,
window modes are mixed, or a pooled backfill arm has no completed-walk seconds.
"""

import json
import math
import os
import sys
from collections import Counter, defaultdict
from glob import glob

SAMPLE_PERIOD_MS = 10.0          # profile.jfc Java exec-sample period
TRIPWIRE_BANDS = ("nbt-other", "serialize-live")
COLUMN_PARITY_WARN_PCT = 2.0     # the plan's columns_received parity guard


def die(msg):
    print(f"[compare-profile] ERROR: {msg}", file=sys.stderr)
    sys.exit(2)


def load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return None


def collect_arm(stamp_dir, arm, role, skip_reps=0):
    """Pool all <arm>-rep* run dirs (rep > skip_reps) into one record."""
    run_dirs = sorted(glob(os.path.join(stamp_dir, f"{arm}-rep*")))
    run_dirs = [d for d in run_dirs if os.path.isdir(d)]
    if not run_dirs:
        die(f"no {arm}-rep* run dirs in {stamp_dir}")
    pooled = {
        "arm": arm, "role": role, "reps": 0, "skipped": 0, "refs": set(),
        "bands": Counter(), "band_reps": defaultdict(list),
        "lss_attributed": 0, "total_exec": 0, "window_s": 0.0,
        "window_modes": set(),
        "markers": defaultdict(lambda: {"total": 0, "by_thread": Counter()}),
        "thread_lss": defaultdict(lambda: [0, 0]),   # th -> [lss, other] (Phase 3 gate)
        "alloc_by_class_mb": Counter(), "alloc_total_mb": 0.0,
        "columns": 0, "disk_read": 0, "deposited": 0, "walk_seconds": 0.0,
        "is_backfill": False,
    }
    for d in run_dirs:
        meta = load_json(os.path.join(d, "meta.json")) or {}
        if isinstance(meta.get("rep"), int) and meta["rep"] <= skip_reps:
            pooled["skipped"] += 1
            continue
        if meta.get("arm_valid") is not True:
            die(f"{d}: arm_valid is not true — an invalid arm must never be pooled "
                f"(echo='{meta.get('config_echo', '')}')")
        bands = load_json(os.path.join(d, "bands.json"))
        if bands is None:
            die(f"{d}: no bands.json — run analyze_profile_jfr.py compare first")
        pooled["reps"] += 1
        pooled["refs"].add(meta.get("ref", "?"))
        pooled["window_modes"].add(bands.get("window_mode", "wire-slope"))
        for k, v in bands["bands"].items():
            pooled["bands"][k] += v
            pooled["band_reps"][k].append((v, bands.get("window_s") or 0.0))
        pooled["lss_attributed"] += bands["lss_attributed_samples"]
        pooled["total_exec"] += bands["total_exec_samples"]
        pooled["window_s"] += bands.get("window_s") or 0.0
        for m, rec in bands.get("markers", {}).items():
            pooled["markers"][m]["total"] += rec["total"]
            for th, n in rec.get("by_thread", {}).items():
                pooled["markers"][m]["by_thread"][th] += n
        for th, by_band in bands.get("thread_band_lss", {}).items():
            for split in by_band.values():
                pooled["thread_lss"][th][0] += split.get("lss", 0)
                pooled["thread_lss"][th][1] += split.get("other", 0)
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
    if pooled["reps"] == 0:
        die(f"{stamp_dir}/{arm}: every rep was skipped (skip_reps too high?)")
    if len(pooled["window_modes"]) > 1:
        die(f"{stamp_dir}/{arm}: mixed analysis windows {sorted(pooled['window_modes'])} — "
            f"re-run analyze_profile_jfr.py with one --window mode before pooling")
    if pooled["is_backfill"]:
        if pooled["walk_seconds"] <= 0:
            die(f"{stamp_dir}/{arm}: backfill arm pooled 0 completed-walk seconds — "
                f"no rep finished its walk; raise the duration and re-run")
        if pooled["columns"] == 0:
            # Backfill runs are client-less: the per-column denominator is deposits.
            pooled["columns"] = pooled["deposited"]
    return pooled


def cut_ci(a, b):
    """(cut_pct, lo_pct, hi_pct) for pooled Poisson counts base=a, change=b.

    b==0: +100% cut with a rule-of-three lower bound (upper 95% CI of a Poisson
    observation of 0 is ~3, so ratio_hi = 3/a). a==0: no ratio exists -> None.
    """
    if a <= 0:
        return None
    if b == 0:
        return (100.0, 100.0 * (1 - 3.0 / a), 100.0)
    r = b / a
    se = math.sqrt(1.0 / a + 1.0 / b)
    lo_r, hi_r = r * math.exp(-1.96 * se), r * math.exp(1.96 * se)
    return (100 * (1 - r), 100 * (1 - hi_r), 100 * (1 - lo_r))


def significant(a, b):
    """True when the 95% CI on the cut excludes 0 (drift/effect detectable)."""
    c = cut_ci(a, b)
    if c is None:
        # base 0: an appearance is significant by rule-of-three symmetry when b >= 4.
        return b >= 4
    _, lo, hi = c
    return lo > 0 or hi < 0


# Two-sided 95% t critical values by dof (capped table; >10 -> ~z).
T_CRIT = {1: 12.71, 2: 4.30, 3: 3.18, 4: 2.78, 5: 2.57, 6: 2.45, 7: 2.36,
          8: 2.31, 9: 2.26, 10: 2.23}


def rep_level_significant(reps_a, reps_b):
    """Dispersion-aware significance on per-rep RATES (count / window seconds).

    The b0-aa-control A/A showed the save/probe-correlated bands (serialize-live,
    nbt-other, zip) are BURSTY — 5-20x over-dispersed vs Poisson on code-identical
    arms — so a pooled-count Poisson CI fires false tripwires there. A pooled t-test
    on rep rates carries the observed dispersion instead. Falls back to the Poisson
    judgement when either arm has < 2 reps.
    """
    if len(reps_a) < 2 or len(reps_b) < 2:
        return significant(sum(c for c, _ in reps_a), sum(c for c, _ in reps_b))
    ra = [c / w for c, w in reps_a if w > 0]
    rb = [c / w for c, w in reps_b if w > 0]
    if len(ra) < 2 or len(rb) < 2:
        return significant(sum(c for c, _ in reps_a), sum(c for c, _ in reps_b))
    ma, mb = sum(ra) / len(ra), sum(rb) / len(rb)
    va = sum((x - ma) ** 2 for x in ra) / (len(ra) - 1)
    vb = sum((x - mb) ** 2 for x in rb) / (len(rb) - 1)
    dof = len(ra) + len(rb) - 2
    sp2 = ((len(ra) - 1) * va + (len(rb) - 1) * vb) / dof
    if sp2 == 0:
        return ma != mb
    t = abs(ma - mb) / math.sqrt(sp2 * (1 / len(ra) + 1 / len(rb)))
    return t > T_CRIT.get(dof, 1.96)


def fmt_cut(a, b):
    if a == 0 and b == 0:
        return "n/a (0/0)"
    if a == 0:
        return f"appeared (0 -> {b})"
    c = cut_ci(a, b)
    cut, lo, hi = c
    return f"{cut:+6.1f}% [{lo:+.1f}, {hi:+.1f}]"


def compare(base, change):
    """Print the full comparison; returns nothing (pure reporting)."""
    print(f"# compare_profile: base={base['arm']} ({base['reps']} reps, refs "
          f"{sorted(base['refs'])}) vs change={change['arm']} ({change['reps']} reps, "
          f"refs {sorted(change['refs'])})")
    skipped = base["skipped"] + change["skipped"]
    if skipped:
        print(f"(skipped {skipped} warm-up rep(s) via --skip-reps)")
    else:
        print("(--skip-reps 0: pooling ALL reps including any warm-up rep)")
    if base["reps"] != change["reps"]:
        print(f"WARNING: unbalanced reps ({base['reps']} vs {change['reps']}) — "
              f"pooled counts are still valid, but check why an arm lost a rep")
    if base["window_modes"] != change["window_modes"]:
        die(f"window modes differ between arms: {sorted(base['window_modes'])} vs "
            f"{sorted(change['window_modes'])} — re-analyze with one --window mode")
    window_mode = next(iter(base["window_modes"]))
    print(f"pooled window ({window_mode}): base {base['window_s']:.0f}s, "
          f"change {change['window_s']:.0f}s"
          f" | columns: base {base['columns']}, change {change['columns']}")
    if base["columns"] and change["columns"] and not base["is_backfill"]:
        parity = 100.0 * abs(base["columns"] - change["columns"]) / base["columns"]
        marker = "WARNING" if parity > COLUMN_PARITY_WARN_PCT else "ok"
        print(f"column parity: {parity:.2f}% delta ({marker}; the plan's guard is "
              f"±{COLUMN_PARITY_WARN_PCT:.0f}% — beyond it, count cuts are diluted by "
              f"throughput change, biasing toward false FAILs)")
    print()

    print("## Pooled bands (samples; cut = 1 - change/base, Poisson 95% CI)")
    band_names = sorted(set(base["bands"]) | set(change["bands"]),
                        key=lambda k: -base["bands"].get(k, 0))
    for name in band_names:
        a, b = base["bands"].get(name, 0), change["bands"].get(name, 0)
        trip = "   <- TRIPWIRE" if name in TRIPWIRE_BANDS else ""
        print(f"- {name:<16} {a:>7} -> {b:<7} cut {fmt_cut(a, b)}{trip}")
    a, b = base["lss_attributed"], change["lss_attributed"]
    print(f"- {'lss_attributed':<16} {a:>7} -> {b:<7} cut {fmt_cut(a, b)}")
    for n in TRIPWIRE_BANDS:
        ta, tb = base["bands"].get(n, 0), change["bands"].get(n, 0)
        if ta == 0 and tb == 0:
            print(f"WARNING: tripwire band {n} is 0 in both arms — classifier drift is "
                  f"UNDETECTABLE here (normal for backfill windows; note it in the verdict)")
        elif rep_level_significant(base["band_reps"].get(n, []),
                                   change["band_reps"].get(n, [])):
            spread_a = [c for c, _ in base["band_reps"].get(n, [])]
            spread_b = [c for c, _ in change["band_reps"].get(n, [])]
            print(f"WARNING: tripwire band {n} moved beyond its rep-level dispersion "
                  f"({ta} -> {tb}; per-rep {spread_a} -> {spread_b}) — the CLASSIFIER "
                  f"moved, not the code; do not trust the other cuts")
    print()

    print("## Pooled per-thread LSS-frame samples (Phase 3 gate: lss cut + vanilla residual)")
    threads = sorted(set(base["thread_lss"]) | set(change["thread_lss"]),
                     key=lambda th: -base["thread_lss"].get(th, [0, 0])[0])
    for th in threads[:10]:
        la, oa = base["thread_lss"].get(th, [0, 0])
        lb, ob = change["thread_lss"].get(th, [0, 0])
        if la == 0 and lb == 0 and oa == 0 and ob == 0:
            continue
        print(f"- {th:<24} lss {la:>6} -> {lb:<6} cut {fmt_cut(la, lb)}"
              f" | other(residual) {oa} -> {ob}")
    print()

    print("## Pooled markers (any-frame counters; the phase-gate numbers)")
    for m in sorted(set(base["markers"]) | set(change["markers"])):
        a = base["markers"][m]["total"] if m in base["markers"] else 0
        b = change["markers"][m]["total"] if m in change["markers"] else 0
        if a == 0 and b == 0:
            continue
        print(f"- {m}")
        print(f"    total {a:>7} -> {b:<7} cut {fmt_cut(a, b)}")
        by_a = base["markers"].get(m, {}).get("by_thread", {})
        by_b = change["markers"].get(m, {}).get("by_thread", {})
        for th in sorted(set(by_a) | set(by_b), key=lambda t: -by_a.get(t, 0)):
            ta, tb = by_a.get(th, 0), by_b.get(th, 0)
            print(f"    {th:<24} {ta:>7} -> {tb:<7} cut {fmt_cut(ta, tb)}")
    print()

    if base["columns"] > 0 and change["columns"] > 0:
        print("## Per-column metrics (µs/col caveat: native sampled @20ms vs Java @10ms —"
              " absolutes understate native, cuts are fair)")
        for rec in (base, change):
            us_col = rec["lss_attributed"] * SAMPLE_PERIOD_MS * 1000.0 / rec["columns"]
            alloc_col = rec["alloc_total_mb"] * 1e6 / rec["columns"]
            print(f"- {rec['role']:<8} lss µs/col {us_col:8.1f} | alloc/col {alloc_col:9.0f} B"
                  f" | columns {rec['columns']}")
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
    else:
        print("## Per-column metrics: SKIPPED — an arm has 0 columns (no client.json "
              "and no completed walk); never divide by a fabricated denominator")
        print()

    if base["is_backfill"] or change["is_backfill"]:
        print("## Backfill walk (deposits/s = the Phase 2 gate metric)")
        for rec in (base, change):
            if rec["walk_seconds"] > 0:
                print(f"- {rec['role']:<8} deposited {rec['deposited']} in {rec['walk_seconds']:.0f}s"
                      f" = {rec['deposited'] / rec['walk_seconds']:.1f}/s")
        print()

    if base["disk_read"] or change["disk_read"]:
        print(f"## hibw ceiling (pooled sources.disk_read / pooled {window_mode}-window seconds)")
        for rec in (base, change):
            if rec["window_s"] > 0:
                print(f"- {rec['role']:<8} {rec['disk_read']} disk-read serves / {rec['window_s']:.0f}s"
                      f" = {rec['disk_read'] / rec['window_s']:.1f} col/s")
        print()


# ---- selftest --------------------------------------------------------------------------

def selftest():
    import contextlib
    import io
    import tempfile
    failures = []
    ran = [0]

    def check(name, cond):
        ran[0] += 1
        if cond:
            print(f"  ok  {name}")
        else:
            failures.append(name)
            print(f"FAIL  {name}")

    # cut_ci arithmetic + endpoint ordering
    c = cut_ci(300, 150)
    check("cut_ci midpoint", c is not None and abs(c[0] - 50.0) < 1e-9)
    check("cut_ci ordering lo<cut<hi", c[1] < c[0] < c[2])
    check("cut_ci zero-change rule-of-three", cut_ci(30, 0) == (100.0, 100.0 * (1 - 0.1), 100.0))
    check("cut_ci zero-base is None", cut_ci(0, 5) is None)
    check("fmt 0/0", fmt_cut(0, 0) == "n/a (0/0)")
    check("fmt appeared", fmt_cut(0, 7) == "appeared (0 -> 7)")
    check("fmt full-elimination has 100%", "+100.0%" in fmt_cut(30, 0))
    # significance: 1->0 must NOT be significant; 10->0 must; 0->4 must (symmetry)
    check("sig 1->0 no", not significant(1, 0))
    check("sig 10->0 yes", significant(10, 0))
    check("sig 0->4 yes", significant(0, 4))
    check("sig 0->3 no", not significant(0, 3))
    check("sig equal no", not significant(200, 200))
    # Dispersion-aware tripwire: the REAL b0-aa-control serialize-live rep data —
    # 6..164 across code-identical arms — must NOT fire (the Poisson CI did).
    aa_base = [(18, 83.0), (88, 82.0), (6, 79.0)]
    aa_change = [(164, 86.0), (48, 83.0), (52, 80.0)]
    check("rep-level: A/A burst not significant", not rep_level_significant(aa_base, aa_change))
    tight_base = [(100, 80.0), (98, 80.0), (102, 80.0)]
    tight_change = [(10, 80.0), (12, 80.0), (9, 80.0)]
    check("rep-level: tight real drift fires", rep_level_significant(tight_base, tight_change))
    check("rep-level: identical rates quiet",
          not rep_level_significant(tight_base, tight_base))
    check("rep-level: 1-rep falls back to Poisson",
          rep_level_significant([(300, 80.0)], [(150, 80.0)]))

    def write_run(root, arm, rep, *, valid=True, walk=None, columns=1000,
                  window_mode="wire-slope", bands_extra=None):
        d = os.path.join(root, f"{arm}-rep{rep}")
        os.makedirs(d, exist_ok=True)
        meta = {"arm": arm, "rep": rep, "ref": "abc123", "arm_valid": valid}
        if walk is not None:
            meta["walk"] = walk
        with open(os.path.join(d, "meta.json"), "w") as f:
            json.dump(meta, f)
        bands = {"window_mode": window_mode, "window_s": 60.0,
                 "total_exec_samples": 1000,
                 "bands": {"nbt": 100, "nbt-other": 10, "serialize-live": 5},
                 "lss_attributed_samples": 100,
                 "thread_band_lss": {"IO-Worker": {"nbt": {"lss": 90, "other": 4}}},
                 "markers": {"m.X": {"total": 50, "by_thread": {"IO-Worker": 50}}},
                 "alloc_total_mb": 10.0, "alloc_by_class_mb": {"byte[]": 8.0}}
        if bands_extra:
            bands.update(bands_extra)
        with open(os.path.join(d, "bands.json"), "w") as f:
            json.dump(bands, f)
        if columns is not None:
            with open(os.path.join(d, "client.json"), "w") as f:
                json.dump({"columns_received": columns}, f)
        with open(os.path.join(d, "server.json"), "w") as f:
            json.dump({"sources": {"disk_read": 900}}, f)

    def expect_die(name, fn):
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                fn()
        except SystemExit as e:
            check(name, e.code == 2)
        else:
            check(name, False)

    with tempfile.TemporaryDirectory() as td:
        write_run(td, "base", 1)
        write_run(td, "base", 2)
        write_run(td, "change", 1)
        write_run(td, "change", 2)
        rec = collect_arm(td, "base", "base")
        check("pool reps", rec["reps"] == 2 and rec["bands"]["nbt"] == 200)
        check("pool thread_lss", rec["thread_lss"]["IO-Worker"] == [180, 8])
        rec1 = collect_arm(td, "base", "base", skip_reps=1)
        check("skip-reps drops rep1", rec1["reps"] == 1 and rec1["skipped"] == 1)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            compare(collect_arm(td, "base", "base"), collect_arm(td, "change", "change"))
        text = out.getvalue()
        check("A/A tripwires quiet", "moved significantly" not in text)
        check("thread section present", "Pooled per-thread LSS-frame" in text)
        check("role labels", "- base " in text and "- change " in text)

    with tempfile.TemporaryDirectory() as td:
        write_run(td, "base", 1, valid=False)
        expect_die("invalid arm dies", lambda: collect_arm(td, "base", "base"))

    with tempfile.TemporaryDirectory() as td:
        write_run(td, "base", 1)
        write_run(td, "base", 2, window_mode="full")
        expect_die("mixed window modes die", lambda: collect_arm(td, "base", "base"))

    with tempfile.TemporaryDirectory() as td:
        walk = {"complete": True, "deposited": 5000, "walk_seconds": 50}
        write_run(td, "backfill", 1, walk=walk, columns=None, window_mode="walk")
        rec = collect_arm(td, "backfill", "base")
        check("backfill deposits become columns", rec["columns"] == 5000)

    with tempfile.TemporaryDirectory() as td:
        walk = {"complete": False, "deposited": 0, "walk_seconds": 0}
        write_run(td, "backfill", 1, walk=walk, columns=None, window_mode="walk")
        expect_die("0-walk-seconds backfill dies", lambda: collect_arm(td, "backfill", "base"))

    with tempfile.TemporaryDirectory() as td:
        write_run(td, "base", 1)
        write_run(td, "change", 1)
        write_run(td, "change", 2)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            compare(collect_arm(td, "base", "base"), collect_arm(td, "change", "change"))
        check("unbalanced reps warns", "unbalanced reps" in out.getvalue())

    with tempfile.TemporaryDirectory() as td:
        write_run(td, "base", 1, bands_extra={"bands": {"nbt": 100, "nbt-other": 0,
                                                        "serialize-live": 0}})
        write_run(td, "change", 1, bands_extra={"bands": {"nbt": 90, "nbt-other": 0,
                                                          "serialize-live": 0}})
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            compare(collect_arm(td, "base", "base"), collect_arm(td, "change", "change"))
        check("0/0 tripwire says undetectable", "UNDETECTABLE" in out.getvalue())

    print(f"[compare-profile] selftest: {ran[0]} cases, {len(failures)} failures — "
          f"{'PASS' if not failures else 'FAIL: ' + ', '.join(failures)}")
    sys.exit(1 if failures else 0)


def main():
    args = [a for a in sys.argv[1:]]
    if "--selftest" in args:
        selftest()
        return
    arms_arg, skip_reps = None, 0
    if "--arms" in args:
        i = args.index("--arms")
        arms_arg = args[i + 1]
        del args[i:i + 2]
    if "--skip-reps" in args:
        i = args.index("--skip-reps")
        skip_reps = int(args[i + 1])
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

    compare(collect_arm(base_dir, arm_a, "base", skip_reps),
            collect_arm(change_dir, arm_b, "change", skip_reps))


if __name__ == "__main__":
    main()
