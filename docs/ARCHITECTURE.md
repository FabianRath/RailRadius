# RailReach — Architecture

_Read this only when you need detail beyond CLAUDE.md. Last reviewed: 2026-10-03._
_Describes the code as it is now. Update it in the same commit as any change it covers (see CLAUDE.md → Workflow). After a full check against the code, bump the date._

## Purpose

RailReach answers the question **"Where can I get by train from station X with at most N transfers?"** across Europe.

It loads GTFS timetable feeds for ~25 European countries, keeps only **rail** routes, and builds one merged station/connection graph (with cross-border station deduplication). It then runs a transfer-bounded BFS from a start station. The result is every reachable station with transfer count, total duration, path, and a leg-by-leg itinerary (train legs and transfer waits). The browser frontend (`frontend/`, served by the backend at `http://localhost:8080/`) draws the results on a MapLibre map with OpenFreeMap's dark style and receives stations live via Server-Sent Events.

## Tech stack

- Java 25 (JDK 25 required to build), Spring Boot 4.0.2 (`spring-boot-starter-webmvc`, actuator, cache). Virtual threads are on (`spring.threads.virtual.enabled`): SSE streams run on Spring's task executor.
- Lombok (`@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`)
- Caffeine for the in-memory response cache
- GTFS CSV parsing is hand-written (no GTFS library).
- Jackson 3 (`tools.jackson`, from Spring Boot) for the on-disk JSON caches
- Maven (wrapper `mvnw` / `mvnw.cmd`). Tests use JUnit 5 + AssertJ.

## Running

Run from `backend/`; the cache paths are relative to the working directory. The commands below are for Git Bash; in PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

- **Full data:** `./mvnw package -DskipTests && java -Xmx12g -jar target/railreach-backend-0.0.1-SNAPSHOT.jar`
  - `data/hub-cache.json` is ~840 MB. `data/gtfs-cache.json` is ~360 MB (3.6 GB before the trip-based format, S9); it loads in ~17 s.
- **Dev data:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
  - Austria + Hungary + Slovakia from `data/gtfs-dev/`, cache in `data/gtfs-dev-cache.json`.

**Startup flow:**
1. `GtfsDataService` loads the GTFS cache file if present. Otherwise it parses the zips only if `auto-load-from-files` is true (true for dev, false for default). With no data, use `POST /api/admin/reload-gtfs`.
2. `HubRoutingService` loads the hub cache if present. If it's missing and `auto-initialize` is false, there are no hubs; use `POST /api/admin/compute-hubs`.

## Directory layout

```
RailReach/
├── CLAUDE.md, docs/ (ARCHITECTURE, TODO, decisions), scripts/api.sh
├── frontend/index.html               # markup; served at / by Spring (static-locations: file:../frontend/)
├── frontend/css/app.css              # all styles and the map palette (CSS variables)
├── frontend/js/                      # ES modules: main (entry), api, map (only MapLibre user), search, query-form,
│                                     #   reachability (SSE), coverage (Turf), popup, debug, util, config
├── frontend/backend_test.html        # small manual API test page
└── backend/
    ├── pom.xml
    ├── src/main/java/com/railreach/{controller,service,dto,model,config}/
    ├── src/main/resources/application.yml (+ application-dev.yml)
    ├── src/test/java/com/railreach/           # service/ (GtfsDataService, Reachability*, GtfsTestFixtures), util/ tests
    ├── src/test/resources/gtfs-mini/          # hand-written fixture feeds (see its README)
    └── data/                                  # GITIGNORED
        ├── gtfs/            # gtfs_<country>.zip, broken/, list.txt (download sources; versioned copy: docs/gtfs-sources.txt), maps
        ├── gtfs-dev/        # 3 small feeds for the dev profile
        ├── gtfs-cache.json  # processed graph: stations + trips (generated)
        └── hub-cache.json   # precomputed hub reachability (~840 MB, generated)
```

## Source files

Package `com.railreach.<sub>` lives in `backend/src/main/java/com/railreach/<sub>/`.

| File | Package | Role |
|---|---|---|
| `RailReachBackendApplication` | root | Spring Boot entry point |
| `RailReachController` | controller | Public API under `/api` |
| `AdminController` | controller | Admin/debug API under `/api/admin` |
| `GtfsDataService` | service | Fills the `TransitGraph` at startup (cache file, else zips) and on `reload-gtfs`, then rebuilds the station search index and (on reload) clears the reachability caches |
| `TransitGraph` | service | The merged network: stations, edges, connections, trip indexes. Everything that reads GTFS data injects this |
| `GtfsFeedLoader` | service | Parses the rail part of each zip into the graph, with per-feed stop dedup |
| `TripRowBatcher` | service | Buffers stop_times rows per trip and hands whole trips to the loader in batches |
| `GtfsCsvReader` | service | Row-by-row reader for one CSV file inside a GTFS zip; strips a UTF-8 BOM from the header |
| `StationSearchIndex` | service | Station name search: trigram → station positions, in degree order; rebuilt after each load |
| `StationDeduplicator` | service | Within-country and cross-border station merging after all feeds are loaded |
| `GtfsCacheStore` | service | Reads/writes the graph as the JSON cache file (`GtfsCacheData`: format version, stations, trips); a file with another version is ignored |
| `CachedTrip` | service | One trip in the cache file: route name, stop IDs, per-leg dep/arr seconds. Rebuilds the trip's edges and connections on load. Connections matching no leg (B19) are left out |
| `ReachabilityService` | service | Thin orchestrator: request defaults, POST response (`@Cacheable`), SSE streaming, triggers warming |
| `ReachabilitySearch` | service | Full and incremental trip BFS |
| `ReachabilityCache` | service | Raw BFS results per `SearchKey`; get-or-compute (incremental / full); Caffeine eviction and stats |
| `ReachabilityWarmer` | service | `@Async` predictive warming of N+1 … transfers (own bean so the proxy applies) |
| `JourneyLegBuilder` | service | BFS path → `ReachableStation` with TRAIN/TRANSFER legs |
| `HubRoutingService` | service | Top-N hub precompute and its cache file; not used by queries (B20) |
| `CacheConfig` | config | Caffeine `CacheManager` ("reachability" cache), `@EnableAsync` |
| `Station` | model | id, name, latitude, longitude, parentStation |
| `Connection` | model | One edge of one trip: tripId, routeName, from/to, dep/arr times as **int seconds since midnight** (string getters/setters for JSON) |
| `JourneyPath` | model | Internal BFS result: stationIds, connections, transfers, totalDurationMinutes |
| `ReachabilityRequest` | dto | startStationId, maxTransfers, maxJourneyTimeHours, min/maxTransferTimeMinutes |
| `ReachabilityResponse` | dto | Start info, count, calcTime, list of `ReachableStation` |
| `ReachableStation` | dto | Station + transfers, duration, path ids/names, `legs` |
| `JourneyLeg` | dto | `TRAIN` or `TRANSFER` leg with times and duration |
| `GtfsTime` | util | Parse/format GTFS times, minute diffs with overnight wrap, duration text |
| `StopIds` | util | Canonical `<source>:<rawId>` stop IDs |
| `Coordinates` | util | Degree-box proximity check |

## Data pipeline (`GtfsDataService.loadFromFiles`)

`GtfsFeedLoader`, for each `data/gtfs/*.zip` (sorted). The source name comes from the filename, e.g. `gtfs_germany.zip` → `germany`:
1. **routes.txt**: keep only rail route types `2` and `100–106`.
2. **stops.txt / trips.txt**: load stops and the trip→route map.
3. **Per-feed stop dedup**: stops with the same name within ~0.0015° are merged. IDs are canonicalised to `<source>:<id>`, with `_ARR`/`_DEP`/`ARRIVAL_` style suffixes and prefixes stripped.
4. **stop_times.txt**: streamed and buffered per trip (`TripRowBatcher`), rail trips only, and handed to the graph in batches of `railreach.gtfs.trip-batch-size` (100k) trips. A batch is only cut where a new trip starts, so trips stay whole in files grouped by `trip_id`. If a trip shows up again after another one started, the rest of the file is read in one batch, and rows of a trip already built are dropped with a WARN. Each trip's consecutive stop pairs produce:
   - `neighbors`: stopId → neighbour stopIds
   - `connections`: `"from->to"` → list of `Connection`
   - `tripStops`: tripId → ordered stop list; `stopTrips`: stopId → tripIds

A feed that adds no connections at all (e.g. unreadable headers) is logged as a `!!!` WARN.

After all feeds are loaded (`StationDeduplicator`, then `TransitGraph.mergeStations`):
5. **Within-country dedup**: same exact name, same source, within ~200 m.
6. **Cross-border dedup**: ~1 km spatial grid, different sources, within ~500 m, token Jaccard similarity ≥ 0.4. The station with more connections becomes canonical, and every map is remapped. A canonical station can itself be merged later (A→B, then B→C); `mergeStations` first resolves such chains so every alias maps straight to the last station.
7. `GtfsCacheStore` saves stations and trips (`CachedTrip`) to the GTFS cache file. On load, `TransitGraph.restore` rebuilds `neighbors`, `connections`, `tripStops` and `stopTrips` from the trips.

`StationSearchIndex.rebuild` then indexes the station names. `TransitGraph` builds lazy indexes on first access: `tripStopIndex` (trip → stop → position) and `connectionByTrip` (edge → tripId → Connection).

## Reachability algorithm (`ReachabilityCache`, `ReachabilitySearch`)

`ReachabilityCache.getOrCompute` is the central dispatcher. It is keyed by `SearchKey(start, maxTransfers, minTransfer, maxTransfer)`:
1. **Raw cache hit** → return the stored result immediately.
2. **Incremental BFS**: if the result for N−1 transfers is cached, seed the queue only from stations reached with exactly N−1 transfers and explore one more transfer layer.
3. **Full BFS** (`ReachabilitySearch.fullSearch`): a trip-based BFS. From a station, ride each trip forward stop by stop. Boarding a different trip counts as +1 transfer and must satisfy the min/max wait window. Paths are ranked by (transfers, then duration). `exploredBoardings` (`station|trip|transfers`) prevents re-walking the same trip.

Overnight wrap: a time difference below −60 min gets +24 h added.

`JourneyLegBuilder` merges consecutive connections into one TRAIN leg (same tripId, or same route name and continuous in time) and inserts TRANSFER legs between them.

**Caching layers:**
- `ReachabilityCache` (Caffeine, max `cache.raw-max-entries` (30) results) holds raw `JourneyPath` maps and enables the incremental BFS. If the N−1 result was evicted, N falls back to a full BFS.
- Caffeine `"reachability"` cache holds full `ReachabilityResponse`s from `POST /api/reachability` (max 10k entries, 60 min TTL).
- **Predictive warming**: after a streaming request, `ReachabilityWarmer` precomputes N+1 … `predictive-max-transfers` (5) with the same transfer window on a background thread, into the raw cache only (the stream never reads Caffeine).

**Streaming** (`calculateReachabilityStreaming`): the BFS runs to completion first (paths improve during the search, so nothing is sent early). The result is then sent as SSE `stations` events in batches of 30, followed by a `done` event (`{total, calculationTimeMs}`), or an `error` event on failure.

## Hub routing (`HubRoutingService`)

- Hubs are the top `num-hubs` (50) stations by out-degree.
- For each hub, a BFS with `precompute-transfers` (1) runs on a pool of up to 4 threads with a per-hub timeout. Results are saved to the hub cache file.
- Queries do not use the hubs (B20): transferring only at hubs missed most stations (Hatvan, 1 transfer: 140 instead of 782). The precompute, cache file and admin endpoints remain.

## REST API

**Public (`/api`)**, CORS `*`:
| Method | Path | Notes |
|---|---|---|
| GET | `/reachability/stream?stationId=&maxTransfers=&minTransferMinutes=0&maxTransferMinutes=180&maxJourneyHours=` | SSE; used by the frontend |
| POST | `/reachability` | JSON `ReachabilityRequest` → `ReachabilityResponse` (Caffeine-cached) |
| GET | `/stations/search?q=&limit=50` | Case-insensitive substring match; only stations with edges, sorted by degree. Candidates come from the rarest trigram of the query (`StationSearchIndex`); queries under 3 characters scan in degree order and stop at `limit` |
| GET | `/stations/{id}` | Single station |
| GET | `/stats` | stations / graphNodes / totalEdges |
| GET | `/health` | UP/DOWN depending on whether data is loaded |

**Admin (`/api/admin`)**:
| Method | Path | Notes |
|---|---|---|
| GET | `/cache-stats` | Caffeine stats, warming jobs, rawPathCache keys |
| POST | `/reload-gtfs` | Full reparse of the zips (minutes), rewrite of the cache, clears the raw and response reachability caches |
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
- `cache.predictive-enabled` (true), `cache.predictive-max-transfers` (5), `cache.raw-max-entries` (30)
- `hub.auto-initialize` (false), `hub.cache-file`, `hub.num-hubs` (50), `hub.precompute-transfers` (1), `hub.timeout-seconds` (600)

`application-dev.yml` overrides the GTFS directory and cache file, sets `auto-load-from-files: true`, turns predictive warming off and uses `data/hub-dev-cache.json`.

## Known issues and TODOs

See `docs/TODO.md`.
