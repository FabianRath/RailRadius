#!/usr/bin/env bash
# Builds the rail-only basemap tiles (#93, #98) into backend/data/rail-tiles/, which the backend serves at /tiles/rail/.
# Downloads the Geofabrik extracts and the pinned Planetiler jar into the work dir when missing (default
# backend/data/osm-rail/, gitignored; RAIL_TILES_WORK moves it to a disk with room for Europe, e.g. E:/TMP/osm-rail: on
# Windows with its drive letter, as Java reads /f/TMP as C:\f\TMP).
# Planetiler reads one OSM file per run: the dev area is one run per country, joined by rail-tiles-merge.mjs; europe
# is one run on the Europe extract. Needs Java 21+ and Node.
# Usage: scripts/rail-tiles.sh [europe]
set -euo pipefail
cd "$(dirname "$0")/.."
PLANETILER_VERSION=0.10.2
WORK=${RAIL_TILES_WORK:-backend/data/osm-rail}
OUT=backend/data/rail-tiles
JAR="$WORK/planetiler-$PLANETILER_VERSION.jar"
if [[ "${1:-}" == europe ]]; then
  EXTRACTS="europe"
  # Europe's nodes don't fit in 16 GB of RAM: the node map goes to the tmpdir, memory-mapped
  HEAP=6g
else
  EXTRACTS="europe/austria europe/hungary europe/slovakia"
  HEAP=2g
fi

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
for extract in $EXTRACTS; do
  name=$(basename "$extract")
  fetch "https://download.geofabrik.de/$extract-latest.osm.pbf" "$WORK/$name.osm.pbf"
  part="$WORK/tiles-$name"
  # No --force, so Planetiler's own check stops the run when the disk is too small
  rm -rf "$part"
  # Planetiler takes the output as a URI, and would read a drive letter as its scheme
  uri="$part"
  [[ "$part" == ?:* ]] && uri="file:///$part"
  java -Xmx$HEAP -jar "$JAR" generate-custom --schema=scripts/rail-tiles.yml --osm_path="$WORK/$name.osm.pbf" \
    --output="$uri?format=files" --tile_compression=none --tmpdir="$WORK/tmp" --storage=mmap
  parts+=("$part")
done
# Built next to the served folder and swapped in at the end, so the backend never serves half a tileset
rm -rf "$OUT.new"
if (( ${#parts[@]} == 1 )); then
  mv "${parts[0]}" "$OUT.new"
else
  node scripts/rail-tiles-merge.mjs "$OUT.new" "${parts[@]}"
  rm -rf "${parts[@]}"
fi
rm -rf "$OUT" "$WORK/tmp"
mv "$OUT.new" "$OUT"
echo "Rail tiles in $OUT: $(find "$OUT" -name '*.pbf' | wc -l) tiles, $(du -sh "$OUT" | cut -f1)"
