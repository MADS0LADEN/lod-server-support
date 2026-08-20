#!/usr/bin/env bash
set -euo pipefail

# P1 header-rung live gate (region-summary-sync-plan.md P1; the P1 review's MAJOR-5):
# two chained, individually law-checked soak phases —
#   1. warm-rejoin-summary     its run-1 clearcache re-serve leaves the client cache
#                              holding stamps that CLEAR the serve-latency margin over
#                              every settled region header (a plain serve-then-save
#                              stamp never can — saves postdate serves)
#   2. evicted-tscache-rejoin  the carried world boots a FRESH server with
#                              world/data/lss-timestamps.bin DELETED: the whole-disc
#                              ts>0 re-declare hits an EMPTY timestamp cache and must
#                              resolve through the region-header freshness rung
#                              (disk.header_hits floor + bounded re-download, checked
#                              by the scenario's named check) instead of the GB-class
#                              full re-serve the rung exists to kill
#
# Usage: [SOAK_PLATFORM=fabric|paper] ./scripts/summary_evicted.sh
# Exit nonzero on any phase failure.

PLATFORM="${SOAK_PLATFORM:-fabric}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CARRY_DIR="$PROJECT_ROOT/soak-results/summary-evicted-carry.$$"

case "$PLATFORM" in
    fabric) SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-server" ;;
    paper)  SERVER_RUN_DIR="$PROJECT_ROOT/paper/build/run/soak-server" ;;
    *) echo "[summary-evicted] ERROR: SOAK_PLATFORM must be fabric|paper" >&2; exit 1 ;;
esac

log() { echo "[summary-evicted] $*"; }
cleanup() { rm -rf "$CARRY_DIR"; }
trap cleanup EXIT

log "=== phase 1: warm-rejoin-summary (platform=$PLATFORM) ==="
SOAK_PLATFORM="$PLATFORM" "$PROJECT_ROOT/scripts/soak.sh" warm-rejoin-summary

rm -rf "$CARRY_DIR"
mkdir -p "$CARRY_DIR"
cp -r "$SERVER_RUN_DIR/world" "$CARRY_DIR/world"

log "=== phase 2: evicted-tscache-rejoin (platform=$PLATFORM, carried world) ==="
SOAK_PLATFORM="$PLATFORM" SOAK_WORLD_FROM="$CARRY_DIR" \
    "$PROJECT_ROOT/scripts/soak.sh" evicted-tscache-rejoin

log "both phases green — the header rung carried an evicted-tscache rejoin"
