// Times the browser engine's search in Node (#33), as `bench.sh times` times the server's: it loads the engine the way
// the browser engine check does (browser-engine/src/test/js/check.mjs), then searches from each start at transfers
// 0 to 5 with the API's default wait at changes and no journey limit. Prints one line per search, then p50 and p95
// per transfer count, then the share of the searches at 5 transfers spent in fallback scans (#39; the engine estimates
// it from one departure in 64, and an engine from before #39 can't tell). Node 22 or later (Wasm GC); no other
// dependency. Run it through `scripts/bench.sh browser`.
// Usage: node bench-browser.mjs <engine dir> <browser file, gzipped or not> <today yyyy-mm-dd> <start>...
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { pathToFileURL } from "node:url";
import { gunzipSync } from "node:zlib";

const MAX_TRANSFERS = 5;
// The API's defaults for a request without a transfer window, as SearchSnapshot uses
const MIN_WAIT_MINUTES = 0;
const MAX_WAIT_MINUTES = 180;
// No journey limit (the largest int)
const NO_JOURNEY_LIMIT = 2147483647;
const GZIP_MAGIC = [0x1f, 0x8b];
const PERCENTILES = [50, 95];

const [engineDir, file, today, ...starts] = process.argv.slice(2);
if (!engineDir || !file || !today || starts.length === 0) {
  console.error("Usage: node bench-browser.mjs <engine dir> <browser file> <today yyyy-mm-dd> <start>...");
  process.exit(1);
}
const { load } = await import(pathToFileURL(join(resolve(engineDir), "wasm-gc-module-runtime.js")).href);
const engine = (await load(join(resolve(engineDir), "search.wasm"))).exports;

const bytes = readFileSync(file);
const gzipped = GZIP_MAGIC.every((byte, i) => bytes[i] === byte);
const loadStartMs = performance.now();
engine.load(new Int8Array(gzipped ? gunzipSync(bytes) : bytes), today);
console.log(`# loaded ${file} for ${today} in ${Math.round(performance.now() - loadStartMs)} ms`);

console.log("start transfers stations searchMs fallbackMs");
const searchMsByTransfers = Array.from({ length: MAX_TRANSFERS + 1 }, () => []);
const timesFallbackScans = typeof engine.lastFallbackScanMillis === "function";
let fallbackMsAtMax = 0;
let searchMsAtMax = 0;
for (const start of starts) {
  let reachedAny = false;
  for (let transfers = 0; transfers <= MAX_TRANSFERS; transfers++) {
    const startMs = performance.now();
    // The rounds' JSON is built as for the page, so its cost counts, as sending the stream does on the server
    const stations = engine.search(start, transfers, MIN_WAIT_MINUTES, MAX_WAIT_MINUTES, NO_JOURNEY_LIMIT, () => {});
    const exactSearchMs = performance.now() - startMs;
    const searchMs = Math.round(exactSearchMs);
    const fallbackMs = timesFallbackScans ? engine.lastFallbackScanMillis() : NaN;
    searchMsByTransfers[transfers].push(searchMs);
    if (transfers === MAX_TRANSFERS) {
      fallbackMsAtMax += fallbackMs;
      searchMsAtMax += exactSearchMs;
    }
    console.log(`${start} ${transfers} ${stations} ${searchMs} ${timesFallbackScans ? Math.round(fallbackMs) : "-"}`);
    reachedAny ||= stations > 0;
  }
  // The engine answers an unknown start with no stations, so a set that doesn't match the file would time nothing
  if (!reachedAny) {
    console.error(`No station reached from ${start}: is it in this file? Use the set of its dataset`);
    process.exit(1);
  }
}

console.log(`transfers ${PERCENTILES.map(p => `p${p}`).join(" ")}`);
searchMsByTransfers.forEach((times, transfers) => {
  console.log(`${transfers} ${PERCENTILES.map(p => percentile(times, p)).join(" ")}`);
});
console.log(timesFallbackScans
  ? `fallback scans: ${(fallbackMsAtMax * 100 / searchMsAtMax).toFixed(1)} % of search time `
    + `at ${MAX_TRANSFERS} transfers`
  : "fallback scans: not timed by this engine");

/** The nearest-rank percentile of the times. */
function percentile(times, p) {
  const sorted = [...times].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(p / 100 * sorted.length) - 1)];
}
