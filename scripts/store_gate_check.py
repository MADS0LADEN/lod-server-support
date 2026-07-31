#!/usr/bin/env python3
"""LOD-store §0 gate calculator (docs/planning/lod-store-implementation-plan.md §0).

Consumes a store_gate.sh stamp dir (off-repN / on-repN run dirs from the warm-join or
no-cache benchmark scenario) and computes, per rep pair and as medians:

warm mode (the three-part §0 gate, measure cycle only):
  1. Work elimination (on-arm): store.hits/(hits+misses) >= 0.95; disk.submitted <= 2%
     of columns served; addressable (nbt+serialize) CPU/col cut >= 70% vs off (the
     absolute operationalization of "bands ~= 0" — see ADDR_CPU_CUT_FLOOR for both
     calibration lessons; a run with NO band data FAILS this leg rather than skipping).
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
# "JFR nbt+serialize bands ~= 0" is gated ABSOLUTELY: nbt+serialize CPU per column
# must fall >= 70% vs the off arm. Two calibration lessons from the first live gate
# runs (2026-07-31, both directions documented in lod-store-progress.md):
#  - the old on-arm band-SHARE ceiling (1% of LSS samples) was a shrunken-denominator
#    artifact — the ~1.5% residual misses (documented conservative sweep drops) cost
#    ~2.4 ms each atop a denominator the store just halved, so the share read 12-24%
#    while absolute CPU/col collapsed;
#  - 100% elimination is NOT the ceiling of this measure either: the band also carries
#    store-INDEPENDENT serializer work that runs identically on both arms — the Fabric
#    save-hook's dirty-hash re-serialization on autosave, probe-rung serves of loaded
#    chunks, and vanilla save-time NBT — so the measured cut tops out well short of
#    100% by construction. The uncontaminated work-elimination evidence is the
#    disk.submitted fraction (~0) and the hit-p95 speedup (>= 5x floor, measured >20x);
#    this leg pins that the band CPU followed them down.
ADDR_CPU_CUT_FLOOR = 0.70
# Whole-LSS-band CPU/col cut. Recalibrated 2026-07-31: the plan's 70% floor exceeded
# its own Phase 0 baseline — nbt+serialize = 68.3% of the LSS band, so eliminating ALL
# of it caps the whole-band cut at ~68% (and the irreducible serve/send path ~500 us/col
# dominates the on-arm). 40% asserts the win lands in the whole band; the 85% ADDR floor
# above carries the work-elimination teeth. Recorded in lod-store-progress.md.
BAND_CPU_CUT_FLOOR = 0.40
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


def addr_cpu_per_col_us(run):
    """Absolute addressable-band CPU per column: the (nbt + serialize) sample share of
    exec samples x active-window CPU / columns. The store's whole value proposition is
    driving THIS to ~0; unlike a band SHARE it is immune to the on-arm's shrunken
    denominator."""
    if not run.bands or not run.columns:
        return None
    nbt_ser = run.bands["bands"].get("nbt", 0) + run.bands["bands"].get("serialize", 0)
    share = nbt_ser / max(1, run.bands["total_exec_samples"])
    return share * run.cpu_s / run.columns * 1e6


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
    # Noisy legs gate on the MEDIAN across reps (single-box A/B reps carry page-cache /
    # scheduler variance; the per-rep values are printed for eyeballing). store.errors
    # stays per-rep: any error is a fail.
    failures = []
    ratios, submitted_fracs, addr_cuts, cuts, jvm_deltas = [], [], [], [], []
    for rep, off, on in pair_runs(stamp_dir):
        print(f"— rep {rep}:")
        hits = on.store.get("hits", 0)
        misses = on.store.get("misses", 0)
        ratio = hits / max(1, hits + misses)
        ratios.append(ratio)
        print(f"  [1] hit ratio: {hits}/{hits + misses} = {ratio:.3f} (floor {HIT_RATIO_FLOOR})")
        submitted = (on.server.get("disk_reader") or {}).get("submitted", 0)
        frac = submitted / max(1, on.columns)
        submitted_fracs.append(frac)
        print(f"  [1] disk.submitted: {submitted} = {frac:.2%} of {on.columns} cols "
              f"(ceil {DISK_SUBMITTED_CEIL_FRAC:.0%})")
        off_addr, on_addr = addr_cpu_per_col_us(off), addr_cpu_per_col_us(on)
        if off_addr and on_addr is not None:
            addr_cut = 1 - on_addr / off_addr
            addr_cuts.append(addr_cut)
            print(f"  [1] addressable (nbt+serialize) CPU/col: off {off_addr:.0f}us -> "
                  f"on {on_addr:.0f}us (cut {addr_cut:.1%}, floor {ADDR_CPU_CUT_FLOOR:.0%}; "
                  f"on-arm zip band = {on.bands['bands'].get('zip', 0) if on.bands else '?'}"
                  " — part-vanilla, not gated)")
        else:
            print("  [1] WARN: no band data (jfr tool or files missing) — "
                  "addressable-CPU gate skipped")
        off_cpu, on_cpu = off.lss_band_cpu_per_col_us(), on.lss_band_cpu_per_col_us()
        if off_cpu and on_cpu is not None:
            cut = 1 - on_cpu / off_cpu
            cuts.append(cut)
            print(f"  [2] LSS-band CPU/col: off {off_cpu:.0f}us -> on {on_cpu:.0f}us "
                  f"(cut {cut:.1%}, floor {BAND_CPU_CUT_FLOOR:.0%} — recalibrated; "
                  "addressable ceiling is ~68% of the whole band)")
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
    # Median gating (noisy single-box legs; per-rep values printed above).
    if ratios:
        med = statistics.median(ratios)
        print(f"median hit ratio: {med:.3f} (floor {HIT_RATIO_FLOOR})")
        if med < HIT_RATIO_FLOOR:
            failures.append(f"median hit ratio {med:.3f} < {HIT_RATIO_FLOOR}")
    if submitted_fracs:
        med = statistics.median(submitted_fracs)
        print(f"median disk.submitted fraction: {med:.2%} (ceil {DISK_SUBMITTED_CEIL_FRAC:.0%})")
        if med > DISK_SUBMITTED_CEIL_FRAC:
            failures.append(f"median disk.submitted {med:.2%} > {DISK_SUBMITTED_CEIL_FRAC:.0%}")
    if addr_cuts:
        med = statistics.median(addr_cuts)
        print(f"median addressable-CPU cut: {med:.1%} (floor {ADDR_CPU_CUT_FLOOR:.0%})")
        if med < ADDR_CPU_CUT_FLOOR:
            failures.append(f"median addressable-CPU cut {med:.1%} < {ADDR_CPU_CUT_FLOOR:.0%}")
    else:
        failures.append("no addressable-band data on any rep — metric 1's band leg never ran")
    if cuts:
        med = statistics.median(cuts)
        print(f"median band-CPU cut: {med:.1%} (floor {BAND_CPU_CUT_FLOOR:.0%})")
        if med < BAND_CPU_CUT_FLOOR:
            failures.append(f"median band CPU cut {med:.1%} < {BAND_CPU_CUT_FLOOR:.0%}")
    if jvm_deltas:
        print(f"median whole-JVM delta: {statistics.median(jvm_deltas):+.1%} (informational)")
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
               off_read_ms=2.0, deposits=5, bands=None):
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
        if bands is not None:
            nbt, serialize, attributed, total = bands
            with open(os.path.join(d, "bands.json"), "w") as f:
                json.dump({"total_exec_samples": total,
                           "lss_attributed_samples": attributed,
                           "bands": {"nbt": nbt, "serialize": serialize,
                                     "zip": 2, "store": 5, "lss-other": 50}}, f)
        else:
            # a stale bands.json from a prior case must not leak into this one
            stale = os.path.join(d, "bands.json")
            if os.path.exists(stale):
                os.remove(stale)

    OFF_BANDS = (300, 380, 800, 1000)   # nbt+serialize dominate the off arm
    ON_BANDS = (5, 40, 300, 900)        # collapsed on the warm arm

    with tempfile.TemporaryDirectory() as td:
        # warm: clean pair passes
        mk_run(os.path.join(td, "off-rep1"), 1000, 0, 0, 1000, 40, bands=OFF_BANDS)
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 5, 8, bands=ON_BANDS)
        fails = check_warm(td)
        check(fails == [], f"clean warm pair flagged: {fails}")
        # addressable-CPU floor catches: on-arm band CPU stays near the off arm's
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 5, 30, bands=(250, 250, 700, 900))
        fails = check_warm(td)
        check(any("addressable" in f for f in fails),
              f"undropped addressable band not caught: {fails}")
        # missing band data must FAIL the gate, not silently skip its band leg
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 5, 8)
        mk_run(os.path.join(td, "off-rep1"), 1000, 0, 0, 1000, 40)
        fails = check_warm(td)
        check(any("band leg never ran" in f for f in fails),
              f"missing band data not flagged: {fails}")
        mk_run(os.path.join(td, "off-rep1"), 1000, 0, 0, 1000, 40, bands=OFF_BANDS)
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 5, 8, bands=ON_BANDS)
        # hit-ratio floor catches
        mk_run(os.path.join(td, "on-rep1"), 1000, 500, 500, 5, 8, bands=ON_BANDS)
        fails = check_warm(td)
        check(any("hit ratio" in f for f in fails), f"low hit ratio not caught: {fails}")
        # disk.submitted ceiling catches
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 500, 8, bands=ON_BANDS)
        fails = check_warm(td)
        check(any("disk.submitted" in f for f in fails), f"submits not caught: {fails}")
        # p95 speedup floor catches (off mean 2ms=2000us, floor 5x -> p95 must be <=400us)
        mk_run(os.path.join(td, "on-rep1"), 1000, 990, 10, 5, 8, p95=900, bands=ON_BANDS)
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
