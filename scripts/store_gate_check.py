#!/usr/bin/env python3
"""LOD-store §0 gate calculator (docs/planning/lod-store-implementation-plan.md §0).

Consumes a store_gate.sh stamp dir (off-repN / on-repN run dirs from the warm-join or
no-cache benchmark scenario) and computes, per rep pair and as medians:

warm mode (the three-part §0 gate, measure cycle only):
  1. Work elimination (on-arm): store.hits/(hits+misses) >= 0.95; disk.submitted <= 2%
     of columns served; JFR nbt+serialize bands <= 1% of exec samples (band check skipped
     with a warning when the `jfr` tool or the JFR/cpu.jsonl files are unavailable).
  2. LSS-band CPU per column: (lss_attributed_samples / total_samples) x active-window
     process-CPU-seconds / columns_received — on-arm must be >= 70% below the paired
     off-arm. (srv_cpu jiffies from cpu.jsonl are process CPU: idle-corrected by
     construction; the window comes from the wire slope, same as the JFR analysis.)
  3. Non-regression: whole-JVM CPU-s/1k columns reported (informational — expected band
     ~-35..50%, stated so a -40% isn't misread as failure); hit p95 (store.read_p95_us)
     must be >= 5x better than the off-arm's mean disk read (compared against the MEAN,
     which is stricter than p95-vs-p95). MSPT pairing is deliberately NOT here — it
     rides the soak snapshots (mspt_avg_window), which benchmark runs don't emit.

cold mode (the deposits-on cold-path gate): whole-JVM CPU-s/1k columns, on-arm
  regression vs paired off-arm must be <= 10%.

Zip-band caveat (recorded here so gate readers see it): java.util.zip also serves MC's
connection-level packet deflate, so the `zip` band is part-vanilla on warm arms; it is
reported but never gated on.

Exit 0 = all gates pass (or informational-only), 1 = any gate fails.
Stdlib only; imports analyze_profile_jfr for the window/band machinery when available.

Usage:
  store_gate_check.py warm <stamp-dir>
  store_gate_check.py cold <stamp-dir>
  store_gate_check.py --selftest
"""

import glob
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

CLK_TCK = 100.0

HIT_RATIO_FLOOR = 0.95
DISK_SUBMITTED_CEIL_FRAC = 0.02
BAND_CEIL_FRAC = 0.01
BAND_CPU_CUT_FLOOR = 0.70
HIT_P95_SPEEDUP_FLOOR = 5.0
COLD_REGRESSION_CEIL = 0.10


def load_json(path):
    with open(path) as f:
        return json.load(f)


def load_jsonl(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return rows


def active_window_cpu_seconds(cpu_rows):
    """(window_cpu_seconds, window_len_s) of the server process over the wire-slope
    active window — the §0 metric-2 CPU input. Mirrors analyze_profile_jfr.active_window
    but stdlib-local so the check runs without the jfr tool."""
    rows = [r for r in cpu_rows if isinstance(r.get("srv_cpu"), (int, float))]
    if len(rows) < 5:
        raise RuntimeError("cpu.jsonl too short for a window")
    ts = [r["t"] for r in rows]
    wire = [r.get("wire_bytes") or 0 for r in rows]
    for i in range(1, len(wire)):
        if wire[i] < wire[i - 1]:
            wire[i] = wire[i - 1]
    # smoothed slope over +-2 neighbors
    slopes = []
    for i in range(len(rows)):
        lo, hi = max(0, i - 2), min(len(rows) - 1, i + 2)
        dt = ts[hi] - ts[lo]
        slopes.append((wire[hi] - wire[lo]) / dt if dt > 0 else 0.0)
    peak = max(slopes) if slopes else 0.0
    thresh = max(50_000.0, peak * 0.01)
    active = [i for i, s in enumerate(slopes) if s > thresh]
    if not active:
        raise RuntimeError("no active window in cpu.jsonl")
    a, b = active[0], active[-1]
    cpu_s = (rows[b]["srv_cpu"] - rows[a]["srv_cpu"]) / CLK_TCK
    return cpu_s, ts[b] - ts[a]


def bands_for_run(run_dir):
    """bands dict from bands.json (written by analyze_profile_jfr) or computed on the
    fly if the jfr tool is available; None when impossible."""
    cached = os.path.join(run_dir, "bands.json")
    if os.path.exists(cached):
        return load_json(cached)
    jfr_file = os.path.join(run_dir, "server-benchmark.jfr")
    cpu_file = os.path.join(run_dir, "cpu.jsonl")
    if not (os.path.exists(jfr_file) and os.path.exists(cpu_file)):
        return None
    try:
        import analyze_profile_jfr as apj
        apj.analyze_jfr(run_dir, apj.find_jfr_tool())
        return load_json(cached)
    except SystemExit:
        return None
    except Exception as e:  # noqa: BLE001 — band absence degrades, never crashes the gate
        print(f"  [warn] band analysis failed for {run_dir}: {e!r}")
        return None


class Run:
    def __init__(self, run_dir):
        self.dir = run_dir
        self.server = load_json(os.path.join(run_dir, "server.json"))
        self.client = load_json(os.path.join(run_dir, "client.json"))
        self.store = self.server.get("store", {})
        self.columns = self.client.get("columns_received") or 0
        cpu_rows = load_jsonl(os.path.join(run_dir, "cpu.jsonl"))
        self.cpu_s, self.window_s = active_window_cpu_seconds(cpu_rows)
        self.bands = bands_for_run(run_dir)

    def lss_band_cpu_per_col_us(self):
        if not self.bands or not self.columns:
            return None
        share = self.bands["lss_attributed_samples"] / max(1, self.bands["total_exec_samples"])
        return share * self.cpu_s / self.columns * 1e6

    def jvm_cpu_s_per_1k(self):
        return self.cpu_s / max(1, self.columns) * 1000


def pair_runs(stamp_dir):
    pairs = []
    for off_dir in sorted(glob.glob(os.path.join(stamp_dir, "off-rep*"))):
        m = re.search(r"off-rep(\d+)$", off_dir)
        if not m:
            continue
        on_dir = os.path.join(stamp_dir, f"on-rep{m.group(1)}")
        if os.path.isdir(on_dir):
            pairs.append((int(m.group(1)), Run(off_dir), Run(on_dir)))
    if not pairs:
        sys.exit(f"no off/on rep pairs under {stamp_dir}")
    return pairs


def check_warm(stamp_dir):
    failures = []
    cuts, jvm_deltas = [], []
    for rep, off, on in pair_runs(stamp_dir):
        print(f"— rep {rep}:")
        hits = on.store.get("hits", 0)
        misses = on.store.get("misses", 0)
        ratio = hits / max(1, hits + misses)
        print(f"  [1] hit ratio: {hits}/{hits + misses} = {ratio:.3f} (floor {HIT_RATIO_FLOOR})")
        if ratio < HIT_RATIO_FLOOR:
            failures.append(f"rep{rep}: hit ratio {ratio:.3f} < {HIT_RATIO_FLOOR}")
        submitted = (on.server.get("disk_reader") or {}).get("submitted", 0)
        ceil = DISK_SUBMITTED_CEIL_FRAC * max(1, on.columns)
        print(f"  [1] disk.submitted: {submitted} (ceil {ceil:.0f} = "
              f"{DISK_SUBMITTED_CEIL_FRAC:.0%} of {on.columns} cols)")
        if submitted > ceil:
            failures.append(f"rep{rep}: disk.submitted {submitted} > {ceil:.0f}")
        if on.bands:
            tot = max(1, on.bands["total_exec_samples"])
            nbt_ser = on.bands["bands"].get("nbt", 0) + on.bands["bands"].get("serialize", 0)
            print(f"  [1] nbt+serialize band: {nbt_ser}/{tot} = {nbt_ser / tot:.3%} "
                  f"(ceil {BAND_CEIL_FRAC:.0%}; zip band = "
                  f"{on.bands['bands'].get('zip', 0)} — part-vanilla, not gated)")
            if nbt_ser / tot > BAND_CEIL_FRAC:
                failures.append(f"rep{rep}: nbt+serialize bands {nbt_ser / tot:.2%} > "
                                f"{BAND_CEIL_FRAC:.0%}")
        else:
            print("  [1] WARN: no band data (jfr tool or files missing) — band gate skipped")
        off_cpu, on_cpu = off.lss_band_cpu_per_col_us(), on.lss_band_cpu_per_col_us()
        if off_cpu and on_cpu is not None:
            cut = 1 - on_cpu / off_cpu
            cuts.append(cut)
            print(f"  [2] LSS-band CPU/col: off {off_cpu:.0f}us -> on {on_cpu:.0f}us "
                  f"(cut {cut:.1%}, floor {BAND_CPU_CUT_FLOOR:.0%})")
            if cut < BAND_CPU_CUT_FLOOR:
                failures.append(f"rep{rep}: band CPU cut {cut:.1%} < {BAND_CPU_CUT_FLOOR:.0%}")
        else:
            print("  [2] WARN: band CPU/col unavailable — metric 2 skipped")
        jvm_delta = 1 - on.jvm_cpu_s_per_1k() / max(1e-9, off.jvm_cpu_s_per_1k())
        jvm_deltas.append(jvm_delta)
        print(f"  [3] whole-JVM CPU-s/1k: off {off.jvm_cpu_s_per_1k():.1f} -> "
              f"on {on.jvm_cpu_s_per_1k():.1f} ({jvm_delta:+.1%}; informational, "
              "expected ~-35..50%)")
        off_read_us = ((off.server.get("disk_reader") or {}).get("avg_read_time_ms", 0)) * 1000
        p95 = on.store.get("read_p95_us", 0)
        if off_read_us > 0 and p95 > 0:
            speedup = off_read_us / p95
            print(f"  [3] hit p95 {p95}us vs off mean read {off_read_us:.0f}us "
                  f"(speedup {speedup:.1f}x, floor {HIT_P95_SPEEDUP_FLOOR}x)")
            if speedup < HIT_P95_SPEEDUP_FLOOR:
                failures.append(f"rep{rep}: hit p95 speedup {speedup:.1f}x < "
                                f"{HIT_P95_SPEEDUP_FLOOR}x")
        else:
            print(f"  [3] WARN: latency inputs missing (off mean {off_read_us:.0f}us, "
                  f"on p95 {p95}us) — latency gate skipped")
        drops = on.store.get("deposit_drops", 0)
        errs = on.store.get("errors", 0)
        if errs:
            failures.append(f"rep{rep}: store.errors = {errs}")
        if drops:
            print(f"  [!] store.deposit_drops = {drops} (eyeball)")
    if cuts:
        print(f"median band-CPU cut: {statistics.median(cuts):.1%}")
    if jvm_deltas:
        print(f"median whole-JVM delta: {statistics.median(jvm_deltas):+.1%}")
    return failures


def check_cold(stamp_dir):
    failures = []
    regressions = []
    for rep, off, on in pair_runs(stamp_dir):
        off_k, on_k = off.jvm_cpu_s_per_1k(), on.jvm_cpu_s_per_1k()
        reg = on_k / max(1e-9, off_k) - 1
        regressions.append(reg)
        deposits = on.store.get("deposits", 0)
        print(f"— rep {rep}: CPU-s/1k off {off_k:.1f} -> on {on_k:.1f} ({reg:+.1%}, "
              f"ceil +{COLD_REGRESSION_CEIL:.0%}); deposits={deposits}")
        if reg > COLD_REGRESSION_CEIL:
            failures.append(f"rep{rep}: cold-path regression {reg:+.1%} > "
                            f"+{COLD_REGRESSION_CEIL:.0%}")
        if deposits == 0:
            failures.append(f"rep{rep}: on-arm made no deposits — the arm did not "
                            "exercise the cold path (config staging bug?)")
    print(f"median cold-path regression: {statistics.median(regressions):+.1%}")
    return failures


def selftest():
    import tempfile
    n = 0

    def check(cond, msg):
        nonlocal n
        assert cond, "selftest FAIL: " + msg
        n += 1

    def mk_run(d, columns, hits, misses, submitted, cpu_jiffies, p95=20,
               off_read_ms=2.0, deposits=5):
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "server.json"), "w") as f:
            json.dump({"disk_reader": {"submitted": submitted,
                                       "avg_read_time_ms": off_read_ms},
                       "store": {"hits": hits, "misses": misses, "errors": 0,
                                 "deposit_drops": 0, "deposits": deposits,
                                 "read_p95_us": p95}}, f)
        with open(os.path.join(d, "client.json"), "w") as f:
            json.dump({"columns_received": columns}, f)
        with open(os.path.join(d, "cpu.jsonl"), "w") as f:
            for i in range(30):
                f.write(json.dumps({"t": 1000 + i, "srv_cpu": i * cpu_jiffies,
                                    "wire_bytes": i * 10_000_000}) + "\n")

    with tempfile.TemporaryDirectory() as td:
        # warm: clean pair passes (band gates skip without JFR — warns, not fails)
        mk_run(os.path.join(td, "off-rep1"), 1000, 0, 0, 1000, 40)
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 5, 8)
        fails = check_warm(td)
        check(fails == [], f"clean warm pair flagged: {fails}")
        # hit-ratio floor catches
        mk_run(os.path.join(td, "on-rep1"), 1000, 500, 500, 5, 8)
        fails = check_warm(td)
        check(any("hit ratio" in f for f in fails), f"low hit ratio not caught: {fails}")
        # disk.submitted ceiling catches
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 500, 8)
        fails = check_warm(td)
        check(any("disk.submitted" in f for f in fails), f"submits not caught: {fails}")
        # p95 speedup floor catches (off mean 2ms=2000us, floor 5x -> p95 must be <=400us)
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 5, 8, p95=900)
        fails = check_warm(td)
        check(any("speedup" in f for f in fails), f"slow p95 not caught: {fails}")
    with tempfile.TemporaryDirectory() as td:
        # cold: within-ceiling passes, above-ceiling fails, zero-deposit arm fails
        mk_run(os.path.join(td, "off-rep1"), 1000, 0, 0, 1000, 40)
        mk_run(os.path.join(td, "on-rep1"), 1000, 0, 0, 1000, 43)  # +7.5%
        fails = check_cold(td)
        check(fails == [], f"clean cold pair flagged: {fails}")
        mk_run(os.path.join(td, "on-rep1"), 1000, 0, 0, 1000, 46)  # +15%
        fails = check_cold(td)
        check(any("regression" in f for f in fails), f"cold regression not caught: {fails}")
        mk_run(os.path.join(td, "on-rep1"), 1000, 0, 0, 1000, 43, deposits=0)
        fails = check_cold(td)
        check(any("no deposits" in f for f in fails), f"dead on-arm not caught: {fails}")
    print(f"store_gate_check selftest OK: {n} cases")
    return 0


def main(argv):
    if len(argv) >= 2 and argv[1] == "--selftest":
        return selftest()
    if len(argv) < 3 or argv[1] not in ("warm", "cold"):
        sys.exit(__doc__)
    stamp_dir = argv[2]
    failures = check_warm(stamp_dir) if argv[1] == "warm" else check_cold(stamp_dir)
    verdict = {"mode": argv[1], "stamp": os.path.basename(stamp_dir.rstrip("/")),
               "failures": failures, "pass": not failures}
    with open(os.path.join(stamp_dir, "gate-verdict.json"), "w") as f:
        json.dump(verdict, f, indent=1)
    if failures:
        print("GATE FAIL:")
        for x in failures:
            print(f"  - {x}")
        return 1
    print("GATE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
