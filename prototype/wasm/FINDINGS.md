## Prototype result: the search runs in the browser with identical results (TeaVM recommended)

Branch `prototype/wasm-search` (not for merging). Dev machine: Windows 10, 12 threads. Browser: the Claude desktop
app's Chromium pane. Node 24 for the headless checks. Today pinned to 2026-01-01 (gtfs-mini) and 2026-10-16 (dev and
full data).

### Does it compile, and what had to change?

**TeaVM 0.17.0** (Wasm GC target; JS target as a side result): yes, after these changes.
- Backend (small, behaviour-neutral, all tests and goldens pass on the JVM):
  - a `Parallel` class holds every parallel spot (`IntStream.parallel` ×5, `Arrays.parallelPrefix` ×2, the
    `VarHandle` atomic OR). The browser build swaps in a sequential one. TeaVM has neither threads nor these APIs.
  - `LocalDate.datesUntil` became an epoch-day range, and `ByteArrayOutputStream.writeBytes` became `write` (both
    missing from TeaVM's class library).
  - `BinaryCacheStore.read` became package-private (the prototype reads the file from memory).
- Replaced on the browser side only:
  - `BinaryBlocks`: reads a `byte[]`, since there is no `FileChannel`. TeaVM's buffer views also need aligned offsets,
    so it reads element by element.
  - `SearchRefusal`: no Spring parent (`ResponseStatusException`).
  - `StationSearchIndex`: none is built. TeaVM's regex engine rejects `\p{L}` in a lookbehind
    (`StationNameVariants`); the browser searches by station ID and the name search stays on the server.
  - slf4j: a stub logger (the real `LoggerFactory` needs `ServiceLoader`).
  - TeaVM emulations of `StringJoiner` (only the snapshot uses it) and `ConcurrentLinkedQueue`.
  - A `ResourceSupplier` embeds the `.txt` resources that classes read at load.

**GraalVM Web Image** (Oracle GraalVM 25.6.0-dev EA, binaryen 133): yes, with **no** changes beyond the in-memory
file reader. It compiles the backend's own classes, Spring exception, regex name index, real slf4j and parallel streams
included (all goldens identical). But:
- It is experimental (EA build; `@JS.Export` is not implemented, so functions are exported through a `globalThis`
  bootstrap).
- It has no typed-array interop: a JS `Int8Array` can't become a Java `byte[]`. The prototype passes the store as a
  Latin-1 string (one char per byte), which doubles the load time on full data.
- On Windows, `native-image` refuses to start without Visual Studio Build Tools ≥ 17.6, even though the Wasm target
  links no C code. The build had to run inside a `vcvars64` environment.

### Size of the compiled module

| | raw | gzip -9 | Brotli 11 |
|---|---|---|---|
| TeaVM Wasm GC (`search.wasm` + 36 KB runtime JS) | 835 KB | 295 KB | **211 KB** |
| TeaVM JS | 540 KB | 174 KB | 142 KB |
| Web Image (`search.js.wasm` + 111 KB launcher JS) | 8.26 MB | 3.22 MB | **2.38 MB** |

For comparison, the dev store is 6.4 MB (1.4 MB with Brotli) and the full store 168 MB (#9 trims it).

### Search time vs the JVM

Median of 5 runs (3 on full data) after one warm-up; ms. JVM 1 thread = common pool parallelism 0.

**Dev data**, desktop browser:

| start | JVM 12 threads x3 / x5 | JVM 1 thread x3 / x5 | TeaVM Wasm x3 / x5 | Web Image x3 / x5 |
|---|---|---|---|---|
| Wien Hbf | 45 / 38 | 82 / 82 | 112 / 141 | 134 / 184 |
| Budapest-Keleti | 18 / 29 | 35 / 66 | 64 / 122 | 57 / 134 |
| Bratislava hl.st. | 19 / 32 | 37 / 68 | 75 / 110 | 87 / 142 |
| Hegyeshalom | 21 / 33 | 43 / 79 | 74 / 143 | 105 / 168 |
| Pribeník | 5 / 18 | 8 / 38 | 14 / 60 | 15 / 59 |

In Node (V8, no page around it), TeaVM Wasm is a bit faster than in the pane (x5: 51–105 ms) and TeaVM's JS output is
about 3× slower than its Wasm (x5: 166–335 ms).

**Full data** (all of Europe, 34,062 stations), first four `full` bench starts:

| start | JVM 12 threads x3 / x5 | JVM 1 thread x3 / x5 | TeaVM Wasm x3 / x5 | Web Image x3 / x5 |
|---|---|---|---|---|
| Wien Hbf | 433 / 1,309 | 959 / 3,676 | 1,760 / 5,483 | 1,836 / 6,014 |
| Paris Gare du Nord | 708 / 1,945 | 1,904 / 6,297 | 3,014 / 8,762 | 3,444 / 9,363 |
| Pribeník | 24 / 378 | 42 / 879 | 155 / 1,398 | 151 / 1,539 |
| Hegyeshalom | 199 / 937 | 411 / 2,508 | 648 / 3,698 | 714 / 4,026 |

Every engine reaches the same station count as the JVM on every start.
- In the browser, TeaVM Wasm takes **1.4–1.6× as long as one JVM thread**, and Web Image another 7–10% longer.
- Against the server's parallel search, the browser takes about 4× as long: a 5-transfer search from a big hub takes
  5–9 s in the browser instead of 1.3–1.9 s on an idle server.
- Load (parse the store and build the graph): TeaVM 1.8 s, Web Image 3.4 s on full data; under 0.2 s on dev data.

### Peak browser memory

Taken from `performance.memory.usedJSHeapSize`, which in V8 includes Wasm GC objects.
`measureUserAgentSpecificMemory` is not available in the pane.

| | dev data | full data |
|---|---|---|
| TeaVM Wasm | 65 MB | ~0.95 GB after the searches; up to 1.3 GB while the file's copies are still uncollected |
| Web Image | 83 MB | ~0.96–0.98 GB |

On full data, the downloaded file, its Java copy and the store sit in memory together at load. #9 shrinks all three.

### Identical results on the goldens

- **gtfs-mini:** all 12 `SearchSnapshot` goldens are line-identical for TeaVM Wasm, TeaVM JS and Web Image (both
  builds). Each feed combination's binary store is exported by `PrototypeFixtureExport`, and every station is
  searched at 0–5 transfers.
- **Dev data:** the 84,103-line `SearchSnapshot` dump of the 5 `dev` bench starts is byte-identical to the JVM's for
  all four builds.

### Recommendation: TeaVM (Wasm GC target), no JavaScript port

- **11× smaller download** (211 KB vs 2.4 MB with Brotli), a bit faster, and it takes typed arrays directly.
- **A Maven plugin** from Maven Central. Web Image needs an EA JDK plus binaryen, and Visual Studio on Windows.
- **The price** is a few seams in the backend (the `Parallel` class, a Spring-free `SearchRefusal`, no name index
  in the browser build, two JDK calls rewritten). The build check (#10) keeps both engines honest.

To decide before #12:
- **Speed:** a 5-transfer search from a big hub takes 5–9 s in one browser thread instead of ~1.5 s on an idle server.
  It moves load off the server but is slower for that visitor unless the server is busy. TeaVM's Wasm GC target has
  no threads, so speeding it up would mean splitting rounds over Web Workers, which is not trivial.
  - Option A: keep the server for the first and for big-hub 5-transfer searches.
  - Option B: accept the wait and show progress (the search already reports each round).
- **Memory:** about 1 GB of heap on full data. `navigator.deviceMemory` ≥ 8 is a sensible gate for #12 until #9
  shows the trimmed numbers.

### Reproduce (from the repo root, on the branch)

```
mvn -q -f backend/pom.xml compile
mvn -q -f prototype/wasm/pom.xml package                  # TeaVM, into prototype/wasm/web/build/
cd prototype/wasm
node web/verify-goldens.mjs teavm-wasm                     # or teavm-js, graal, graal-orig
node web/dev-check.mjs teavm-wasm ../../backend/data/gtfs-dev-store.bin 2026-10-16 <dump-file>
node web/serve.mjs 8091                                    # then /bench.html?engine=…&store=dev|full&limit=4&runs=3
```

- Browser full data: copy `gtfs-store.bin` to `web/build/data/full.bin` (dev: `dev.bin`, plus `bench-stations.txt`).
- JVM times: `prototype/wasm/jvm/JvmBench.java`.
- Web Image: `javac` `graal/GraalEntry.java` against `target/classes` and the `org.graalvm.webimage.api` module,
  then `native-image --tool:svm-wasm` inside `vcvars64` with binaryen on the PATH.
