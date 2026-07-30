#!/usr/bin/env bash
set -euo pipefail

# LOD-store §0 gate runner (docs/planning/lod-store-implementation-plan.md §0/§5):
# interleaved kill-switch A/B arms of the warm-join benchmark scenario, one rep = one
# off-arm + one on-arm back-to-back (the plan's same-session A/B discipline — never
# compare against numbers from another day/box-state).
#
# Usage:
#   store_gate.sh warm <reps> <duration> [lodStore-on-mode]   # warm-join A/B (§0 gate)
#   store_gate.sh cold <reps> <duration> [lodStore-on-mode]   # no-cache deposits A/B
#                                                             # (the ≤10% cold-path gate)
#   lodStore-on-mode defaults to "full"; Phase 1 passes "memory".
#
# Results: store-gate-results/<stamp>/<arm>-rep<N>/… then store_gate_check.py runs the
# §0 math (work-elimination, band CPU/col, non-regression) over the whole stamp dir.
#
# Env: BENCHMARK_DROP_CACHES=1 propagates to benchmark.sh (cold-page-cache variant).

MODE="${1:?warm|cold}"
REPS="${2:?reps}"
DURATION="${3:?duration-seconds}"
ON_MODE="${4:-full}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="${OUT_ROOT:-$PROJECT_ROOT/store-gate-results}"
STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
SRV_CFG_DIR="$PROJECT_ROOT/fabric/build/run/benchmark-server/config"
RESULTS="$PROJECT_ROOT/benchmark-results"

case "$MODE" in
    warm) SCENARIO="warm-join" ;;
    cold) SCENARIO="no-cache" ;;
    *) echo "[store-gate] ERROR: mode must be warm|cold" >&2; exit 1 ;;
esac

log() { echo "[store-gate] $*"; }

stage_config() { # <lodStore-value>
    mkdir -p "$SRV_CFG_DIR"
    cat > "$SRV_CFG_DIR/lss-server-config.json" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": 256,
  "diskReaderThreads": 5,
  "enableChunkGeneration": false,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": true,
  "useNbtTranscode": true,
  "lodStore": "$1"
}
EOF
}

collect() { # <run-out-dir>
    local out="$1"
    mkdir -p "$out"
    local f
    for f in server.json client.json server-populate.json client-populate.json \
             cpu.jsonl cpu-populate.jsonl server-benchmark.jfr \
             server-benchmark-populate.jfr warm-join-meta.json server.log; do
        [[ -f "$RESULTS/$f" ]] && cp "$RESULTS/$f" "$out/"
    done
}

run_arm() { # <arm-label> <lodStore-value> <rep>
    local arm="$1" value="$2" rep="$3"
    local out="$OUT_ROOT/$STAMP/${arm}-rep${rep}"
    log "=== $SCENARIO $arm rep$rep (lodStore=$value, ${DURATION}s) ==="
    stage_config "$value"
    # Stale-artifact guard: a crashed run must yield MISSING files, not the last run's.
    rm -f "$RESULTS"/server*.json "$RESULTS"/client*.json "$RESULTS"/cpu*.jsonl \
          "$RESULTS"/*.jfr "$RESULTS"/warm-join-meta.json
    local rc=0
    (cd "$PROJECT_ROOT" && ./scripts/benchmark.sh "$SCENARIO" "$DURATION") \
        > "$OUT_ROOT/$STAMP/${arm}-rep${rep}.orchestrator.log" 2>&1 || rc=$?
    collect "$out"
    cat > "$out/meta.json" <<EOF
{"mode":"$MODE","arm":"$arm","lodStore":"$value","rep":$rep,"duration_s":$DURATION,
 "ref":"$(git -C "$PROJECT_ROOT" rev-parse --short HEAD)","rc":$rc,"finished":"$(date -Is)"}
EOF
    if [[ $rc -ne 0 ]]; then
        log "arm $arm rep$rep FAILED (rc=$rc)"
        return 1
    fi
}

mkdir -p "$OUT_ROOT/$STAMP"
if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
    echo "[store-gate] port 25565 in use — refusing to start (soak/benchmark conflict guard)" >&2
    exit 1
fi

for rep in $(seq 1 "$REPS"); do
    run_arm off off "$rep"
    sleep 10
    run_arm on "$ON_MODE" "$rep"
    sleep 10
done

log "Runs complete: $OUT_ROOT/$STAMP — computing gates"
python3 "$PROJECT_ROOT/scripts/store_gate_check.py" "$MODE" "$OUT_ROOT/$STAMP"
