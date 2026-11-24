// Prototype (#8): dumps the SearchSnapshot of the dev bench starts with an engine, then times searches at 3 and 5
// transfers (one warm-up, then the best and median of RUNS). Usage, from prototype/wasm:
//   node web/dev-check.mjs <engine> <store.bin> <today> <dump-out>
import { readFileSync, writeFileSync } from "node:fs";
import { engine } from "./engines.mjs";

const [name, storeFile, today, dumpFile] = process.argv.slice(2);
const RUNS = 5;
const starts = readFileSync("../../docs/bench-stations.txt", "utf8").split(/\r?\n/)
  .map(line => line.split(/\s+/)).filter(columns => columns[0] === "dev").map(columns => columns[1]);
const api = await engine(name);
const quiet = console.log;
console.log = () => {}; // the engine logs a stats line per search
const loadInfo = api.load(new Int8Array(readFileSync(storeFile)), today);
let started = performance.now();
writeFileSync(dumpFile, api.snapshot(starts.join(",")));
const dumpMs = performance.now() - started;
const rows = [];
for (const start of starts) {
  for (const transfers of [3, 5]) {
    api.search(start, transfers);
    const times = [];
    let stations = 0;
    for (let i = 0; i < RUNS; i++) {
      started = performance.now();
      stations = api.search(start, transfers).split(" ")[0];
      times.push(performance.now() - started);
    }
    times.sort((a, b) => a - b);
    rows.push(`${start} x${transfers}: ${stations} stations, best ${times[0].toFixed(0)} ms, median ${times[2].toFixed(0)} ms`);
  }
}
console.log = quiet;
console.log(`${name}: ${loadInfo}; dump ${dumpMs.toFixed(0)} ms`);
rows.forEach(row => console.log(row));
console.log(`heap used ${(process.memoryUsage().heapUsed / 1e6).toFixed(0)} MB, rss ${(process.memoryUsage().rss / 1e6).toFixed(0)} MB`);
