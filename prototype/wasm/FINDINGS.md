# #8 WebAssembly prototype: findings so far (2026-11-24)

## TeaVM 0.17.0: works
- Compiles the unchanged search with these changes:
  - Backend seams: `Parallel` (all parallel streams, `parallelPrefix`, the VarHandle OR), `datesUntil` replaced by
    an epoch-day range, `writeBytes` replaced by `write`, `BinaryCacheStore.read` made package-private.
  - Prototype stand-ins: `BinaryBlocks` (reads memory), `Parallel` (sequential), `SearchRefusal` (no Spring),
    `StationSearchIndex` (none: TeaVM regex rejects `\p{L}` in a lookbehind), slf4j stub, and TeaVM emulations of
    `StringJoiner` and `ConcurrentLinkedQueue`.
- Goldens: all 12 gtfs-mini goldens identical (Wasm GC and JS). Dev data: 84,103-line dump of the 5 dev starts
  identical to the JVM's (Wasm GC and JS).
- Size (raw): search.wasm 1.12 MB, search.js 686 KB. Compressed sizes not measured yet.
- Node 24 (V8), dev data, median of 5: Wasm GC x3 13-70 ms, x5 51-105 ms; JS x3 46-230 ms, x5 166-335 ms.
  Wasm is about 3x faster than TeaVM's JS output.

## Still to do
- JVM times on the same starts (single-threaded and default), compressed sizes, desktop browser times and peak
  memory, full data, GraalVM Web Image (owner approved the GraalVM EA download; it also needs binaryen), the issue
  comment.

Run: `mvn -q -f backend/pom.xml compile`, then in prototype/wasm `mvn -q package`,
`node web/verify-goldens.mjs teavm-wasm`, `node web/dev-check.mjs teavm-wasm ../../backend/data/gtfs-dev-store.bin 2026-10-16 <out>`.
