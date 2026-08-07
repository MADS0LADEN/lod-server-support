#!/usr/bin/env python3
"""Validator for the move-desync tracer's JSONL output (move-desync-tracer-plan.md §3).

Usage:
    check_move_trace.py --validate FILE [FILE...]   # validate collected trace files
    check_move_trace.py --selftest                  # prove the checks pass AND catch

The row schema's writer is MoveRow.java; the shared fixture
scripts/testdata/move-trace-rows.jsonl is written by the Tier 1 golden test
(MoveRowGoldenTest) and read by this file's selftest, so writer and reader cannot
drift — the check_soak.py --selftest culture applied to the tracer. A schema change
lands in BOTH files in one commit (and bumps SCHEMA_VERSIONS when more than additive).

stdlib only. Absent-vs-null discipline (plan §0.5): a field that could not be captured
is ABSENT; the `lss` block is an explicit null on control-arm rows (that null is the
A/B label) — so `lss` is REQUIRED on every non-boot row, with null a legal value.
"""

import argparse
import json
import sys
from pathlib import Path

SCHEMA_VERSIONS = {1}

RUNG_MOONRISE = "moonrise"
RUNG_VANILLA = "vanilla"
RUNG_NONE = "none"
RUNGS = {RUNG_MOONRISE, RUNG_VANILLA, RUNG_NONE}

DIALECTS = {"v16", "v18", "v19"}

# Rows emitted mid-boot after a rotation lose their boot row to the .1 file, so a
# bootId without a boot row is legal; a boot row that is not its bootId's FIRST row
# in the file is not.
ENVELOPE_KEYS = ["v", "type", "bootId", "wallMs", "tick", "player", "name", "dim",
                 "obuf", "latency_ms", "mspt", "online", "dropped", "lss"]
BOOT_KEYS = ["v", "type", "bootId", "wallMs", "tz_offset_min", "lss_version",
             "mc_version", "moonrise", "c2me", "chunky", "rung", "config"]
LSS_KEYS = ["registered", "since_s", "caps", "proto", "send_queue", "bw_total"]
# `yielded` is OPTIONAL in the lss block (reviews B-3/A-5): A1-era jars — including the
# build staged on the live server — predate the yield and emit lss blocks without it;
# absent means "pre-yield jar", exactly like the boot key below. When present it must
# be an int. A2+ writers always emit it (the golden fixture pins that).
LSS_OPTIONAL_INT_KEYS = ["yielded"]

EVENT_COMMON_KEYS = ["origin", "claimed", "fall_flying", "speed",
                     "move_gap_ms", "move_gap_max_5s_ms"]
# `restored` is OPTIONAL on collision events: it comes from the mixin's start capture,
# and a partially-applied config leaves it absent rather than a zero triple (C-10).
COLLISION_REQUIRED_KEYS = ["simulated", "residual", "residual_h",
                           "send_state", "send_state_claimed"]
# Explicitly named (not a slice of another list — C-9a): the keys a too_quickly row can
# NEVER carry — no simulated stop exists before move(), and the wrongly/rejected-only
# discriminators are meaningless there.
TOO_QUICKLY_FORBIDDEN_KEYS = ["simulated", "residual", "residual_h", "restored",
                              "send_state", "logged_wrongly", "entity_collide",
                              "stop_block", "awaiting_tp"]
# awaiting_tp lives on flight rows only (optional bool): every event site is inside the
# not-awaiting branch of updateAwaitingTeleport, so the field is structurally false
# there — confidently wrong, the one §0.5 sin (A-2).
EVENT_FORBIDDEN_KEYS = ["awaiting_tp"]
# The boot-row config keys the E4/§4.5 analysis partitions on — a boot row without them
# is unanalyzable (C-9c). "lodYieldsToVanillaTransport" is deliberately NOT here even
# though §4.5 partitions on it: A1-era jars predate the yield, and analysis treats an
# absent key as unarmed — which is exactly what those jars ran (reviews C-8/B-3).
BOOT_CONFIG_REQUIRED_KEYS = ["bytesPerSecondLimitPerPlayer", "bytesPerSecondLimitGlobal",
                             "lodDistanceChunks"]

# Wall-clock NTP nudges must not red a whole week's collection; real ordering bugs
# (queue reorder, cross-thread emission) show up far above this.
WALLMS_TOLERANCE = 100

TYPES = {"boot", "flight", "flight_ring", "too_quickly", "wrongly", "rejected"}


class Violations:
    def __init__(self):
        self.items = []

    def add(self, line_no, what):
        self.items.append(f"line {line_no}: {what}")

    def __bool__(self):
        return bool(self.items)


def _require(row, keys, line_no, v):
    for k in keys:
        if k not in row:
            v.add(line_no, f"{row.get('type', '?')} row missing required key '{k}'")


def _forbid(row, keys, line_no, v, why):
    for k in keys:
        if k in row:
            v.add(line_no, f"{row.get('type', '?')} row must not carry '{k}' ({why})")


def _check_pos(row, key, line_no, v):
    p = row.get(key)
    if p is not None and (not isinstance(p, list) or len(p) != 3
                          or not all(isinstance(c, (int, float)) for c in p)):
        v.add(line_no, f"'{key}' must be a [x, y, z] number triple")


def _check_chunk_pair(block, key, line_no, v):
    p = block.get(key)
    if p is not None and (not isinstance(p, list) or len(p) != 2
                          or not all(isinstance(c, int) for c in p)):
        v.add(line_no, f"'{key}' must be a [cx, cz] int pair")


def check_send_state(block, line_no, v, ctx):
    if not isinstance(block, dict):
        v.add(line_no, f"{ctx} must be an object")
        return
    rung = block.get("rung")
    if rung not in RUNGS:
        v.add(line_no, f"{ctx}.rung '{rung}' is not one of {sorted(RUNGS)}")
        return
    if rung == RUNG_NONE:
        extra = set(block) - {"rung"}
        if extra:
            v.add(line_no, f"{ctx} rung=none must carry no other keys, has {sorted(extra)}")
        return
    if "anchor" not in block:
        v.add(line_no, f"{ctx} rung={rung} missing 'anchor'")
    _check_chunk_pair(block, "anchor", line_no, v)
    if rung == RUNG_MOONRISE:
        if "not_pending" in block:
            v.add(line_no, f"{ctx} rung=moonrise must not carry 'not_pending' "
                           "(the vanilla rung's strictly weaker predicate — U-12)")
        for k in ("sent_mask_5x5", "sent_r1"):
            if k not in block:
                v.add(line_no, f"{ctx} rung=moonrise missing '{k}'")
        mask = block.get("sent_mask_5x5")
        if isinstance(mask, int) and not (0 <= mask < (1 << 25)):
            v.add(line_no, f"{ctx}.sent_mask_5x5 out of 25-bit range: {mask}")
        r1 = block.get("sent_r1")
        if isinstance(r1, int) and not (0 <= r1 < (1 << 9)):
            v.add(line_no, f"{ctx}.sent_r1 out of 9-bit range: {r1}")
        _check_chunk_pair(block, "loader_center", line_no, v)
        stage = block.get("stage")
        if stage is not None and (not isinstance(stage, int) or not 0 <= stage <= 5):
            v.add(line_no, f"{ctx}.stage must be 0..5, got {stage}")
    else:  # vanilla
        if "not_pending" not in block:
            v.add(line_no, f"{ctx} rung=vanilla missing 'not_pending'")
        for k in ("sent_mask_5x5", "sent_r1", "stage", "send_radius", "loader_center",
                  "send_queue", "send_head_stage"):
            if k in block:
                v.add(line_no, f"{ctx} rung=vanilla must not carry '{k}' "
                               "(moonrise-rung field — U-12 keeps the rungs un-aggregatable)")


def check_lss(row, line_no, v):
    if "lss" not in row:
        v.add(line_no, "row missing 'lss' (must be present — explicit null is the "
                       "control-arm label, absence is a writer bug)")
        return
    lss = row["lss"]
    if lss is None:
        return
    if not isinstance(lss, dict):
        v.add(line_no, "'lss' must be an object or null")
        return
    for k in LSS_KEYS:
        if k not in lss:
            v.add(line_no, f"lss block missing '{k}'")
    for k in LSS_OPTIONAL_INT_KEYS:
        if k in lss and not isinstance(lss[k], int):
            v.add(line_no, f"lss.{k} must be an int when present")
    if "dialect" in lss and lss["dialect"] not in DIALECTS:
        v.add(line_no, f"lss.dialect '{lss['dialect']}' is not one of {sorted(DIALECTS)} "
                       "(absent means native)")


def check_ring_samples(row, line_no, v):
    samples = row.get("samples")
    if not isinstance(samples, list) or not samples:
        v.add(line_no, "flight_ring 'samples' must be a non-empty list")
        return
    send_keys = {"anchor", "sent_mask_5x5", "loader_center"}
    for i, s in enumerate(samples):
        if not isinstance(s, dict):
            v.add(line_no, f"samples[{i}] must be an object")
            continue
        for k in ("wallMs", "pos", "speed", "obuf", "gap_ms"):
            if k not in s:
                v.add(line_no, f"samples[{i}] missing '{k}'")
        present = send_keys & set(s)
        if present and present != send_keys:
            v.add(line_no, f"samples[{i}] carries a PARTIAL send-state {sorted(present)} — "
                           "anchor/sent_mask_5x5/loader_center are all-or-none")


def check_row(row, line_no, v):
    if not isinstance(row, dict):
        v.add(line_no, "row is not a JSON object")
        return
    rv = row.get("v")
    if rv not in SCHEMA_VERSIONS:
        v.add(line_no, f"schema version {rv} not in supported {sorted(SCHEMA_VERSIONS)}")
        return
    rtype = row.get("type")
    if rtype not in TYPES:
        v.add(line_no, f"unknown row type '{rtype}'")
        return
    if rtype == "boot":
        _require(row, BOOT_KEYS, line_no, v)
        if row.get("rung") is not None and row.get("rung") not in RUNGS:
            v.add(line_no, f"boot rung '{row.get('rung')}' is not one of {sorted(RUNGS)}")
        config = row.get("config")
        if "config" in row and not isinstance(config, dict):
            v.add(line_no, "boot 'config' must be an object")
        elif isinstance(config, dict):
            for k in BOOT_CONFIG_REQUIRED_KEYS:
                if k not in config:
                    v.add(line_no, f"boot config missing partition key '{k}' "
                                   "(the E4/yield analysis partitions on it)")
        return

    _require(row, ENVELOPE_KEYS, line_no, v)
    check_lss(row, line_no, v)

    if rtype == "flight":
        _require(row, ["pos", "speed", "move_gap_ms", "move_gap_max_5s_ms", "send_state",
                       "loaded_chunks"], line_no, v)
        _check_pos(row, "pos", line_no, v)
        if "awaiting_tp" in row and not isinstance(row["awaiting_tp"], bool):
            v.add(line_no, "flight 'awaiting_tp' must be a bool when present")
        if "send_state" in row:
            check_send_state(row["send_state"], line_no, v, "send_state")
    elif rtype == "flight_ring":
        check_ring_samples(row, line_no, v)
    elif rtype == "too_quickly":
        _require(row, EVENT_COMMON_KEYS + ["delta_packets", "delta_packets_used",
                                           "expected_dist_sq", "send_state_claimed"], line_no, v)
        # too_quickly returns BEFORE move() runs — these fields cannot exist (U-14),
        # and awaiting_tp is flight-only (A-2).
        _forbid(row, TOO_QUICKLY_FORBIDDEN_KEYS, line_no, v,
                "no simulated stop exists before move() runs / flight-only field")
        for key in ("origin", "claimed"):
            _check_pos(row, key, line_no, v)
        if "send_state_claimed" in row:
            check_send_state(row["send_state_claimed"], line_no, v, "send_state_claimed")
    elif rtype in ("wrongly", "rejected"):
        _require(row, EVENT_COMMON_KEYS + COLLISION_REQUIRED_KEYS, line_no, v)
        _forbid(row, EVENT_FORBIDDEN_KEYS, line_no, v,
                "awaiting_tp is structurally false at event sites — flight-only (A-2)")
        for key in ("origin", "claimed", "simulated", "restored"):
            _check_pos(row, key, line_no, v)
        for key in ("send_state", "send_state_claimed"):
            if key in row:
                check_send_state(row[key], line_no, v, key)
        if rtype == "rejected":
            if "logged_wrongly" not in row:
                v.add(line_no, "rejected row missing 'logged_wrongly' (the silent-rejection "
                               "discriminator is the tracer's whole point)")
        else:
            _forbid(row, ["logged_wrongly"], line_no, v,
                    "tautological on the warn-site row; it belongs to 'rejected'")


def check_file_order(rows, v):
    """Per-boot monotonic wallMs (small NTP tolerance) + monotone dropped + boot-first."""
    last_wall = {}
    last_dropped = {}
    seen_boot_ids = set()
    for line_no, row in rows:
        if not isinstance(row, dict):
            continue
        boot = row.get("bootId")
        if boot is None:
            continue
        if row.get("type") == "boot" and boot in seen_boot_ids:
            v.add(line_no, f"boot row for '{boot}' is not that boot's first row in the file")
        seen_boot_ids.add(boot)
        wall = row.get("wallMs")
        if isinstance(wall, int):
            prev = last_wall.get(boot)
            if prev is not None and wall < prev - WALLMS_TOLERANCE:
                v.add(line_no, f"wallMs went backwards within boot '{boot}' "
                               f"({prev} -> {wall})")
            last_wall[boot] = max(prev or wall, wall)
        dropped = row.get("dropped")
        if isinstance(dropped, int):
            prev = last_dropped.get(boot)
            if prev is not None and dropped < prev:
                v.add(line_no, f"dropped went backwards within boot '{boot}' "
                               f"({prev} -> {dropped})")
            last_dropped[boot] = max(prev or 0, dropped)


def validate_lines(lines, v):
    rows = []
    for line_no, line in enumerate(lines, start=1):
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as e:
            v.add(line_no, f"not valid JSON: {e}")
            continue
        rows.append((line_no, row))
        check_row(row, line_no, v)
    check_file_order(rows, v)


def validate_file(path):
    v = Violations()
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError as e:
        v.add(0, f"cannot read {path}: {e}")
        return v
    validate_lines(lines, v)
    return v


# ---------------------------------------------------------------- selftest

def _fixture_rows():
    fixture = Path(__file__).resolve().parent / "testdata" / "move-trace-rows.jsonl"
    return [json.loads(l) for l in fixture.read_text(encoding="utf-8").splitlines() if l.strip()]


def _check_one(row):
    v = Violations()
    check_row(row, 1, v)
    return v


def selftest():
    """Prove every check PASSES the shared fixture and CATCHES a minimally-doctored row.

    A check that never fires is theater — every rule gets a clean case and a hit case
    (the check_soak.py selftest culture)."""
    cases = [0]

    def clean(label, v):
        cases[0] += 1
        assert not v, f"{label}: unexpected violations: {v.items}"

    def hits(label, v, needle):
        cases[0] += 1
        assert v, f"{label}: expected a violation, got none"
        assert any(needle in item for item in v.items), \
            f"{label}: expected a violation mentioning '{needle}', got {v.items}"

    rows = _fixture_rows()
    # 7 rows since C1: the six row types plus a second too_quickly carrying the
    # v19 dialect (the writer's golden and this reader widen in the same commit).
    assert len(rows) == 7, f"fixture must carry the seven pinned rows, got {len(rows)}"
    by_type = {}
    for r in rows:
        by_type.setdefault(r["type"], r)  # FIRST of each type: doctored cases pin those
    assert set(by_type) == TYPES, f"fixture types drifted: {sorted(by_type)}"
    fixture_dialects = {r["lss"]["dialect"] for r in rows
                        if r.get("lss") and r["lss"].get("dialect")}
    assert fixture_dialects == DIALECTS, \
        f"fixture dialect coverage drifted: {sorted(fixture_dialects)}"

    # The shared fixture — the writer's own output — passes whole-file validation.
    v = Violations()
    validate_lines([json.dumps(r) for r in rows], v)
    clean("shared fixture", v)

    def doctored(base, mutate):
        row = json.loads(json.dumps(base))
        mutate(row)
        return row

    # Envelope / versioning.
    hits("unknown schema version", _check_one(doctored(by_type["flight"],
         lambda r: r.update(v=99))), "schema version")
    hits("unknown type", _check_one(doctored(by_type["flight"],
         lambda r: r.update(type="warp"))), "unknown row type")
    hits("missing envelope key", _check_one(doctored(by_type["flight"],
         lambda r: r.pop("obuf"))), "missing required key 'obuf'")
    hits("missing lss entirely", _check_one(doctored(by_type["flight"],
         lambda r: r.pop("lss"))), "missing 'lss'")
    clean("lss null is legal (control arm)", _check_one(doctored(by_type["flight"],
          lambda r: r.update(lss=None))))
    hits("lss block missing key", _check_one(doctored(by_type["flight"],
         lambda r: r["lss"].pop("proto"))), "lss block missing 'proto'")
    clean("A1-era lss block without yielded is legal", _check_one(doctored(
        by_type["flight"], lambda r: r["lss"].pop("yielded"))))
    hits("yielded wrong type", _check_one(doctored(by_type["flight"],
         lambda r: r["lss"].update(yielded="7"))), "must be an int")
    hits("bad dialect", _check_one(doctored(by_type["too_quickly"],
         lambda r: r["lss"].update(dialect="v12"))), "dialect")

    # Boot.
    hits("boot missing rung", _check_one(doctored(by_type["boot"],
         lambda r: r.pop("rung"))), "missing required key 'rung'")
    hits("boot config missing partition key", _check_one(doctored(by_type["boot"],
         lambda r: r["config"].pop("lodDistanceChunks"))), "partition key")

    # Send-state rung consistency (U-12).
    hits("mask on vanilla rung", _check_one(doctored(by_type["too_quickly"],
         lambda r: r["send_state_claimed"].update(sent_mask_5x5=1))),
         "rung=vanilla must not carry 'sent_mask_5x5'")
    hits("not_pending on moonrise rung", _check_one(doctored(by_type["flight"],
         lambda r: r["send_state"].update(not_pending=True))),
         "rung=moonrise must not carry 'not_pending'")
    hits("rung none with extra keys", _check_one(doctored(by_type["rejected"],
         lambda r: r["send_state"].update(anchor=[0, 0]))),
         "rung=none must carry no other keys")
    hits("mask out of range", _check_one(doctored(by_type["flight"],
         lambda r: r["send_state"].update(sent_mask_5x5=1 << 25))), "25-bit range")
    hits("stage out of range", _check_one(doctored(by_type["flight"],
         lambda r: r["send_state"].update(stage=6))), "stage must be 0..5")

    # Per-type schema split (U-14).
    hits("simulated on too_quickly", _check_one(doctored(by_type["too_quickly"],
         lambda r: r.update(simulated=[0, 0, 0]))), "must not carry 'simulated'")
    hits("stop_block on too_quickly", _check_one(doctored(by_type["too_quickly"],
         lambda r: r.update(stop_block="minecraft:stone"))), "must not carry 'stop_block'")
    hits("rejected missing logged_wrongly", _check_one(doctored(by_type["rejected"],
         lambda r: r.pop("logged_wrongly"))), "missing 'logged_wrongly'")
    hits("logged_wrongly on wrongly", _check_one(doctored(by_type["wrongly"],
         lambda r: r.update(logged_wrongly=True))), "must not carry 'logged_wrongly'")
    hits("wrongly missing residual", _check_one(doctored(by_type["wrongly"],
         lambda r: r.pop("residual"))), "missing required key 'residual'")
    clean("restored optional on collision events (C-10)", _check_one(doctored(
        by_type["wrongly"], lambda r: r.pop("restored"))))
    hits("bad pos triple", _check_one(doctored(by_type["flight"],
         lambda r: r.update(pos=[1, 2]))), "number triple")
    hits("expected_dist_sq renamed (B-6)", _check_one(doctored(by_type["too_quickly"],
         lambda r: (r.pop("expected_dist_sq"), r.update(expected_dist=0.09)))),
         "missing required key 'expected_dist_sq'")
    # awaiting_tp: flight-only (A-2).
    hits("awaiting_tp on an event row", _check_one(doctored(by_type["rejected"],
         lambda r: r.update(awaiting_tp=False))), "must not carry 'awaiting_tp'")
    clean("awaiting_tp absent on flight is legal", _check_one(doctored(by_type["flight"],
          lambda r: r.pop("awaiting_tp"))))
    hits("awaiting_tp wrong type on flight", _check_one(doctored(by_type["flight"],
         lambda r: r.update(awaiting_tp=1))), "must be a bool")

    # Ring samples.
    hits("ring sample partial send-state", _check_one(doctored(by_type["flight_ring"],
         lambda r: r["samples"][0].pop("loader_center"))), "PARTIAL send-state")
    hits("ring sample missing gap", _check_one(doctored(by_type["flight_ring"],
         lambda r: r["samples"][1].pop("gap_ms"))), "missing 'gap_ms'")
    hits("empty ring", _check_one(doctored(by_type["flight_ring"],
         lambda r: r.update(samples=[]))), "non-empty")

    # File-order laws.
    def file_violations(rowlist):
        v = Violations()
        validate_lines([json.dumps(r) for r in rowlist], v)
        return v

    back = doctored(by_type["flight"], lambda r: r.update(wallMs=r["wallMs"] - 5000))
    hits("wallMs backwards in boot", file_violations([by_type["flight"], back]),
         "wallMs went backwards")
    clean("wallMs NTP-tolerance", file_violations(
        [by_type["flight"],
         doctored(by_type["flight"], lambda r: r.update(wallMs=r["wallMs"] - 50))]))
    drop_back = doctored(by_type["flight"],
                         lambda r: r.update(wallMs=r["wallMs"] + 1, dropped=0))
    drop_fwd = doctored(by_type["flight"],
                        lambda r: r.update(wallMs=r["wallMs"] + 2, dropped=5))
    hits("dropped backwards in boot", file_violations([drop_fwd, drop_back]),
         "dropped went backwards")
    hits("boot row not first for its boot", file_violations(
        [by_type["flight"], by_type["boot"]]), "not that boot's first row")
    clean("rotation tail without boot row", file_violations([by_type["flight"]]))

    print(f"selftest OK: {cases[0]} cases — writer fixture + doctored-row catches all hold")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--validate", nargs="+", metavar="FILE",
                        help="validate one or more trace JSONL files")
    parser.add_argument("--selftest", action="store_true")
    opts = parser.parse_args()

    if opts.selftest:
        return selftest()
    if opts.validate:
        rc = 0
        for path in opts.validate:
            v = validate_file(path)
            if v:
                rc = 1
                print(f"VALIDATE FAIL: {path}")
                for item in v.items:
                    print(f"  {item}")
            else:
                print(f"VALIDATE PASS: {path}")
        return rc
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
