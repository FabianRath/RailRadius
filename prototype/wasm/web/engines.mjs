// Prototype (#8): loads one compiled engine and returns { load, snapshot, search }, the same in Node and the browser.
const isNode = typeof process !== "undefined" && process.versions?.node;

export async function engine(name) {
  if (name === "teavm-wasm") {
    const { load } = await import("./wasm-gc-module-runtime.js");
    const teavm = await load(isNode ? "web/build/teavm-wasm/search.wasm" : "build/teavm-wasm/search.wasm");
    return teavm.exports;
  }
  if (name === "teavm-js") {
    return await import("./build/teavm-js/search.js");
  }
  throw new Error(`Unknown engine ${name}`);
}
