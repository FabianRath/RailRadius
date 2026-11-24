// Prototype (#8): serves web/ with the wasm MIME type and cross-origin isolation (for measureUserAgentSpecificMemory).
// Usage, from prototype/wasm: node web/serve.mjs [port]. Store files are read from web/build/data/.
import { createServer } from "node:http";
import { createReadStream, statSync } from "node:fs";
import { extname, join, normalize } from "node:path";

const ROOT = new URL(".", import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1");
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript",
  ".wasm": "application/wasm", ".bin": "application/octet-stream" };
const port = Number(process.argv[2] ?? 8091);

createServer((req, res) => {
  const path = normalize(join(ROOT, decodeURIComponent(new URL(req.url, "http://x").pathname)));
  if (!path.startsWith(normalize(ROOT))) { res.writeHead(403).end(); return; }
  let stat;
  try { stat = statSync(path); } catch { res.writeHead(404).end(); return; }
  if (!stat.isFile()) { res.writeHead(404).end(); return; }
  const size = stat.size;
  res.writeHead(200, { "Content-Type": TYPES[extname(path)] ?? "application/octet-stream", "Content-Length": size,
    "Cross-Origin-Opener-Policy": "same-origin", "Cross-Origin-Embedder-Policy": "require-corp",
    "Cache-Control": "no-store" });
  createReadStream(path).pipe(res);
}).listen(port, "127.0.0.1", () => console.log(`Serving ${ROOT} at http://127.0.0.1:${port}/`));
