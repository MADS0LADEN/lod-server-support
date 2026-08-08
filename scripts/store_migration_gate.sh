#!/usr/bin/env bash
set -euo pipefail

# LOD-store lazy-migration gate (XVER §9's store-migration soak variant, C6):
# two chained, individually law-checked phases —
#   1. store-offline-populate  lodStore=full backfill charges a schema-4/v20 store
#   2. store-migration-join    the carried store is DOWNGRADED to the released v0.9.x
#                              shape at SERVER_STARTING (SoakStoreDowngrade — schema 3,
#                              FNV hashes, native bodies, no wirefmt column), the boot
#                              runs the REAL lazy 3→4 upgrade, and a cold-cache client
#                              is served warm from 19-rows through the inverse
#                              translator WHILE the background walk migrates
# plus the wrapper-only assertions no snapshot carries:
#   - the downgrade line   ("Store downgraded to the released v0.9.x shape")
#   - the walk completion  ("background migration complete")
#   - zero migrate anomalies (the completion line must not carry "DELETED")
#
# This closes C4's accepted gap: no ordinary soak can produce a live 19-row.
# Fabric-only: the migration is platform-agnostic common code, and the downgrade tool
# rides the Fabric soak driver.
#
# Usage: ./scripts/store_migration_gate.sh
# Exit nonzero on any phase failure or a missing wrapper assertion.

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RESULTS_ROOT="$PROJECT_ROOT/soak-results"
SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-server"
CARRY_DIR="$PROJECT_ROOT/soak-results/store-migration-carry.$$"

log() { echo "[store-migration] $*"; }
cleanup() { rm -rf "$CARRY_DIR"; }
trap cleanup EXIT

latest_results() { # <scenario>  (freshness-guarded, the store_offline_edit pattern)
    local d
    d="$(ls -1d "$RESULTS_ROOT/$1-"2* 2>/dev/null | sort | tail -1)"
    if [[ -z "$d" || "$(stat -c %Y "$d")" -lt "$WRAPPER_START_EPOCH" ]]; then
        echo "[store-migration] ERROR: no fresh results dir for $1 (latest: ${d:-none})" >&2
        exit 1
    fi
    echo "$d"
}
WRAPPER_START_EPOCH="$(date +%s)"

log "=== phase 1: store-offline-populate (charges a v20 store) ==="
"$PROJECT_ROOT/scripts/soak.sh" store-offline-populate

rm -rf "$CARRY_DIR"
mkdir -p "$CARRY_DIR"
cp -r "$SERVER_RUN_DIR/world" "$CARRY_DIR/world"
if [[ ! -f "$CARRY_DIR/world/lss-lod/store.db" ]]; then
    log "ERROR: phase 1 left no store at world/lss-lod/store.db — nothing to downgrade"
    exit 1
fi

log "=== phase 2: store-migration-join (downgrade → lazy upgrade → warm 19-row serves) ==="
SOAK_WORLD_FROM="$CARRY_DIR" "$PROJECT_ROOT/scripts/soak.sh" store-migration-join
JOIN_DIR="$(latest_results store-migration-join)"

log "wrapper assertions over $JOIN_DIR/server.log"
SERVER_LOG="$JOIN_DIR/server.log"
if [[ ! -f "$SERVER_LOG" ]]; then
    log "ERROR: no server.log collected"
    exit 1
fi
fail=0
if ! grep -q "Store downgraded to the released v0.9.x shape" "$SERVER_LOG"; then
    log "FAIL: the downgrade never ran — phase 2 measured an ordinary v20 store"
    fail=1
fi
if ! grep -q "background migration complete" "$SERVER_LOG"; then
    log "FAIL: the background walk did not complete within the scenario"
    fail=1
fi
if grep "background migration complete" "$SERVER_LOG" | grep -q "DELETED"; then
    log "FAIL: the walk deleted anomaly rows — on a freshly-downgraded store every row"
    log "      must translate cleanly (a deletion here is a translator/hash defect)"
    fail=1
fi
if [[ "$fail" -ne 0 ]]; then
    exit 1
fi
log "PASS: downgrade ran, walk completed with zero anomalies, laws + named check green"
