#!/usr/bin/env bash
# Shortcuts for the RailRadius backend API (default http://localhost:8080).
# Usage: scripts/api.sh <command> [args]     e.g. scripts/api.sh search wien
set -euo pipefail
BASE="${RAILRADIUS_URL:-http://localhost:8080}"
# Admin calls send RAILRADIUS_ADMIN_TOKEN; "dev" matches the dev profile's default. reach sends it too, so it is never
# rate limited
admin() { curl -s -H "X-Admin-Token: ${RAILRADIUS_ADMIN_TOKEN:-dev}" "$@"; }

case "${1:-help}" in
  health)        curl -s "$BASE/api/health" ;;
  stats)         curl -s "$BASE/api/stats" ;;
  search)        curl -s "$BASE/api/stations/search?q=${2:?query}&limit=${3:-10}" ;;
  station)       curl -s "$BASE/api/stations/${2:?stationId}" ;;
  # reach <stationId> <maxTransfers> [minTransferMin] [maxTransferMin] -> prints the "done" event only
  reach)         admin -N "$BASE/api/reachability/stream?stationId=${2:?stationId}&maxTransfers=${3:?maxTransfers}&minTransferMinutes=${4:-0}&maxTransferMinutes=${5:-180}" | grep -A1 '^event:done' ;;
  cache-stats)   admin "$BASE/api/admin/cache-stats" ;;
  gtfs-stats)    admin "$BASE/api/admin/gtfs-stats" ;;
  # Feed update center: validity of every zip; downloads replace zips (old one kept in previous/), then reload-gtfs
  feeds)         admin "$BASE/api/admin/feeds" ;;
  feeds-storage) admin "$BASE/api/admin/feeds/storage" ;;
  feed-download) admin -X POST "$BASE/api/admin/feeds/${2:?source}/download" ;;
  feeds-download-outdated) admin -X POST "$BASE/api/admin/feeds/download-outdated" ;;
  # Slow / destructive: full GTFS reparse (minutes), cache deletion
  reload-gtfs)   admin -X POST "$BASE/api/admin/reload-gtfs" ;;
  clear-gtfs)    admin -X DELETE "$BASE/api/admin/gtfs-cache" ;;
  # Frontend debug overlay (shows all stations, picked up within ~2 s by a browser logged in as dev)
  debug-on)      admin -X POST "$BASE/api/admin/debug/trigger" ;;
  debug-off)     admin -X DELETE "$BASE/api/admin/debug/trigger" ;;
  debug-status)  admin "$BASE/api/admin/debug/status" ;;
  *) sed -n '2,3p' "$0"; grep -oE '^  [a-z-]+\)' "$0" | tr -d ') ' | paste -sd' ' ;;
esac
echo
