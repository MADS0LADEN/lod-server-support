#!/usr/bin/env bash
set -euo pipefail

# Phase 3 MSPT-pairing gate (lod-store-implementation-plan.md Phase 3): runs the
# store-save-storm scenario twice — lodStore=full (the gated arm, with the save-hook
# deposit named check) and lodStore=off (the pairing arm, laws only) — and compares
# paired MSPT over the STORM window. The save-hook path must be tick-neutral: the
# serialization it consumes was already paid by the DirtyContentFilter hash; the only
# added work is a queue offer, so a paired MSPT delta outside noise means the hook is
# doing something it shouldn't on the save path.
#
# Usage: ./scripts/store_save_storm.sh
# Noise bound: |mean_on - mean_off| <= max(2.0 ms, 40% of mean_off) over the storm
# window (t in [60s, 115s] of each run — the save-alls fire at 65..110 s).

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RESULTS_ROOT="$PROJECT_ROOT/soak-results"
WRAPPER_START_EPOCH="$(date +%s)"

log() { echo "[save-storm] $*"; }

latest_results() { # <scenario>
    local d
    d="$(ls -1d "$RESULTS_ROOT/$1-"2* 2>/dev/null | sort | tail -1)"
    if [[ -z "$d" || "$(stat -c %Y "$d")" -lt "$WRAPPER_START_EPOCH" ]]; then
        echo "[save-storm] ERROR: no fresh results dir for $1 (latest: ${d:-none})" >&2
        exit 1
    fi
    echo "$d"
}

log "=== arm: store-save-storm (lodStore=full, gated) ==="
"$PROJECT_ROOT/scripts/soak.sh" store-save-storm
ON_DIR="$(latest_results store-save-storm)"
log "=== arm: store-save-storm-off (lodStore=off, MSPT pair) ==="
"$PROJECT_ROOT/scripts/soak.sh" store-save-storm-off
OFF_DIR="$(latest_results store-save-storm-off)"

log "paired MSPT verdict over the storm window"
python3 - "$ON_DIR/server.jsonl" "$OFF_DIR/server.jsonl" <<'EOF'
import json, statistics, sys

def storm_mspt(path):
    rows = [json.loads(l) for l in open(path, encoding="utf-8")
            if '"snapshot"' in l]
    t0 = rows[0]["wallMs"]
    vals = [r["mspt_avg_window"] for r in rows
            if 60_000 <= r["wallMs"] - t0 <= 115_000
            and isinstance(r.get("mspt_avg_window"), (int, float))
            and r["mspt_avg_window"] >= 0]
    if len(vals) < 5:
        print(f"[save-storm] FAIL: only {len(vals)} MSPT samples in the storm window "
              f"of {path}")
        sys.exit(1)
    return statistics.mean(vals)

on, off = storm_mspt(sys.argv[1]), storm_mspt(sys.argv[2])
delta = on - off
bound = max(2.0, 0.40 * off)
print(f"[save-storm]   storm-window MSPT: off {off:.2f} ms -> on {on:.2f} ms "
      f"(delta {delta:+.2f} ms, bound ±{bound:.2f} ms)")
if abs(delta) > bound:
    print("[save-storm] FAIL: paired MSPT delta outside noise — the save-hook deposit "
          "path is loading the tick")
    sys.exit(1)
print("[save-storm] paired MSPT verdict: PASS (save-hook path tick-neutral)")
EOF
log "save-storm gate PASS"
