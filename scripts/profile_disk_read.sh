#!/usr/bin/env bash
set -euo pipefail

# Disk-read serving profile harness: vanilla chunk IO vs C2ME (chunk-IO-overhaul fallback).
#
# Both arms run the SAME code and the SAME pre-generated base world through benchmark.sh's
# no-cache scenario (generation disabled, cold client cache), with the server JFR recording
# (wired into the runBenchmarkServer task) plus the external 1 Hz CPU/wire sampler. The only
# variable is C2ME on the server runtime (-Pbenchmark.c2me=true via BENCHMARK_SERVER_GRADLE_ARGS):
#   vanilla  LSS reads at IOWorker BACKGROUND priority (useBackgroundReadPriority path)
#   c2me     C2ME's chunkio rewrite nulls the vanilla IOWorker -> LSS latches the
#            incompatible fallback: chunkMap.read + AdaptiveReadThrottle
#
# Usage:
#   profile_disk_read.sh run <arm> <rep> <duration> <R>   # arm = vanilla | c2me
#   profile_disk_read.sh matrix <reps> <duration> <R>     # interleaved: vanilla,c2me per rep
#
# Results: profile-results/<stamp>/<arm>-rep<N>/{server.json,client.json,server-benchmark.jfr,
#          client-benchmark.jfr,cpu.jsonl,orchestrator.log,server.log,meta.json}

MAIN_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="${OUT_ROOT:-$MAIN_ROOT/profile-results}"

log() { echo "[profile] $*"; }
die() { echo "[profile] ERROR: $*" >&2; exit 1; }

# PROFILE_BW_PER_PLAYER: per-player bandwidth cap override (default 20 MiB/s) — the
# low-cap backpressure experiment sets e.g. 2097152 to verify CPU scales with the cap.
# PROFILE_SEND_QUEUE: sendQueueLimitPerPlayer override (default 4000) — set BELOW
# WANT_SET_BUDGET (800) to force the router's sendQueueFull admission gate to engage
# (single-player, the client's bounded want-set otherwise backpressures first).
stage_server_config() { # <path>
    cat > "$1" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": $LOD_R,
  "bytesPerSecondLimitPerPlayer": ${PROFILE_BW_PER_PLAYER:-20971520},
  "diskReaderThreads": 5,
  "sendQueueLimitPerPlayer": ${PROFILE_SEND_QUEUE:-1024},
  "bytesPerSecondLimitGlobal": 104857600,
  "enableChunkGeneration": false,
  "generationConcurrencyLimitGlobal": 32,
  "generationTimeoutSeconds": 60,
  "dirtyBroadcastIntervalSeconds": 10,
  "generationConcurrencyLimitPerPlayer": 16,
  "perDimensionTimestampCacheSizeMB": 32,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": true,
  "enableV16Compat": true
}
EOF
}

stage_client_config() { # <path>
    cat > "$1" <<EOF
{
  "receiveServerLods": true,
  "lodDistanceChunks": $LOD_R,
  "enableV16ServerCompat": true,
  "enableV16Generation": true
}
EOF
}

cmd_run() {
    local arm="${1:?arm}" rep="${2:?rep}" duration="${3:?duration}" LOD_R="${4:?lod-distance}"
    local extra_args=""
    case "$arm" in
        vanilla) ;;
        c2me) extra_args="-Pbenchmark.c2me=true" ;;
        *) die "unknown arm '$arm' (want vanilla | c2me)" ;;
    esac

    [[ -d "$MAIN_ROOT/benchmark-worlds/base/world" ]] || die "no base world — build one first"
    if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
        die "port 25565 is in use — refusing to start (soak/benchmark conflict guard)"
    fi

    RUN_OUT="$OUT_ROOT/$RUN_STAMP/${arm}-rep${rep}"
    mkdir -p "$RUN_OUT"
    log "=== RUN $arm rep$rep (duration=${duration}s, R=$LOD_R) ==="

    # Cold client cache every run + staged configs (gen OFF: pure disk-read + serialization).
    local srv_cfg_dir="$MAIN_ROOT/fabric/build/run/benchmark-server/config"
    local cli_cfg_dir="$MAIN_ROOT/fabric/build/run/benchmark-client/config"
    mkdir -p "$srv_cfg_dir" "$cli_cfg_dir"
    rm -rf "$cli_cfg_dir/lss/cache"
    stage_server_config "$srv_cfg_dir/lss-server-config.json"
    stage_client_config "$cli_cfg_dir/lss-client-config.json"

    # Stale-artifact guard: a crashed run must yield MISSING files, not the previous run's.
    rm -f "$MAIN_ROOT/benchmark-results/server.json" "$MAIN_ROOT/benchmark-results/client.json" \
          "$MAIN_ROOT/benchmark-results/"*.jfr \
          "$MAIN_ROOT/fabric/build/run/benchmark-server/benchmark-results/server.json" \
          "$MAIN_ROOT/fabric/build/run/benchmark-client/benchmark-results/client.json" \
          "$MAIN_ROOT/fabric/build/run/benchmark-server/server-benchmark.jfr" \
          "$MAIN_ROOT/fabric/build/run/benchmark-client/client-benchmark.jfr"

    "$MAIN_ROOT/scripts/lib/proc_sampler.sh" "$RUN_OUT/cpu.jsonl" $((duration + 420)) &
    local sampler_pid=$!

    local rc=0
    (cd "$MAIN_ROOT" && BENCHMARK_SERVER_GRADLE_ARGS="$extra_args" \
        ./scripts/benchmark.sh no-cache "$duration") \
        > "$RUN_OUT/orchestrator.log" 2>&1 || rc=$?

    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true

    for f in server.json client.json server.log client.log \
             server-benchmark.jfr client-benchmark.jfr; do
        [[ -f "$MAIN_ROOT/benchmark-results/$f" ]] && cp "$MAIN_ROOT/benchmark-results/$f" "$RUN_OUT/"
    done

    # A/B validity: the c2me arm must have latched the incompatible fallback (warn present),
    # the vanilla arm must not. Recorded, and a mismatch fails the run.
    local warn="absent"
    if grep -q 'Background-priority disk reads unavailable' "$RUN_OUT/server.log" 2>/dev/null; then
        warn="present"
    fi
    local warn_ok=true
    { [[ "$arm" == "c2me" && "$warn" == "absent" ]] || [[ "$arm" == "vanilla" && "$warn" == "present" ]]; } \
        && warn_ok=false

    cat > "$RUN_OUT/meta.json" <<EOF
{
  "arm": "$arm",
  "rep": $rep,
  "ref": "$(git -C "$MAIN_ROOT" rev-parse --short HEAD)",
  "duration_s": $duration,
  "lod_distance": $LOD_R,
  "bw_per_player": ${PROFILE_BW_PER_PLAYER:-20971520},
  "fallback_warn": "$warn",
  "arm_valid": $warn_ok,
  "orchestrator_rc": $rc,
  "finished": "$(date -Is)"
}
EOF
    if [[ $rc -ne 0 ]]; then
        log "run $arm rep$rep FAILED (rc=$rc) — see $RUN_OUT/orchestrator.log"
        return 1
    fi
    if [[ "$warn_ok" != "true" ]]; then
        log "run $arm rep$rep INVALID ARM: fallback warn $warn for arm $arm"
        return 1
    fi
    log "run $arm rep$rep done -> $RUN_OUT"
}

cmd_matrix() {
    local reps="${1:?reps}" duration="${2:?duration}" lod_r="${3:?lod-distance}"
    log "Matrix: ${reps} reps x {vanilla, c2me}, duration=${duration}s, R=$lod_r -> $OUT_ROOT/$RUN_STAMP"
    for rep in $(seq 1 "$reps"); do
        for arm in vanilla c2me; do
            cmd_run "$arm" "$rep" "$duration" "$lod_r"
            sleep 15   # settle: gradle daemon / page cache quiesce between runs
        done
    done
    log "Matrix complete: $OUT_ROOT/$RUN_STAMP"
}

CMD="${1:-}"
shift || true
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"

case "$CMD" in
    run)    cmd_run "$@" ;;
    matrix) cmd_matrix "$@" ;;
    *) sed -n '3,20p' "$0"; exit 1 ;;
esac
