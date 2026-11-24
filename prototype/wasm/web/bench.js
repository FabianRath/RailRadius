// Prototype (#8): loads a store, times searches at 3 and 5 transfers from the bench starts, reports JS heap use.
import { engine } from "./engines.mjs";

const params = new URLSearchParams(location.search);
const name = params.get("engine") ?? "teavm-wasm";
const set = params.get("store") ?? "dev";
const today = params.get("today") ?? "2026-10-16";
const RUNS = Number(params.get("runs") ?? 5);
const LIMIT = Number(params.get("limit") ?? 1000);
const out = document.getElementById("out");
const lines = [];
function report(line) {
  lines.push(line);
  out.textContent = lines.join("\n");
}
function heapMb() {
  return performance.memory ? (performance.memory.usedJSHeapSize / 1e6).toFixed(0) + " MB JS heap" : "n/a";
}
async function measuredMb() {
  try {
    return ((await performance.measureUserAgentSpecificMemory()).bytes / 1e6).toFixed(0) + " MB page";
  } catch {
    return "page total n/a";
  }
}

const log = console.log;
console.log = () => {}; // the engine logs a stats line per search
try {
  let started = performance.now();
  const api = await engine(name);
  report(`${name}: engine ready in ${(performance.now() - started).toFixed(0)} ms`);
  started = performance.now();
  const file = new Int8Array(await (await fetch(`build/data/${set}.bin`)).arrayBuffer());
  const fetchMs = performance.now() - started;
  report(`${api.load(file, today)}; fetch ${fetchMs.toFixed(0)} ms; ${heapMb()}, ${await measuredMb()}`);
  const starts = (await (await fetch("build/data/bench-stations.txt")).text()).split(/\r?\n/)
    .map(line => line.split(/\s+/)).filter(columns => columns[0] === set).map(columns => columns[1]).slice(0, LIMIT);
  let peakHeap = 0;
  for (const start of starts) {
    for (const transfers of [3, 5]) {
      api.search(start, transfers);
      const times = [];
      let stations = 0;
      for (let i = 0; i < RUNS; i++) {
        started = performance.now();
        stations = api.search(start, transfers).split(" ")[0];
        times.push(performance.now() - started);
        peakHeap = Math.max(peakHeap, performance.memory?.usedJSHeapSize ?? 0);
      }
      times.sort((a, b) => a - b);
      report(`${start} x${transfers}: ${stations} stations, best ${times[0].toFixed(0)} ms, median ${times[Math.floor(RUNS / 2)].toFixed(0)} ms`);
      await new Promise(resolve => setTimeout(resolve)); // let the page repaint
    }
  }
  report(`after searches: ${heapMb()}, ${await measuredMb()}; highest JS heap seen ${(peakHeap / 1e6).toFixed(0)} MB`);
  report("DONE");
} catch (e) {
  report(`FAILED: ${e?.stack ?? e}`);
} finally {
  console.log = log;
}
