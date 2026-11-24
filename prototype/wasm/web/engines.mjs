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
  if (name === "graal" || name === "graal-orig") {
    // The Web Image launcher runs main, which puts railSearch on globalThis once the module has started
    if (isNode) {
      await import(`./build/${name}/search.js`);
    } else {
      const script = document.createElement("script");
      script.src = `build/${name}/search.js`;
      document.head.append(script);
    }
    while (typeof globalThis.railSearch !== "function") await new Promise(resolve => setTimeout(resolve, 10));
    const call = request => {
      const result = String(globalThis.railSearch(request));
      if (result.startsWith("ERROR ")) throw new Error(result);
      return result;
    };
    return {
      load: (file, today) => call({ op: "load", file: latin1(file), today }),
      snapshot: starts => call({ op: "snapshot", starts }),
      search: (start, transfers) => call({ op: "search", start, transfers }),
    };
  }
  throw new Error(`Unknown engine ${name}`);
}

// One char per byte, built in chunks: Web Image converts strings but not typed arrays
function latin1(file) {
  const bytes = new Uint8Array(file.buffer, file.byteOffset, file.byteLength);
  const CHUNK = 1 << 15;
  const parts = [];
  for (let at = 0; at < bytes.length; at += CHUNK) {
    parts.push(String.fromCharCode.apply(null, bytes.subarray(at, at + CHUNK)));
  }
  return parts.join("");
}
