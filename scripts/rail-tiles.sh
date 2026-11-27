#!/usr/bin/env bash
# Builds the rail-only basemap tiles (#93) for the dev area into backend/data/rail-tiles/, which the backend serves at
# /tiles/rail/. Downloads the Geofabrik extracts and the pinned Planetiler jar into backend/data/osm-rail/ when missing
# (all gitignored). Planetiler reads one OSM file per run, so each country is its own run, and rail-tiles-merge.mjs
# joins their tiles into one layer. Needs Java 21+ and Node.
# Usage: scripts/rail-tiles.sh
set -euo pipefail
cd "$(dirname "$0")/.."
COUNTRIES="austria hungary slovakia"
PLANETILER_VERSION=0.10.2
WORK=backend/data/osm-rail
OUT=backend/data/rail-tiles
JAR="$WORK/planetiler-$PLANETILER_VERSION.jar"

# Downloads to a .part file first, so an interrupted download is never taken for a finished one
fetch() {
  [[ -f "$2" ]] && return
  echo "Downloading $1"
  curl -fL --retry 3 -o "$2.part" "$1"
  mv "$2.part" "$2"
}

mkdir -p "$WORK"
fetch "https://github.com/onthegomap/planetiler/releases/download/v$PLANETILER_VERSION/planetiler.jar" "$JAR"
parts=()
for country in $COUNTRIES; do
  fetch "https://download.geofabrik.de/europe/$country-latest.osm.pbf" "$WORK/$country.osm.pbf"
  part="$WORK/tiles-$country"
  java -Xmx2g -jar "$JAR" generate-custom --schema=scripts/rail-tiles.yml --osm_path="$WORK/$country.osm.pbf" \
    --output="$part?format=files" --tile_compression=none --tmpdir="$WORK/tmp" --force
  parts+=("$part")
done
# Built next to the served folder and swapped in at the end, so the backend never serves half a tileset
rm -rf "$OUT.new"
node scripts/rail-tiles-merge.mjs "$OUT.new" "${parts[@]}"
rm -rf "$OUT" "${parts[@]}" "$WORK/tmp"
mv "$OUT.new" "$OUT"
echo "Rail tiles in $OUT: $(find "$OUT" -name '*.pbf' | wc -l) tiles, $(du -sh "$OUT" | cut -f1)"
