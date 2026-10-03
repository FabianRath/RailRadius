# RailReach — Architecture

_Read this only when you need detail beyond CLAUDE.md. Last reviewed: 2026-10-03._

## Purpose

RailReach answers the question **"Where can I get by train from station X with at most N transfers?"** across Europe.

It loads GTFS timetable feeds for ~25 European countries, keeps only **rail** routes, and builds one merged station/connection graph (with cross-border station deduplication). It then runs a transfer-bounded BFS from a start station. The result is every reachable station with transfer count, total duration, path, and a leg-by-leg itinerary (train legs and transfer waits). The browser frontend (`frontend/index.html`) draws the results on a map and receives stations live via Server-Sent Events.

## Tech stack

- Java 25 (JDK 25 required to build), Spring Boot 4.0.2 (`spring-boot-starter-webmvc`, actuator, cache). Virtual threads are on (`spring.threads.virtual.enabled`): SSE streams run on Spring's task executor.
- Lombok (`@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`)
- Caffeine for the in-memory response cache
- `com.conveyal:gtfs-lib` 6.1.0. Only its `Stop` / `StopTime` POJOs are used; GTFS CSV parsing is hand-written.
- Jackson for the on-disk JSON caches
- Maven (wrapper `mvnw` / `mvnw.cmd`). Tests use JUnit 5 + AssertJ.

## Running

Run from `backend/`; the cache paths are relative to the working directory.

- **Full data:** `./mvnw package -DskipTests && java -Xmx12g -jar target/railreach-backend-0.0.1-SNAPSHOT.jar`
  - `data/gtfs-cache.json` is ~4.2 GB and `data/hub-cache.json` ~840 MB.
- **Dev data:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
  - Austria + Hungary + Slovakia from `data/gtfs-dev/`, cache in `data/gtfs-dev-cache.json`.

**Startup flow:**
1. `GtfsDataService` loads the GTFS cache file if present. Otherwise it parses the zips only if `auto-load-from-files` is true (true for dev, false for default). With no data, use `POST /api/admin/reload-gtfs`.
2. `HubRoutingService` loads the hub cache if present. If it's missing and `auto-initialize` is false, there are no hubs; use `POST /api/admin/compute-hubs`.

## Directory layout

```
RailReach/
├── CLAUDE.md, docs/ (ARCHITECTURE, TODO, decisions), scripts/api.sh
├── frontend/index.html               # single-file map UI (Leaflet), talks to localhost:8080
├── frontend/backend_test.html        # small manual API test page
└── backend/
    ├── pom.xml
    ├── src/main/java/com/railreach/{controller,service,dto,model,config}/
    ├── src/main/resources/application.yml (+ application-dev.yml)
    ├── src/test/java/com/railreach/service/   # GtfsDataServiceTest, ReachabilityServiceTest, GtfsTestFixtures
    ├── src/test/resources/gtfs-mini/          # hand-written fixture feeds (see its README)
    └── data/                                  # GITIGNORED
        ├── gtfs/            # gtfs_<country>.zip, broken/, list.txt (download sources; versioned copy: docs/gtfs-sources.txt), maps
        ├── gtfs-dev/        # 3 small feeds for the dev profile
        ├── gtfs-cache.json  # processed graph (~4.2 GB, generated)
        └── hub-cache.json   # precomputed hub reachability (~840 MB, generated)
```

## Source files

Package `com.railreach.<sub>` lives in `backend/src/main/java/com/railreach/<sub>/`.

| File | Package | Role |
|---|---|---|
| `RailReachBackendApplication` | root | Spring Boot entry point |
| `RailReachController` | controller | Public API under `/api` |
| `AdminController` | controller | Admin/debug API under `/api/admin` |
| `GtfsDataService` | service | GTFS loading, dedup, graph + indexes, JSON cache (~1100 lines) |
| `ReachabilityService` | service | BFS algorithms, caching, SSE streaming, leg building (~870 lines) |
| `HubRoutingService` | service | Top-N hub precompute and hub-based query shortcut |
| `CacheConfig` | config | Caffeine `CacheManager` ("reachability" cache), `@EnableAsync` |
| `ServiceConfig` | config | Wires `HubRoutingService` into `ReachabilityService` (breaks the circular dependency with `@Lazy`) |
| `Station` | model | id, name, lat, lon, parentStation |
| `Connection` | model | One edge of one trip: tripId, routeId/name, from/to, dep/arr times as **int seconds since midnight** (string getters/setters for JSON) |
| `JourneyPath` | model | Internal BFS result: stationIds, connections, transfers, totalDurationMinutes |
| `StopSequence` | model | Intermediate stop (mostly unused) |
| `ReachabilityRequest` | dto | startStationId, maxTransfers, maxJourneyTimeHours, min/maxTransferTimeMinutes |
| `ReachabilityResponse` | dto | Start info, count, calcTime, list of `ReachableStation` |
| `ReachableStation` | dto | Station + transfers, duration, path ids/names, `legs` |
| `JourneyLeg` | dto | `TRAIN` or `TRANSFER` leg with times and duration |

## Data pipeline (`GtfsDataService.loadFromFiles`)

For each `data/gtfs/*.zip` (sorted). The source name comes from the filename, e.g. `gtfs_germany.zip` → `germany`:
1. **routes.txt**: keep only rail route types `2` and `100–106`.
2. **stops.txt / trips.txt**: load stops and the trip→route map.
3. **Per-feed stop dedup**: stops with the same name within ~0.0015° are merged. IDs are canonicalised to `<source>:<id>`, with `_ARR`/`_DEP`/`ARRIVAL_` style suffixes and prefixes stripped.
4. **stop_times.txt**: streamed and buffered per trip in batches of 100k trips, rail trips only. Each trip's consecutive stop pairs produce:
   - `graph`: stopId → neighbour stopIds
   - `connectionInfo`: `"from->to"` → list of `Connection`
   - `tripStops`: tripId → ordered stop list; `stopTrips`: stopId → tripIds

After all feeds are loaded:
5. **Within-country dedup**: same exact name, same source, within ~200 m.
6. **Cross-border dedup**: ~1 km spatial grid, different sources, within ~500 m, token Jaccard similarity ≥ 0.4. The station with more connections becomes canonical, and every map is remapped.
7. The result is saved to the GTFS cache file.

Lazy indexes are built on first access: `tripStopIndex` (trip → stop → position) and `connectionByTrip` (edge → tripId → Connection).

## Reachability algorithm (`ReachabilityService`)

`resolveReachability` is the central dispatcher. It is keyed by `start-maxTransfers-minTransfer-maxTransfer`:
1. **rawPathCache hit** → return the stored result immediately.
2. **Incremental BFS**: if the result for N−1 transfers is cached, seed the queue only from stations reached with exactly N−1 transfers and explore one more transfer layer.
3. **Hub routing**: if hubs are loaded and N ≥ 1, run BFS from the start to hubs, then splice in the precomputed hub→destination paths after checking the transfer wait at the hub.
4. **Full BFS** (`runBFSInternal`): a trip-based BFS. From a station, ride each trip forward stop by stop. Boarding a different trip counts as +1 transfer and must satisfy the min/max wait window. Paths are ranked by (transfers, then duration). `exploredBoardings` (`station|trip|transfers`) prevents re-walking the same trip.

Overnight wrap: a time difference below −60 min gets +24 h added.

`buildLegs` merges consecutive connections into one TRAIN leg (same tripId, or same route name and continuous in time) and inserts TRANSFER legs between them.

**Caching layers:**
- `rawPathCache` (unbounded `ConcurrentHashMap`) holds raw `JourneyPath` maps and enables the incremental BFS.
- Caffeine `"reachability"` cache holds full `ReachabilityResponse`s from `POST /api/reachability` (max 10k entries, 60 min TTL).
- **Predictive warming**: after a streaming request, `warmHigherTransferCounts` precomputes N+1 … `predictive-max-transfers` (5).

**Streaming** (`calculateReachabilityStreaming`): stations are sent as SSE `stations` events in batches (30 stations or 400 ms), then a `done` event (`{total, calculationTimeMs}`), or an `error` event on failure.

## Hub routing (`HubRoutingService`)

- Hubs are the top `num-hubs` (50) stations by out-degree.
- For each hub, a BFS with `precompute-transfers` (1) runs on a 4-thread pool with a per-hub timeout. Results are saved to the hub cache file.
- `queryViaHubs`: BFS from the start with `min(N−1, 2)` transfers, then combine with each reachable hub's precomputed paths.

## REST API

**Public (`/api`)**, CORS `*`:
| Method | Path | Notes |
|---|---|---|
| GET | `/reachability/stream?stationId=&maxTransfers=&minTransferMinutes=0&maxTransferMinutes=180&maxJourneyHours=` | SSE; used by the frontend |
| POST | `/reachability` | JSON `ReachabilityRequest` → `ReachabilityResponse` (Caffeine-cached) |
| GET | `/stations/search?q=&limit=50` | Substring match; only stations with edges, sorted by degree |
| GET | `/stations/{id}` | Single station |
| GET | `/stats` | stations / graphNodes / totalEdges |
| GET | `/health` | UP/DOWN depending on whether data is loaded |

**Admin (`/api/admin`)**:
| Method | Path | Notes |
|---|---|---|
| GET | `/cache-stats` | Caffeine stats, warming jobs, rawPathCache keys |
| POST | `/reload-gtfs` | Full reparse of the zips (minutes) and rewrite of the cache |
| DELETE | `/gtfs-cache` | Deletes the cache file |
| GET | `/gtfs-stats` | Cache file and data counts |
| POST | `/compute-hubs` | Recompute hubs (5–10 min) |
| DELETE | `/hub-cache` | Delete the hub cache and clear hubs from memory |
| GET | `/hub-stats` | Hub count and paths |
| POST / DELETE | `/debug/trigger` | Toggle the frontend overlay that shows all stations |
| GET | `/debug/status` | Polled by the frontend every 2 s |
| GET | `/debug/stations` | All connected stations with edge counts |

## Configuration (`application.yml`, prefix `railreach.`)

- `gtfs.directory` (`./data/gtfs`, env `GTFS_DIRECTORY`), `gtfs.auto-load-from-files` (false), `gtfs.cache-file`
- `algorithm.max-transfer-time-minutes` (180), `algorithm.cache-size` (10000), `algorithm.cache-ttl-minutes` (60)
- `cache.predictive-enabled` (true), `cache.predictive-max-transfers` (5)
- `hub.auto-initialize` (false), `hub.cache-file`, `hub.num-hubs` (50), `hub.precompute-transfers` (1), `hub.timeout-seconds` (600)

## Known issues and TODOs

See `docs/TODO.md`.
