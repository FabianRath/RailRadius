#!/usr/bin/env bash
# Shortcuts for the RailReach backend API (default http://localhost:8080).
# Usage: scripts/api.sh <command> [args]     e.g. scripts/api.sh search wien
set -euo pipefail
BASE="${RAILREACH_URL:-http://localhost:8080}"

case "${1:-help}" in
  health)        curl -s "$BASE/api/health" ;;
  stats)         curl -s "$BASE/api/stats" ;;
  search)        curl -s "$BASE/api/stations/search?q=${2:?query}&limit=${3:-10}" ;;
  station)       curl -s "$BASE/api/stations/${2:?stationId}" ;;
  # reach <stationId> <maxTransfers> [minTransferMin] [maxTransferMin] -> prints the "done" event only
  reach)         curl -sN "$BASE/api/reachability/stream?stationId=${2:?stationId}&maxTransfers=${3:?maxTransfers}&minTransferMinutes=${4:-0}&maxTransferMinutes=${5:-180}" | grep -A1 '^event:done' ;;
  cache-stats)   curl -s "$BASE/api/admin/cache-stats" ;;
  gtfs-stats)    curl -s "$BASE/api/admin/gtfs-stats" ;;
  hub-stats)     curl -s "$BASE/api/admin/hub-stats" ;;
  # Slow / destructive: full GTFS reparse (minutes), hub recompute (5-10 min), cache deletion
  reload-gtfs)   curl -s -X POST "$BASE/api/admin/reload-gtfs" ;;
  compute-hubs)  curl -s -X POST "$BASE/api/admin/compute-hubs" ;;
  clear-gtfs)    curl -s -X DELETE "$BASE/api/admin/gtfs-cache" ;;
  clear-hubs)    curl -s -X DELETE "$BASE/api/admin/hub-cache" ;;
  # Frontend debug overlay (shows all stations, picked up within ~2 s)
  debug-on)      curl -s -X POST "$BASE/api/admin/debug/trigger" ;;
  debug-off)     curl -s -X DELETE "$BASE/api/admin/debug/trigger" ;;
  debug-status)  curl -s "$BASE/api/admin/debug/status" ;;
  *) sed -n '2,3p' "$0"; grep -oE '^  [a-z-]+\)' "$0" | tr -d ') ' | paste -sd' ' ;;
esac
echo
