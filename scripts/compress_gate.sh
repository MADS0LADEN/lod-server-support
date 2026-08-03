#!/usr/bin/env bash
set -euo pipefail

# Compressed-columns §5.2 CPU-proof runner (docs/planning/compressed-columns-
# implementation-plan.md): interleaved kill-switch A/B arms flipping ONLY
# useCompressedColumns, one rep = one off-arm + one on-arm back-to-back (the same-box
# same-session discipline store_gate.sh established — never compare across days/box
# states). The store is HELD CONSTANT per mode (warm: lodStore=full both arms — the
# store-hit serve path is the headline claim; cold: lodStore=off both arms — the
# disk/live compress-replaces-deflate path).
#
# Usage:
#   compress_gate.sh warm <reps> <duration>   # warm-join A/B (G1/G1t headline gates)
#   compress_gate.sh cold <reps> <duration>   # no-cache A/B (parity-or-better gate)
#
# Results: compress-gate-results/<stamp>/<arm>-rep<N>/… then compress_gate_check.py
# runs the G1/G1t/G2/G3/G4 math over the whole stamp dir.

MODE="${1:?warm|cold}"
REPS="${2:?reps}"
DURATION="${3:?duration-seconds}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="${OUT_ROOT:-$PROJECT_ROOT/compress-gate-results}"
STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"
SRV_CFG_DIR="$PROJECT_ROOT/fabric/build/run/benchmark-server/config"
RESULTS="$PROJECT_ROOT/benchmark-results"

case "$MODE" in
    warm) SCENARIO="warm-join"; STORE_MODE="full" ;;
    cold) SCENARIO="no-cache";  STORE_MODE="off" ;;
    *) echo "[compress-gate] ERROR: mode must be warm|cold" >&2; exit 1 ;;
esac

log() { echo "[compress-gate] $*"; }

# Premise pin (plan §5.2 / review B4): the OFF arm's whole claim is "netty deflate over
# raw bytes costs CPU" — that only holds while connection compression is ON. Today it
# holds by vanilla default; stage it EXPLICITLY so a silently-added properties line or
# a vanilla default change fails loudly in the check's ratio assert, not silently here.
export BENCHMARK_EXTRA_SERVER_PROPS="network-compression-threshold=256"

stage_config() { # <useCompressedColumns-value>
    mkdir -p "$SRV_CFG_DIR"
    # warm mode reuses store_gate's calibrated converging disc (distance 96 — see
    # store_gate.sh's stage_config comment for the full diagnosis); cold keeps the huge
    # disc for sustained throughput. Pass duration >= 120 for warm so cycle A converges.
    local distance=256
    [[ "$MODE" == "warm" ]] && distance=96
    cat > "$SRV_CFG_DIR/lss-server-config.json" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": $distance,
  "diskReaderThreads": 5,
  "enableChunkGeneration": false,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": true,
  "useNbtTranscode": true,
  "lodStore": "$STORE_MODE",
  "useCompressedColumns": $1
}
EOF
}

collect() { # <run-out-dir>
    local out="$1"
    mkdir -p "$out"
    local f
    for f in server.json client.json server-populate.json client-populate.json \
             cpu.jsonl cpu-populate.jsonl server-benchmark.jfr client-benchmark.jfr \
             server-benchmark-populate.jfr warm-join-meta.json server.log; do
        [[ -f "$RESULTS/$f" ]] && cp "$RESULTS/$f" "$out/"
    done
}

run_arm() { # <arm-label> <useCompressedColumns-value> <rep>
    local arm="$1" value="$2" rep="$3"
    local out="$OUT_ROOT/$STAMP/${arm}-rep${rep}"
    log "=== $SCENARIO $arm rep$rep (useCompressedColumns=$value, lodStore=$STORE_MODE, ${DURATION}s) ==="
    stage_config "$value"
    # Stale-artifact guard: a crashed run must yield MISSING files, not the last run's.
    rm -f "$RESULTS"/server*.json "$RESULTS"/client*.json "$RESULTS"/cpu*.jsonl \
          "$RESULTS"/*.jfr "$RESULTS"/warm-join-meta.json
    local rc=0
    (cd "$PROJECT_ROOT" && ./scripts/benchmark.sh "$SCENARIO" "$DURATION") \
        > "$OUT_ROOT/$STAMP/${arm}-rep${rep}.orchestrator.log" 2>&1 || rc=$?
    collect "$out"
    cat > "$out/meta.json" <<EOF
{"mode":"$MODE","arm":"$arm","useCompressedColumns":$value,"lodStore":"$STORE_MODE",
 "rep":$rep,"duration_s":$DURATION,
 "ref":"$(git -C "$PROJECT_ROOT" rev-parse --short HEAD)","rc":$rc,"finished":"$(date -Is)"}
EOF
    if [[ $rc -ne 0 ]]; then
        log "arm $arm rep$rep FAILED (rc=$rc)"
        return 1
    fi
}

mkdir -p "$OUT_ROOT/$STAMP"
if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
    echo "[compress-gate] port 25565 in use — refusing to start (soak/benchmark conflict guard)" >&2
    exit 1
fi

for rep in $(seq 1 "$REPS"); do
    run_arm off false "$rep"
    sleep 10
    run_arm on true "$rep"
    sleep 10
done

log "Runs complete: $OUT_ROOT/$STAMP — computing gates"
python3 "$PROJECT_ROOT/scripts/compress_gate_check.py" "$MODE" "$OUT_ROOT/$STAMP"
