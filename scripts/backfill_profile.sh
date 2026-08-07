#!/usr/bin/env bash
set -euo pipefail

# Store-backfill profile harness (PERF Phase 0 item 3 — the committed form of the round's
# ad-hoc F3 runner). SERVER-ONLY: no client joins; the measured work is the background
# region walk (StoreBackfill) depositing the pre-built base world into a FRESH store.
#
# Each rep: reset world from benchmark-worlds/base (deleting any store DB so the walk has
# regions to process), stage lodStore=full + backfill config, run :fabric:runBenchmarkServer
# for <duration> (the wired server JFR + the external 1 Hz sampler record it), then parse
# the walk's start/terminal log lines into meta.json — walk seconds + deposit counts are
# what defines the Phase 2 gate's deposits/s. There is no committed export of walk timing
# anywhere else: server.json's store block is a cumulative end-of-run dump.
#
# Analyze the walk window (no client -> no wire slope for the default window detection):
#   analyze_profile_jfr.py run <run-dir> --window walk     # reads this meta.json
#
# Usage:
#   backfill_profile.sh run <rep> [duration]     # one rep (duration default 300 s)
#   backfill_profile.sh matrix <reps> [duration]
#
# Knobs:
#   PROFILE_BACKFILL_CPS   lodStoreBackfillColumnsPerSecond (default 1000 = clamp max, the
#                          F3 setting — the walk should be tooling-bound, not pace-bound)
#   PROFILE_NBT_TRANSCODE  useNbtTranscode (default true)
#   PROFILE_LOD_R          lodDistanceChunks (default 256; irrelevant to the walk itself)
#   OUT_ROOT, RUN_STAMP    results root / stamp dir (default profile-results/<stamp>)
#
# Results: profile-results/<stamp>/backfill-rep<N>/{server.json,server-benchmark.jfr,
#          cpu.jsonl,orchestrator.log,server.log,meta.json}

MAIN_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="${OUT_ROOT:-$MAIN_ROOT/profile-results}"
SERVER_RUN_DIR="$MAIN_ROOT/fabric/build/run/benchmark-server"
PROJECT_ROOT="$MAIN_ROOT"   # for mc-run.sh
LOG_PREFIX="backfill-profile"

source "$MAIN_ROOT/scripts/lib/mc-run.sh"
trap mc_cleanup EXIT

log() { echo "[backfill-profile] $*"; }
die() { echo "[backfill-profile] ERROR: $*" >&2; exit 1; }

stage_server_config() { # <path>
    cat > "$1" <<EOF
{
  "enabled": true,
  "lodDistanceChunks": ${PROFILE_LOD_R:-256},
  "diskReaderThreads": 5,
  "enableChunkGeneration": false,
  "missMemoTtlSeconds": 30,
  "useBackgroundReadPriority": true,
  "useNbtTranscode": ${PROFILE_NBT_TRANSCODE:-true},
  "lodStore": "full",
  "lodStoreBackfill": true,
  "lodStoreBackfillColumnsPerSecond": ${PROFILE_BACKFILL_CPS:-1000}
}
EOF
}

# "[HH:MM:SS] ..." log-line prefix -> time-of-day seconds (matches the analyzer's
# JFR startTime domain, so meta's walk window feeds --window walk directly).
tod_of_line() { # <line> -> seconds or ""
    sed -n 's/^\[\([0-9][0-9]\):\([0-9][0-9]\):\([0-9][0-9]\)\].*/\1 \2 \3/p' <<< "$1" \
        | awk '{print $1*3600 + $2*60 + $3}'
}

cmd_run() {
    local rep="${1:?rep}" duration="${2:-300}"

    [[ -d "$MAIN_ROOT/benchmark-worlds/base/world" ]] || die "no base world — build one first (benchmark.sh fresh)"
    if ss -ltnH 'sport = :25565' 2>/dev/null | grep -q .; then
        die "port 25565 is in use — refusing to start (soak/benchmark conflict guard)"
    fi

    RUN_OUT="$OUT_ROOT/$RUN_STAMP/backfill-rep${rep}"
    mkdir -p "$RUN_OUT"
    log "=== RUN backfill rep$rep (duration=${duration}s, cps=${PROFILE_BACKFILL_CPS:-1000}) ==="

    # Fresh world from base, FRESH store (the walk only visits unmarked regions — a
    # leftover store DB means 0 regions to process and a vacuous run).
    rm -rf "$SERVER_RUN_DIR/world"
    cp -r "$MAIN_ROOT/benchmark-worlds/base/world" "$SERVER_RUN_DIR/world"
    rm -rf "$SERVER_RUN_DIR/world/lss-lod"

    mkdir -p "$SERVER_RUN_DIR/config"
    stage_server_config "$SERVER_RUN_DIR/config/lss-server-config.json"

    # pause-when-empty-seconds=-1 is load-bearing: no client EVER joins, and a paused
    # server neither ticks the benchmark duration counter nor advances the walk.
    cat > "$SERVER_RUN_DIR/server.properties" <<'PROPS'
online-mode=false
level-seed=benchmark-seed-42
spawn-protection=0
max-tick-time=-1
pause-when-empty-seconds=-1
difficulty=peaceful
PROPS
    echo "eula=true" > "$SERVER_RUN_DIR/eula.txt"

    # Stale-artifact guard: a crashed run must yield MISSING files, not the last run's.
    rm -f "$SERVER_RUN_DIR/benchmark-results/server.json" \
          "$SERVER_RUN_DIR/server-benchmark.jfr" \
          "$SERVER_RUN_DIR/logs/latest.log"

    log "Building mod..."
    (cd "$MAIN_ROOT" && ./gradlew :fabric:build -x test -x runGameTest -x runClientGameTest --quiet)

    "$MAIN_ROOT/scripts/lib/proc_sampler.sh" "$RUN_OUT/cpu.jsonl" $((duration + 300)) &
    local sampler_pid=$!

    local rc=0
    mc_start_server "$RUN_OUT/orchestrator.log" :fabric:runBenchmarkServer \
        -Pbenchmark.duration="$duration"
    if ! mc_wait_server_ready "$SERVER_RUN_DIR/logs/latest.log" "$RUN_OUT/orchestrator.log" 180; then
        rc=1
    else
        # Wait for the auto-shutdown tick counter, with a hard deadline (the benchmark
        # server only halts on tick count; a stalled JVM must not hang the matrix).
        local total_timeout=$((duration + 180)) elapsed=0
        while kill -0 "$SERVER_PID" 2>/dev/null; do
            if [[ $elapsed -ge $total_timeout ]]; then
                log "deadline exceeded (${total_timeout}s) — killing stalled server"
                kill "$SERVER_PID" 2>/dev/null || true
                rc=1
                break
            fi
            sleep 1
            elapsed=$((elapsed + 1))
        done
        wait "$SERVER_PID" 2>/dev/null || true
        SERVER_PID=""
    fi

    kill "$sampler_pid" 2>/dev/null || true
    wait "$sampler_pid" 2>/dev/null || true

    [[ -f "$SERVER_RUN_DIR/benchmark-results/server.json" ]] \
        && cp "$SERVER_RUN_DIR/benchmark-results/server.json" "$RUN_OUT/"
    [[ -f "$SERVER_RUN_DIR/server-benchmark.jfr" ]] \
        && cp "$SERVER_RUN_DIR/server-benchmark.jfr" "$RUN_OUT/"
    [[ -f "$SERVER_RUN_DIR/logs/latest.log" ]] \
        && cp "$SERVER_RUN_DIR/logs/latest.log" "$RUN_OUT/server.log"

    # Walk timing + deposit counts (Phase 0 item 3): parsed from StoreBackfill's start
    # line ("Store backfill: N region(s) to process, estimated ~...") and terminal line
    # ("Store backfill complete|stopped: R regions, D deposited, S skipped, E errors,
    # P pauses"). Times are TIME-OF-DAY seconds — the domain --window walk consumes.
    local start_line end_line
    start_line="$(grep -m1 'Store backfill: .* region(s) to process' "$RUN_OUT/server.log" 2>/dev/null || true)"
    end_line="$(grep -m1 -E 'Store backfill (complete|stopped):' "$RUN_OUT/server.log" 2>/dev/null || true)"
    local walk_complete=false walk_start="null" walk_end="null" walk_seconds="null"
    local regions=0 deposited=0 skipped=0 errors=0 pauses=0 deposits_per_s="null"
    if [[ -n "$start_line" ]]; then
        walk_start="$(tod_of_line "$start_line")"
        [[ -n "$walk_start" ]] || walk_start="null"
    fi
    if [[ -n "$end_line" ]]; then
        walk_end="$(tod_of_line "$end_line")"
        [[ -n "$walk_end" ]] || walk_end="null"
        read -r regions deposited skipped errors pauses < <(sed -n \
            's/.*: \([0-9]*\) regions, \([0-9]*\) deposited, \([0-9]*\) skipped, \([0-9]*\) errors, \([0-9]*\) pauses.*/\1 \2 \3 \4 \5/p' \
            <<< "$end_line") || true
        [[ "$end_line" == *"Store backfill complete:"* ]] && walk_complete=true
        if [[ "$walk_start" != "null" && "$walk_end" != "null" && "$walk_end" -gt "$walk_start" ]]; then
            walk_seconds=$((walk_end - walk_start))
            deposits_per_s="$(awk -v d="$deposited" -v s="$walk_seconds" 'BEGIN{printf "%.1f", d/s}')"
        fi
    fi

    # Effective-config assertion (Phase 0 item 1) — same contract as the serve harness.
    local echo_line
    echo_line="$(grep -o 'Effective config: .*' "$RUN_OUT/server.log" 2>/dev/null | tail -1 || true)"
    local arm_valid=true
    [[ "$echo_line" == *"useNbtTranscode=${PROFILE_NBT_TRANSCODE:-true}"* ]] || arm_valid=false
    [[ "$echo_line" == *"diskReaderThreads=5"* ]] || arm_valid=false
    # A walk that never STARTED is a vacuous run (leftover store marks, staging bug).
    [[ -n "$start_line" ]] || arm_valid=false

    cat > "$RUN_OUT/meta.json" <<EOF
{
  "arm": "backfill",
  "rep": $rep,
  "ref": "$(git -C "$MAIN_ROOT" rev-parse --short HEAD)",
  "duration_s": $duration,
  "backfill_cps": ${PROFILE_BACKFILL_CPS:-1000},
  "nbt_transcode": ${PROFILE_NBT_TRANSCODE:-true},
  "config_echo": "$echo_line",
  "walk": {
    "complete": $walk_complete,
    "start_tod_s": $walk_start,
    "end_tod_s": $walk_end,
    "walk_seconds": $walk_seconds,
    "regions": ${regions:-0},
    "deposited": ${deposited:-0},
    "skipped": ${skipped:-0},
    "errors": ${errors:-0},
    "pauses": ${pauses:-0},
    "deposits_per_s": $deposits_per_s
  },
  "arm_valid": $arm_valid,
  "orchestrator_rc": $rc,
  "finished": "$(date -Is)"
}
EOF
    if [[ $rc -ne 0 ]]; then
        log "run backfill rep$rep FAILED (rc=$rc) — see $RUN_OUT/orchestrator.log"
        return 1
    fi
    if [[ "$arm_valid" != "true" ]]; then
        log "run backfill rep$rep INVALID: echo='${echo_line:-<missing>}' walk_started=$([[ -n "$start_line" ]] && echo yes || echo no)"
        return 1
    fi
    if [[ "$walk_complete" != "true" ]]; then
        log "NOTE: walk did not complete within ${duration}s — deposits/s unavailable; raise the duration"
    fi
    log "run backfill rep$rep done -> $RUN_OUT (deposited=$deposited in ${walk_seconds}s, ${deposits_per_s}/s)"
}

cmd_matrix() {
    local reps="${1:?reps}" duration="${2:-300}"
    log "Matrix: ${reps} backfill reps, duration=${duration}s -> $OUT_ROOT/$RUN_STAMP"
    for rep in $(seq 1 "$reps"); do
        cmd_run "$rep" "$duration"
        sleep 15
    done
    log "Matrix complete: $OUT_ROOT/$RUN_STAMP"
}

CMD="${1:-}"
shift || true
RUN_STAMP="${RUN_STAMP:-$(date +%Y%m%d-%H%M%S)}"

case "$CMD" in
    run)    cmd_run "$@" ;;
    matrix) cmd_matrix "$@" ;;
    *) sed -n '3,30p' "$0"; exit 1 ;;
esac
