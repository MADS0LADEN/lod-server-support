#!/usr/bin/env python3
"""Compressed-columns §5.2 gate calculator
(docs/planning/compressed-columns-implementation-plan.md).

Consumes a compress_gate.sh stamp dir (off-repN / on-repN pairs, arms differing ONLY in
useCompressedColumns) and evaluates, per rep pair and as median + per-rep majority
(the store_gate discipline — single-box rep spread was measured at -1.2..+10.3 pts):

  P  premise (per OFF rep): client raw-counted bytes / socket bytes_acked over the
     active window >= PREMISE_ZLIB_RATIO_FLOOR — proves connection zlib was actually ON
     (the OFF arm's deflate cost is the whole claim; a compression-disabled server
     silently guts G1 — review B4).
  G1 server whole-process CPU per received column (warm): ON cut vs OFF >=
     G1_CPU_CUT_FLOOR (0.10; Phase 0 expectation ~13-17%). cold: parity-or-better —
     ON regression <= G1_COLD_REGRESSION_CEIL.
  G1t targeted zip-band CPU/col (the noise-immune leg, review B3): the corrected zip
     band (jdk.NativeMethodSample now counted; covers java.util.zip + zstd) ON cut vs
     OFF >= G1T_ZIP_CUT_FLOOR. Missing band data on any rep is a completeness FAILURE
     (a lost JFR must not shrink the sample). Part-vanilla caveat: the band includes
     vanilla's own packet deflate — which compression REMOVES work from too (frames
     deflate ~7x less input), so the band cut is a valid signal in this A/B even
     though its absolute value is part-vanilla.
  G2 client whole-process CPU per received column: ON <= OFF x G2_CLIENT_CEIL (1.05)
     — a non-regression guard only (review B5: the ~30-40 us/col client win is
     invisible against a whole client process; the win's evidence is the client JFR,
     report-only).
  G3 wire bytes: socket bytes_acked over the active window, ON <= OFF x
     G3_WIRE_CEIL (1.15; Phase 0 measured x1.12 — the ACCEPTED trade, the ON arm
     ships MORE bytes). Plus the ON-arm counted-wire cross-check:
     service wire counter vs bytes_acked within [G3_XCHECK_LO, G3_XCHECK_HI]
     (frames are incompressible, so counted ~= socket on the ON arm only).
  G4 riders: columns_received parity across arms (else per-col normalization lies);
     total_not_generated == 0 on every run.

Exit 0 = all gates pass, 1 = any failure. Stdlib; reuses store_gate_check's window /
band / pairing machinery.

Usage:
  compress_gate_check.py warm <stamp-dir>
  compress_gate_check.py cold <stamp-dir>
  compress_gate_check.py --selftest
"""

import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import store_gate_check as sgc  # noqa: E402 — window/band/pairing machinery

CLK_TCK = 100.0

PREMISE_ZLIB_RATIO_FLOOR = 3.0   # raw:socket; Phase 0 corpus deflate-6 = 7.08, diluted
                                 # by vanilla traffic + envelope — 3.0 still cleanly
                                 # separates "zlib on" from "zlib off" (~1.0)
G1_CPU_CUT_FLOOR = 0.10          # plan §5.2: m = 0.10, Phase-0-derived expectation 13-17%
G1_COLD_REGRESSION_CEIL = 0.05   # cold: parity-or-better (zstd+small-deflate vs big-deflate)
G1T_ZIP_CUT_FLOOR = 0.50         # Phase 0: 574->54 (warm) / 551->101 (live) in-band
G2_CLIENT_CEIL = 1.05
G3_WIRE_CEIL = 1.15              # Phase 0 measured 1.12
G3_XCHECK_LO, G3_XCHECK_HI = 0.7, 1.3
G4_COLUMNS_PARITY_TOL = 0.05


def client_cpu_and_wire(run):
    """(client_cpu_s, wire_acked_bytes) over the SAME wire-slope active window the
    server CPU uses (cpu.jsonl rows carry srv_cpu/cli_cpu/wire_bytes together)."""
    rows = [r for r in sgc.load_jsonl(os.path.join(run.dir, "cpu.jsonl"))
            if isinstance(r.get("srv_cpu"), (int, float))]
    if len(rows) < 5:
        return None, None
    ts = [r["t"] for r in rows]
    wire = [r.get("wire_bytes") or 0 for r in rows]
    for i in range(1, len(wire)):
        if wire[i] < wire[i - 1]:
            wire[i] = wire[i - 1]
    slopes = []
    for i in range(len(rows)):
        lo, hi = max(0, i - 2), min(len(rows) - 1, i + 2)
        dt = ts[hi] - ts[lo]
        slopes.append((wire[hi] - wire[lo]) / dt if dt > 0 else 0.0)
    peak = max(slopes) if slopes else 0.0
    thresh = max(50_000.0, peak * 0.01)
    active = [i for i, s in enumerate(slopes) if s > thresh]
    if not active:
        return None, None
    a, b = active[0], active[-1]
    cli = None
    if isinstance(rows[a].get("cli_cpu"), (int, float)) \
            and isinstance(rows[b].get("cli_cpu"), (int, float)):
        cli = (rows[b]["cli_cpu"] - rows[a]["cli_cpu"]) / CLK_TCK
    acked = wire[b] - wire[a]
    return cli, acked


def zip_band_cpu_per_col_us(run):
    if not run.bands or not run.columns:
        return None
    share = run.bands["bands"].get("zip", 0) / max(1, run.bands["total_exec_samples"])
    return share * run.cpu_s / run.columns * 1e6


def counted_wire_bytes(run):
    """The service wire counter from the deep report (bandwidth.total_wire_bytes_sent)."""
    return (run.server.get("bandwidth") or {}).get("total_wire_bytes_sent")


def raw_counted_bytes(run):
    return run.client.get("bytes_received") or 0


def check(mode, stamp_dir):
    failures = []
    g1_cuts, g1t_cuts, g2_ratios, g3_ratios = [], [], [], []
    n_pairs = 0
    for rep, off, on in sgc.pair_runs(stamp_dir):
        n_pairs += 1
        print(f"— rep {rep}:")

        # P: premise (OFF arm) — zlib genuinely on
        off_cli_cpu, off_acked = client_cpu_and_wire(off)
        raw_off = raw_counted_bytes(off)
        if off_acked and raw_off:
            ratio = raw_off / off_acked
            print(f"  [P] OFF zlib ratio: raw {raw_off} / acked {off_acked} = {ratio:.2f} "
                  f"(floor {PREMISE_ZLIB_RATIO_FLOOR})")
            if ratio < PREMISE_ZLIB_RATIO_FLOOR:
                failures.append(f"rep{rep}: OFF-arm zlib ratio {ratio:.2f} < "
                                f"{PREMISE_ZLIB_RATIO_FLOOR} — connection compression is"
                                " not on; G1 measures nothing (review B4)")
        else:
            failures.append(f"rep{rep}: premise inputs missing (acked={off_acked},"
                            f" raw={raw_off})")

        # G1: server whole-process CPU/col
        off_cpu, on_cpu = off.jvm_cpu_s_per_1k(), on.jvm_cpu_s_per_1k()
        cut = 1 - on_cpu / max(1e-9, off_cpu)
        g1_cuts.append(cut)
        print(f"  [G1] server CPU-s/1k cols: off {off_cpu:.1f} -> on {on_cpu:.1f} "
              f"(cut {cut:+.1%})")

        # G1t: corrected zip-band CPU/col
        off_zip, on_zip = zip_band_cpu_per_col_us(off), zip_band_cpu_per_col_us(on)
        if off_zip and on_zip is not None:
            zcut = 1 - on_zip / off_zip
            g1t_cuts.append(zcut)
            print(f"  [G1t] zip-band CPU/col: off {off_zip:.0f}us -> on {on_zip:.0f}us "
                  f"(cut {zcut:+.1%}, floor {G1T_ZIP_CUT_FLOOR:.0%})")
        else:
            print("  [G1t] WARN: no band data for this rep")

        # G2: client whole-process CPU/col (non-regression)
        on_cli_cpu, on_acked = client_cpu_and_wire(on)
        if off_cli_cpu and on_cli_cpu and off.columns and on.columns:
            off_c = off_cli_cpu / off.columns
            on_c = on_cli_cpu / on.columns
            r = on_c / max(1e-12, off_c)
            g2_ratios.append(r)
            print(f"  [G2] client CPU/col: off {off_c * 1e6:.0f}us -> on {on_c * 1e6:.0f}us "
                  f"(ratio {r:.3f}, ceil {G2_CLIENT_CEIL})")
        else:
            print("  [G2] WARN: client CPU inputs missing — leg skipped this rep")

        # G3: socket bytes over the window + the ON-arm counted-wire cross-check
        if off_acked and on_acked:
            wr = on_acked / off_acked
            g3_ratios.append(wr)
            print(f"  [G3] socket bytes: off {off_acked} -> on {on_acked} "
                  f"(ratio {wr:.3f}, ceil {G3_WIRE_CEIL} — the accepted trade)")
        else:
            failures.append(f"rep{rep}: G3 socket inputs missing")
        cw = counted_wire_bytes(on)
        if cw and on_acked:
            x = cw / on_acked
            print(f"  [G3x] ON counted wire {cw} / acked {on_acked} = {x:.2f} "
                  f"(band {G3_XCHECK_LO}..{G3_XCHECK_HI})")
            if not (G3_XCHECK_LO <= x <= G3_XCHECK_HI):
                failures.append(f"rep{rep}: counted-wire cross-check {x:.2f} outside "
                                f"[{G3_XCHECK_LO}, {G3_XCHECK_HI}] — the wire_bytes"
                                " counter or the window drifted")
        else:
            print(f"  [G3x] WARN: counted-wire cross-check inputs missing (counted={cw})")

        # G4 riders
        if off.columns and on.columns:
            par = abs(on.columns - off.columns) / max(1, off.columns)
            print(f"  [G4] columns_received: off {off.columns} / on {on.columns} "
                  f"(|delta| {par:.2%}, tol {G4_COLUMNS_PARITY_TOL:.0%})")
            if par > G4_COLUMNS_PARITY_TOL:
                failures.append(f"rep{rep}: columns parity {par:.2%} > "
                                f"{G4_COLUMNS_PARITY_TOL:.0%} — per-col normalization"
                                " is comparing different workloads")
        for arm_name, run in (("off", off), ("on", on)):
            ng = run.client.get("total_not_generated")
            if ng:
                failures.append(f"rep{rep} {arm_name}: total_not_generated = {ng}")

    # Aggregate: median AND per-rep majority (the store_gate rule, stated in the plan)
    def gate(name, values, limit, higher_is_better, fmt):
        if not values:
            failures.append(f"{name}: no data")
            return
        med = statistics.median(values)
        ok = (lambda v: v >= limit) if higher_is_better else (lambda v: v <= limit)
        passing = sum(1 for v in values if ok(v))
        majority = len(values) // 2 + 1
        print(f"median {name}: {fmt(med)} (limit {fmt(limit)}; {passing}/{len(values)}"
              f" reps within limit, need >= {majority})")
        if not ok(med):
            failures.append(f"median {name} {fmt(med)} vs limit {fmt(limit)}")
        if passing < majority:
            failures.append(f"only {passing}/{len(values)} reps within {name} limit")

    pct = lambda v: f"{v:+.1%}"
    if mode == "warm":
        gate("G1 server CPU/col cut", g1_cuts, G1_CPU_CUT_FLOOR, True, pct)
    else:
        gate("G1 cold regression (=-cut)", [-c for c in g1_cuts],
             G1_COLD_REGRESSION_CEIL, False, pct)
    if len(g1t_cuts) < n_pairs:
        failures.append(f"G1t band data on only {len(g1t_cuts)}/{n_pairs} reps — every"
                        " rep must carry bands (a lost JFR must not shrink the sample)")
    gate("G1t zip-band cut", g1t_cuts, G1T_ZIP_CUT_FLOOR, True, pct)
    gate("G2 client CPU ratio", g2_ratios, G2_CLIENT_CEIL, False, lambda v: f"{v:.3f}")
    gate("G3 wire ratio", g3_ratios, G3_WIRE_CEIL, False, lambda v: f"{v:.3f}")

    print()
    if failures:
        print("FAIL:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"PASS — all {mode} gates green over {n_pairs} rep pair(s)")
    return 0


def selftest():
    import tempfile
    failures = []

    def check_case(cond, msg):
        (print(f"  ok: {msg}") if cond else failures.append(msg))

    def mk_run(d, columns, cpu_jiffies_per_row, cli_jiffies_per_row, wire_per_row,
               zip_samples, total_samples, raw_bytes, counted_wire=None, ng=0):
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "server.json"), "w") as f:
            json.dump({"store": {},
                       "bandwidth": {"total_wire_bytes_sent":
                                     counted_wire if counted_wire is not None
                                     else wire_per_row * 30}}, f)
        with open(os.path.join(d, "client.json"), "w") as f:
            json.dump({"columns_received": columns, "bytes_received": raw_bytes,
                       "total_not_generated": ng}, f)
        with open(os.path.join(d, "cpu.jsonl"), "w") as f:
            for i in range(31):
                f.write(json.dumps({"t": 1000 + i, "srv_cpu": i * cpu_jiffies_per_row,
                                    "cli_cpu": i * cli_jiffies_per_row,
                                    "wire_bytes": i * wire_per_row}) + "\n")
        with open(os.path.join(d, "bands.json"), "w") as f:
            json.dump({"lss_attributed_samples": zip_samples,
                       "total_exec_samples": total_samples,
                       "bands": {"zip": zip_samples}}, f)

    with tempfile.TemporaryDirectory() as td:
        # Consistent warm pair: ON cuts CPU 20%, zip 80%; wire +12%; zlib ratio 6.
        mk_run(os.path.join(td, "off-rep1"), 10_000, 100, 50, 1_000_000,
               2000, 10_000, 6 * 30_000_000, counted_wire=180_000_000)
        mk_run(os.path.join(td, "on-rep1"), 10_000, 80, 50, 1_120_000,
               400, 10_000, 6 * 30_000_000, counted_wire=33_600_000)
        check_case(check("warm", td) == 0, "consistent warm pair passes")

        # Doctored: zlib off on the OFF arm (ratio ~1) must fail the premise.
        mk_run(os.path.join(td, "off-rep1"), 10_000, 100, 50, 1_000_000,
               2000, 10_000, 30_000_000, counted_wire=180_000_000)
        check_case(check("warm", td) == 1, "zlib-off premise is caught")

        # Restore premise; doctor the CPU cut to ~0 — G1 must fail.
        mk_run(os.path.join(td, "off-rep1"), 10_000, 100, 50, 1_000_000,
               2000, 10_000, 6 * 30_000_000, counted_wire=180_000_000)
        mk_run(os.path.join(td, "on-rep1"), 10_000, 99, 50, 1_120_000,
               400, 10_000, 6 * 30_000_000, counted_wire=33_600_000)
        check_case(check("warm", td) == 1, "flat server CPU is caught by G1")

        # Doctor wire to +30% — G3 must fail.
        mk_run(os.path.join(td, "on-rep1"), 10_000, 80, 50, 1_300_000,
               400, 10_000, 6 * 30_000_000, counted_wire=39_000_000)
        check_case(check("warm", td) == 1, "wire ratio over ceiling is caught by G3")

        # cold mode: parity CPU passes; +10% regression fails.
        mk_run(os.path.join(td, "on-rep1"), 10_000, 100, 50, 1_120_000,
               400, 10_000, 6 * 30_000_000, counted_wire=33_600_000)
        check_case(check("cold", td) == 0, "cold parity passes")
        mk_run(os.path.join(td, "on-rep1"), 10_000, 111, 50, 1_120_000,
               400, 10_000, 6 * 30_000_000, counted_wire=33_600_000)
        check_case(check("cold", td) == 1, "cold +11% regression is caught")

    if failures:
        print("SELFTEST FAILURES:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("compress_gate_check selftest OK: 6 cases")
    return 0


def main(argv):
    if len(argv) == 2 and argv[1] == "--selftest":
        return selftest()
    if len(argv) != 3 or argv[1] not in ("warm", "cold"):
        print(__doc__)
        return 2
    return check(argv[1], argv[2])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
