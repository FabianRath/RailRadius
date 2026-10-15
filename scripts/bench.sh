#!/usr/bin/env bash
# Memory and speed baselines against a running backend (docs/HOSTING.md -> Baselines). Run from the repo root.
# Start the app with the warmer off, or its background searches skew every number:
#   mvn -q -f backend/pom.xml spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx12g -XX:+UseCompactObjectHeaders -Xlog:gc*:file=<log>" \
#       -Dspring-boot.run.arguments=--railreach.cache.predictive-enabled=false
# <log> must be relative (to backend/): -Xlog splits at the colon of "C:". jcmd/jfr come from $JAVA_HOME/bin, else PATH;
# alloc and held write their files to $BENCH_OUT (default: the current directory). Searches send RAILREACH_ADMIN_TOKEN
# (default "dev"): without the dev profile, start the app with the same env var, or the rate limit turns them away.
# Usage: scripts/bench.sh <command> [args]
set -euo pipefail
BASE="${RAILREACH_URL:-http://localhost:8080}"
JDK_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
OUT="${BENCH_OUT:-.}"

# PID of the running backend (not Maven's own JVM)
app_pid() { "${JDK_BIN}jcmd" -l | grep -E 'com\.railreach\.RailReachBackendApplication|railreach-backend-[^ ]*\.jar' | awk '{print $1}' | head -n 1; }

# Live heap in MB after a full GC
live_heap_mb() {
  local pid; pid=$(app_pid)
  "${JDK_BIN}jcmd" "$pid" GC.run > /dev/null
  "${JDK_BIN}jcmd" "$pid" GC.heap_info | grep -oE 'used [0-9]+K' | head -n 1 | grep -oE '[0-9]+' | awk '{print int($1/1024)}'
}

# Bytes of all live objects (forces a full GC)
histogram_bytes() { "${JDK_BIN}jcmd" "$(app_pid)" GC.class_histogram | awk '/^Total/ {print $3}'; }

# Streams a search and prints "<stations> <calculationTimeMs>" from its done event. It sends the admin token, so the
# search rate limit never turns a run away
stream() {
  curl -sN -H "X-Admin-Token: ${RAILREACH_ADMIN_TOKEN:-dev}" "$BASE/api/reachability/stream?stationId=$1&maxTransfers=$2" \
    | grep -A1 '^event:done' | sed -nE 's/.*"(calculationTimeMs|total)":([0-9]+).*"(calculationTimeMs|total)":([0-9]+).*/\1 \2 \3 \4/p' \
    | awk '{v[$1]=$2; v[$3]=$4; print v["total"], v["calculationTimeMs"]}'
}

# Starts of one set in docs/bench-stations.txt
starts() { awk -v set="$1" '$1 == set {print $2}' docs/bench-stations.txt; }

case "${1:-help}" in
  # Live heap after a full GC, and the process's peak working set so far
  memory)
    pid=$(app_pid)
    echo "live heap after GC: $(live_heap_mb) MB"
    echo "peak working set: $(powershell -NoProfile -Command "[int]((Get-Process -Id $pid).PeakWorkingSet64/1MB)") MB"
    ;;
  # times <set>: per start and transfer count 0..5 a fresh search, then the same again from the result cache.
  # The search's time includes sending its stations; the cached replay is the output alone.
  times)
    echo "start transfers stations searchMs replayMs"
    for start in $(starts "${2:?set}"); do
      for transfers in 0 1 2 3 4 5; do
        read -r stations searchMs <<< "$(stream "$start" "$transfers")"
        read -r _ replayMs <<< "$(stream "$start" "$transfers")"
        echo "$start $transfers $stations $searchMs $replayMs"
      done
    done
    ;;
  # alloc <stationId> <transfers>: bytes allocated by all threads during one search (JFR, settings=profile)
  alloc)
    pid=$(app_pid); jfr_file="$OUT/alloc.jfr"
    "${JDK_BIN}jcmd" "$pid" JFR.start name=bench settings=profile > /dev/null
    stream "${2:?stationId}" "${3:?transfers}"
    "${JDK_BIN}jcmd" "$pid" JFR.stop name=bench filename="$(cygpath -w "$jfr_file" 2>/dev/null || echo "$jfr_file")" > /dev/null
    # ThreadAllocationStatistics holds each thread's running total at the start and end of the recording
    # (a thread started during the recording has only the end total). JSON, because the text output is localised.
    "${JDK_BIN}jfr" print --json --events jdk.ThreadAllocationStatistics "$jfr_file" \
      | awk -F'[:,]' '/"startTime"/ {ts=$0; if (t0 == "") t0=ts} /"allocated"/ {bytes=$2+0}
             /"javaThreadId"/ {id=$2+0; if (!(id in first)) first[id]=(ts == t0 ? bytes : 0); last[id]=bytes}
             END {for (id in last) sum += last[id] - first[id];
                  printf "allocated during the recording: %d MB\n", sum/1048576}'
    ;;
  # held <stationId> <transfers>: live bytes while the search runs, minus before it (full GC every few seconds)
  held)
    before=$(histogram_bytes); peak=$before
    stream "${2:?stationId}" "${3:?transfers}" > "$OUT/held-stream.txt" &
    while kill -0 $! 2> /dev/null; do
      now=$(histogram_bytes); (( now > peak )) && peak=$now
      sleep 2
    done
    echo "held during the search: peak $(( (peak - before) / 1048576 )) MB over $(( before / 1048576 )) MB before"
    ;;
  # entry <transfers> <stationId>...: live heap growth per stored result (the results stay in the raw cache)
  entry)
    transfers="${2:?transfers}"; shift 2
    before=$(live_heap_mb)
    for start in "$@"; do stream "$start" "$transfers" > /dev/null; done
    after=$(live_heap_mb)
    echo "per stored result: $(( (after - before) / $# )) MB ($before -> $after MB for $# results)"
    ;;
  *) sed -n '2,8p' "$0"; grep -oE '^  [a-z-]+\)' "$0" | tr -d ') ' | paste -sd' ' ;;
esac
