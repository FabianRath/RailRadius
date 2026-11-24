// The build check (#10): the browser engine must find exactly what the server finds. For every gtfs-mini feed
// combination, the backend's BrowserDataFileTest leaves the browser file (<combination>.bin) and the server's search
// snapshot (<combination>.txt, rides as store positions) in one directory. This searches each file in the compiled
// engine and compares line by line. Then it runs each snapshot's searches as the page does (search, rides, #12) and
// checks they show what the server's stream shows: each station once, with its first path within the max journey time.
// Usage: node check.mjs <engine dir> <check dir>
import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const TODAY_PREFIX = "# today ";
const SECTION = /^## (\S+) maxTransfers (\d+)$/;
// The snapshot's wait at changes
const MIN_WAIT_MINUTES = 0;
const MAX_WAIT_MINUTES = 180;
// No limit (the largest int), and one that leaves out some paths
const MAX_JOURNEY_MINUTES = [2147483647, 60];

const [engineDir, checkDir] = process.argv.slice(2).map(dir => resolve(dir));
const { load } = await import(pathToFileURL(join(engineDir, "wasm-gc-module-runtime.js")).href);
const engine = (await load(join(engineDir, "search.wasm"))).exports;

const combinations = readdirSync(checkDir).filter(name => name.endsWith(".bin"))
    .map(name => name.slice(0, -".bin".length)).sort();
if (combinations.length === 0) {
  console.error(`Nothing to check in ${checkDir}: run the backend tests first`);
  process.exit(1);
}
let failures = 0;
for (const combination of combinations) {
  const want = readFileSync(join(checkDir, `${combination}.txt`), "utf8").split("\n");
  // The snapshot pins today, which decides the running days
  engine.load(new Int8Array(readFileSync(join(checkDir, `${combination}.bin`))), want[0].slice(TODAY_PREFIX.length));
  const diff = snapshotDiff(want) ?? sections(want)
      .flatMap(section => MAX_JOURNEY_MINUTES.map(limit => searchDiff(section, limit)))
      .find(found => found);
  if (diff) {
    failures++;
    console.log(`DIFF ${combination}: ${diff}`);
  } else {
    console.log(`OK   ${combination}: ${want.length} lines`);
  }
}
console.log(failures === 0 ? "The browser engine finds what the server finds"
    : `${failures} of ${combinations.length} combinations differ`);
process.exit(failures === 0 ? 0 : 1);

/** The first line where the engine's snapshot differs from the server's; undefined if none. */
function snapshotDiff(want) {
  const got = engine.snapshot().split("\n");
  const firstDiff = want.findIndex((line, i) => line !== got[i]);
  if (firstDiff < 0 && got.length === want.length) return undefined;
  const at = firstDiff < 0 ? Math.min(want.length, got.length) : firstDiff;
  return `line ${at + 1} of ${want.length}\n  server  ${want[at]}\n  browser ${got[at]}`;
}

/** Each search of a snapshot: start, max transfers and per station its paths, fewest transfers first. */
function sections(lines) {
  const found = [];
  for (const line of lines) {
    const header = SECTION.exec(line);
    if (header) {
      found.push({ start: header[1], maxTransfers: Number(header[2]), paths: new Map() });
    } else if (found.length > 0 && line !== "") {
      const [station, transfers, minutes, rides] = line.split("\t");
      const paths = found.at(-1).paths;
      if (!paths.has(station)) paths.set(station, []);
      paths.get(station).push({ transfers: Number(transfers), minutes: Number(minutes), rides });
    }
  }
  return found;
}

/** How the engine's search differs from the stations the server's stream shows; undefined if it doesn't. */
function searchDiff({ start, maxTransfers, paths }, maxJourneyMinutes) {
  const name = `search from ${start} x${maxTransfers} within ${maxJourneyMinutes} min`;
  const shown = [];
  const total = engine.search(start, maxTransfers, MIN_WAIT_MINUTES, MAX_WAIT_MINUTES, maxJourneyMinutes,
      json => shown.push(...JSON.parse(json)));
  const want = new Map([...paths].filter(([station]) => station !== start)
      .map(([station, stationPaths]) => [station, stationPaths.find(path => path.minutes <= maxJourneyMinutes)])
      .filter(([, path]) => path));
  if (shown.length !== want.size || total !== want.size) {
    return `${name}: ${shown.length} stations shown, ${total} counted, ${want.size} expected`;
  }
  for (const station of shown) {
    const path = want.get(station.stopId);
    if (!path) return `${name}: ${station.stopId} shown twice or not by the server`;
    want.delete(station.stopId);
    const rides = JSON.parse(engine.rides(station.stopId))
        .map(ride => `${ride.trip} ${ride.boardEvent} ${ride.alightEvent}`).join(" | ");
    if (station.transfers !== path.transfers || station.totalDurationMinutes !== path.minutes || rides !== path.rides) {
      return `${name}: ${station.stopId}\n  server  ${path.transfers} ${path.minutes} ${path.rides}`
          + `\n  browser ${station.transfers} ${station.totalDurationMinutes} ${rides}`;
    }
  }
  return undefined;
}
