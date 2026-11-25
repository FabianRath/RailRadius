#!/usr/bin/env bash
# Zips the gtfs-mini test feeds into backend/data/gtfs-mini/ for the offline "mini" profile (#21).
# Leaves out the feeds that only make sense in their own tests: alpha-fast-train (a second "alpha"), kappa and
# lambda (their countries exist only in the test borders), eta, theta and zeta (their trains run in early 2026 only).
# Uses the JDK's jar tool, which also exists in Git Bash on Windows, where zip usually does not.
set -euo pipefail
cd "$(dirname "$0")/.."
FEEDS="alpha beta gamma delta epsilon omega iota germany netherlands poland france switzerland"
OUT=backend/data/gtfs-mini
mkdir -p "$OUT"
for feed in $FEEDS; do
  rm -f "$OUT/gtfs_$feed.zip"
  jar --create --no-manifest --file "$OUT/gtfs_$feed.zip" -C "backend/src/test/resources/gtfs-mini/$feed" .
done
# A store built from older zips would be loaded instead of these
rm -f backend/data/gtfs-mini-store.bin
echo "Zipped $(echo $FEEDS | wc -w) feeds into $OUT"
