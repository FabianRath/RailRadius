// Prototype (#8): runs the gtfs-mini goldens through a compiled engine in Node and diffs them line by line.
// Usage: node web/verify-goldens.mjs teavm-wasm|teavm-js   (from prototype/wasm)
import { readFileSync, readdirSync } from "node:fs";
import { engine } from "./engines.mjs";

const TODAY = "2026-01-01"; // GtfsTestFixtures.TODAY
const GOLDEN_DIR = "../../backend/src/test/resources/search-snapshots";
const api = await engine(process.argv[2]);
let failures = 0;
for (const file of readdirSync("fixtures").filter(name => name.endsWith(".bin")).sort()) {
  const combo = file.replace(/\.bin$/, "");
  api.load(new Int8Array(readFileSync(`fixtures/${file}`)), TODAY);
  const got = api.snapshot("").split("\n").filter(line => line !== "");
  const want = readFileSync(`${GOLDEN_DIR}/${combo}.txt`, "utf8").split(/\r?\n/).filter(line => line !== "");
  const firstDiff = want.findIndex((line, i) => line !== got[i]);
  const same = got.length === want.length && firstDiff < 0;
  if (!same) failures++;
  console.log(`${same ? "OK  " : "DIFF"} ${combo}: ${got.length} lines` + (same ? "" :
      ` vs ${want.length}; first diff at ${firstDiff}:\n  want ${want[firstDiff]}\n  got  ${got[firstDiff]}`));
}
console.log(failures === 0 ? "All goldens identical" : `${failures} goldens differ`);
process.exit(failures === 0 ? 0 : 1);
