#!/usr/bin/env bash
# Builds the track network (#110) into backend/data/rail-tracks.bin, which the backend routes journeys on so their
# lines follow the tracks. Reads the OSM extracts that scripts/rail-tiles.sh downloads (run that first), from the
# same work dir (RAIL_TILES_WORK), so the lines follow the tracks the rail tiles draw. Needs Java 25 and Maven.
# A running backend reads the file at its next start.
# Usage: scripts/rail-tracks.sh [europe]
set -euo pipefail
cd "$(dirname "$0")/.."
WORK=${RAIL_TILES_WORK:-backend/data/osm-rail}
OUT=backend/data/rail-tracks.bin
if [[ "${1:-}" == europe ]]; then
  NAMES="europe"
  HEAP=12g
else
  NAMES="austria hungary slovakia"
  HEAP=3g
fi
native_path() { cygpath -m "$1" 2> /dev/null || echo "$1"; }

extracts=()
for name in $NAMES; do
  extract="$WORK/$name.osm.pbf"
  [[ -f "$extract" ]] || { echo "Missing $extract: run scripts/rail-tiles.sh ${1:-} first" >&2; exit 1; }
  extracts+=("$(native_path "$extract")")
done
mvn -q -f backend/pom.xml package -DskipTests
jar=$(ls backend/target/railradius-backend-*.jar)
# Written next to the file and swapped in at the end, so a start never reads half a network
java -Xmx$HEAP -cp "$(native_path "$jar")" -Dloader.main=com.railradius.service.TrackNetworkExtract \
  org.springframework.boot.loader.launch.PropertiesLauncher "$(native_path "$OUT.new")" "${extracts[@]}"
mv "$OUT.new" "$OUT"
echo "Track network in $OUT: $(du -sh "$OUT" | cut -f1)"
