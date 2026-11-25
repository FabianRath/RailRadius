# The full-data check of perf (#34, docs/HOSTING.md -> Full-data check of perf), sourced by `scripts/bench.sh
# perf-check <dev|full|mini> [set] [perf-ref]`, whose functions it uses. Run from the repo root.
# 1. Records main's results where perf last took them in (the merge base) in same-result mode over the set's starts.
# 2. Builds and measures each first-parent commit of perf since then, and the commit before the first: server search
#    times, idle heap, browser file size, browser engine times. Prints each one's change from the commit before.
# 3. Records perf's results the same way and compares them with step 1's.
# Each commit is built in a worktree, parses the zips into its own store (a commit may change the store's format), then
# is measured on a fresh start from that store, as a server starts. Everything goes to backend/data/perf-check/.
DATA_SET="${1:?data set: dev, full or mini}"
SET="${2:-$DATA_SET}"
PERF_REF="${3:-origin/perf}"
MAIN_REF=origin/main
PORT=8081
BASE="http://localhost:$PORT"
BENCH_APP="--server.port=$PORT"
# Measured as on the server (docs/HOSTING.md -> Size); parsing all zips needs more (HOSTING.md -> Measurements)
MEASURE_HEAP=3g
TODAY=$(date +%F)
# A change misses its target below this gain and costs too much above this loss, in % (decisions.md, 2026-11-25)
TARGET_GAIN_PCT=5
MAX_COST_PCT=2
# Absolute: the app and the recording run from the worktree's backend/
CHECK_DIR="$PWD/backend/data/perf-check"
WORKTREE="$CHECK_DIR/worktree"
RUN_DIR="$CHECK_DIR/$DATA_SET-$(date +%Y%m%d-%H%M%S)"
SNAPSHOT_CLASS=com.railradius.service.SearchSnapshot
SUBJECT_CHARS=50

case "$DATA_SET" in
  full) ZIPS="$PWD/backend/data/gtfs"; PROFILE=default; PARSE_HEAP=8g ;;
  dev) ZIPS="$PWD/backend/data/gtfs-dev"; PROFILE=dev; PARSE_HEAP=2g ;;
  mini) ZIPS="$PWD/backend/data/gtfs-mini"; PROFILE=mini; PARSE_HEAP=2g ;;
  *) echo "Data set must be dev, full or mini: $DATA_SET" >&2; exit 1 ;;
esac

die() { echo "perf-check: $*" >&2; exit 1; }
# A path for Java and Node: Git Bash's /c/... means nothing to them
native_path() { cygpath -m "$1" 2> /dev/null || echo "$1"; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

ls "$ZIPS"/*.zip > /dev/null 2>&1 || die "no zips in $ZIPS"
[ -n "$(starts "$SET")" ] || die "no starts in set $SET of docs/bench-stations.txt"
curl -s "$BASE/api/health" > /dev/null && die "port $PORT is in use"
base=$(git merge-base "$MAIN_REF" "$PERF_REF") || die "no common commit of $MAIN_REF and $PERF_REF (git fetch first?)"
git show "$base:backend/src/main/java/com/railradius/service/SearchSnapshot.java" | grep -q same-result \
  || die "$MAIN_REF as merged into $PERF_REF has no same-result snapshot (#32): merge main into perf first"
mapfile -t walk < <(git rev-list --first-parent --reverse "$base..$PERF_REF")
[ ${#walk[@]} -gt 0 ] || die "$PERF_REF has no commits that $MAIN_REF lacks"
before_first=$(git rev-parse "${walk[0]}^1")
tip=${walk[-1]}

mkdir -p "$RUN_DIR"
[ -d "$WORKTREE" ] || git worktree add --quiet --detach "$WORKTREE" "$base"
built=""
app=""

# build <sha>: the jar and the browser engine of a commit, in the worktree
build() {
  [ "$built" = "$1" ] && return
  log "building $(git rev-parse --short "$1")"
  git -C "$WORKTREE" checkout --quiet --force --detach "$1"
  git -C "$WORKTREE" clean -qfdx
  mvn -q -f "$(native_path "$WORKTREE/backend/pom.xml")" package -DskipTests > "$2/build.log" 2>&1 \
    && mvn -q -f "$(native_path "$WORKTREE/browser-engine/pom.xml")" verify -DskipTests >> "$2/build.log" 2>&1 \
    || die "build failed, see $2/build.log"
  built="$1"
}

jar() { ls "$WORKTREE"/backend/target/railradius-backend-*.jar; }

# start_app <dir> <heap> <browser data dir>: the built app on the zips, with the store in <dir>; waits until it serves
start_app() {
  # From backend/, as the app expects (its frontend and default data paths are relative to it)
  (cd "$WORKTREE/backend" && RAILRADIUS_ADMIN_TOKEN="${RAILRADIUS_ADMIN_TOKEN:-dev}" exec "${JDK_BIN}java" \
    "-Xmx$2" -XX:+UseCompactObjectHeaders -jar "$(native_path "$(jar)")" "--server.port=$PORT" \
    "--spring.profiles.active=$PROFILE" "--railradius.gtfs.directory=$(native_path "$ZIPS")" \
    --railradius.gtfs.auto-load-from-files=true "--railradius.gtfs.store-file=$(native_path "$1/store.bin")" \
    "--railradius.browser-data.directory=$(native_path "$3")" --railradius.cache.predictive-enabled=false) \
    >> "$1/app.log" 2>&1 &
  app=$!
  until curl -s "$BASE/api/health" | grep -q '"UP"'; do
    kill -0 "$app" 2> /dev/null || die "the app stopped, see $1/app.log"
    sleep 2
  done
}

stop_app() {
  [ -n "$app" ] || return 0
  local pid; pid=$(app_pid)
  if command -v taskkill > /dev/null; then taskkill //PID "$pid" //F > /dev/null; else kill "$pid"; fi
  wait "$app" 2> /dev/null || true
  app=""
}
trap stop_app EXIT

# prepare <sha> <dir>: built, with its own store parsed from the zips
prepare() {
  mkdir -p "$2"
  build "$1" "$2"
  [ -f "$2/store.bin" ] && return
  log "parsing the zips"
  start_app "$2" "$PARSE_HEAP" "$2/parse-browser-data"
  stop_app
  rm -rf "$2/parse-browser-data"
}

# snapshot <sha> <dir> <out>: the same-result dump of the set's starts on the commit's store
snapshot() {
  prepare "$1" "$2"
  log "recording results of $(git rev-parse --short "$1") to $3"
  (cd "$WORKTREE/backend" && "${JDK_BIN}java" -Xmx$MEASURE_HEAP -XX:+UseCompactObjectHeaders \
    -cp "$(native_path "$(jar)")" "-Dloader.main=$SNAPSHOT_CLASS" org.springframework.boot.loader.launch.PropertiesLauncher \
    "$(native_path "$2/store.bin")" "$SET" "$TODAY" "$(native_path "$3")" same-result) >> "$2/snapshot.log" 2>&1 \
    || die "recording failed, see $2/snapshot.log"
}

# Nearest-rank percentile <p> of the numbers on stdin
percentile() { sort -n | awk -v p="$1" '{v[NR] = $1} END {i = int(p / 100 * NR); if (i < p / 100 * NR) i++; print v[i < 1 ? 1 : i]}'; }

# measure <sha> <dir> <kind>: one line of metrics.tsv
measure() {
  prepare "$1" "$2"
  log "measuring"
  start_app "$2" "$MEASURE_HEAP" "$2/browser-data"
  until ls "$2"/browser-data/browser-data-*.bin.gz > /dev/null 2>&1; do sleep 1; done
  local heap_mb; heap_mb=$(live_heap_mb)
  search_times "$SET" > "$2/server-times.txt"
  stop_app
  local file; file=$(ls "$2"/browser-data/browser-data-*.bin.gz)
  browser_times "$(native_path "$WORKTREE/frontend/engine")" "$(native_path "$file")" "$TODAY" "$SET" \
    > "$2/browser-times.txt"
  # Searches at 5 transfers, the ones S24's targets are about
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(git rev-parse --short "$1")" "$3" "$heap_mb" \
    "$(( $(wc -c < "$file") / 1024 ))" \
    "$(awk 'NR > 1 && $2 == 5 {print $4}' "$2/server-times.txt" | percentile 50)" \
    "$(awk 'NR > 1 && $2 == 5 {print $4}' "$2/server-times.txt" | percentile 95)" \
    "$(awk '/^transfers p50/ {table = 1; next} table && $1 == 5 {print $2}' "$2/browser-times.txt")" \
    "$(awk '/^transfers p50/ {table = 1; next} table && $1 == 5 {print $3}' "$2/browser-times.txt")" \
    "$(git log -1 --format=%s "$1" | cut -c "1-$SUBJECT_CHARS")" >> "$RUN_DIR/metrics.tsv"
  [ "$1" = "$tip" ] || rm -f "$2/store.bin"
}

# A merge of main into perf, a merged pull request, or a plain commit
kind() {
  local parents; parents=$(git rev-list --parents -n 1 "$1" | wc -w)
  if [ "$parents" -lt 3 ]; then echo commit
  elif git merge-base --is-ancestor "$1^2" "$MAIN_REF"; then echo main
  else echo pr; fi
}

dir_of() { echo "$RUN_DIR/$(git rev-parse --short "$1")"; }

log "$PERF_REF: ${#walk[@]} commits since $MAIN_REF $(git rev-parse --short "$base"); data $DATA_SET, set $SET, today $TODAY"
log "output in $RUN_DIR"
snapshot "$base" "$(dir_of "$base")" "$RUN_DIR/main-same-result.txt.gz"
[ "$base" = "$before_first" ] || rm -f "$(dir_of "$base")/store.bin"

measure "$before_first" "$(dir_of "$before_first")" before
for sha in "${walk[@]}"; do measure "$sha" "$(dir_of "$sha")" "$(kind "$sha")"; done

# Each commit against the one before: lower is better for every column; a column without a before value shows no change
awk -F'\t' -v gain="$TARGET_GAIN_PCT" -v cost="$MAX_COST_PCT" '
  BEGIN {
    split("heapMB fileKB serverP50 serverP95 browserP50 browserP95", names, " ")
    printf "%-9s %-6s", "commit", "kind"; for (c = 1; c <= 6; c++) printf " %17s", names[c]; printf "  %-20s %s\n", "verdict", "subject"
  }
  {
    printf "%-9s %-6s", $1, $2; gained = 0; costly = 0
    for (c = 1; c <= 6; c++) {
      value = $(c + 2); change = ""
      if (NR > 1 && prev[c] > 0) {
        pct = (value - prev[c]) * 100 / prev[c]; change = sprintf(" (%+.1f%%)", pct)
        if (pct <= -gain) gained = 1; if (pct > cost) costly = 1
      }
      printf " %17s", value change; prev[c] = value
    }
    verdict = NR == 1 ? "-" : (gained ? "" : "no " gain " % gain") (gained || !costly ? "" : ", ") (costly ? "costs > " cost " %" : "")
    printf "  %-20s %s\n", verdict == "" ? "ok" : verdict, $9
  }' "$RUN_DIR/metrics.tsv" | tee "$RUN_DIR/table.txt"

snapshot "$tip" "$(dir_of "$tip")" "$RUN_DIR/perf-same-result.txt.gz"
rm -f "$(dir_of "$tip")/store.bin"
if cmp -s <(gzip -dc "$RUN_DIR/main-same-result.txt.gz") <(gzip -dc "$RUN_DIR/perf-same-result.txt.gz"); then
  log "same result: $PERF_REF finds what $MAIN_REF finds" | tee "$RUN_DIR/same-result.txt"
else
  diff <(gzip -dc "$RUN_DIR/main-same-result.txt.gz") <(gzip -dc "$RUN_DIR/perf-same-result.txt.gz") \
    > "$RUN_DIR/same-result-diff.txt" || true
  log "DIFFERENT RESULT: $(grep -c '^[<>]' "$RUN_DIR/same-result-diff.txt") lines differ (< main, > perf):" \
    | tee "$RUN_DIR/same-result.txt"
  head -n 20 "$RUN_DIR/same-result-diff.txt" | tee -a "$RUN_DIR/same-result.txt"
fi
[ "$(date +%F)" = "$TODAY" ] || log "the run passed midnight: stores parsed after it dropped another day, so results may differ"
