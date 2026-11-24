// The build check (#10): the browser engine must find exactly what the server finds. For every gtfs-mini feed
// combination, the backend's BrowserDataFileTest leaves the browser file (<combination>.bin) and the server's search
// snapshot (<combination>.txt, rides as store positions) in one directory. This searches each file in the compiled
// engine and compares line by line.
// Usage: node check.mjs <engine dir> <check dir>
import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const TODAY_PREFIX = "# today ";

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
  const got = engine.snapshot().split("\n");
  const firstDiff = want.findIndex((line, i) => line !== got[i]);
  if (firstDiff < 0 && got.length === want.length) {
    console.log(`OK   ${combination}: ${want.length} lines`);
    continue;
  }
  failures++;
  const at = firstDiff < 0 ? Math.min(want.length, got.length) : firstDiff;
  console.log(`DIFF ${combination}: line ${at + 1} of ${want.length}\n  server  ${want[at]}\n  browser ${got[at]}`);
}
console.log(failures === 0 ? "The browser engine finds what the server finds"
    : `${failures} of ${combinations.length} combinations differ`);
process.exit(failures === 0 ? 0 : 1);
