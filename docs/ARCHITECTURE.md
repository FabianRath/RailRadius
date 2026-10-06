# RailReach — Architecture

_Read this only when you need detail beyond CLAUDE.md. Last reviewed: 2026-10-03._
_Describes the code as it is now. Update it in the same commit as any change it covers (see CLAUDE.md → Workflow). After a full check against the code, bump the date._

## Purpose

RailReach answers the question **"Where can I get by train from station X with at most N transfers?"** across Europe.

It loads GTFS timetable feeds for ~25 European countries, keeps only **rail** routes, and builds one merged station/connection graph (with cross-border station deduplication). It then runs a transfer-bounded BFS from a start station. The result is every reachable station with transfer count, total duration, path, and a leg-by-leg itinerary (train legs and transfer waits). The browser frontend (`frontend/`, served by the backend at `http://localhost:8080/`) draws the results on a MapLibre map with OpenFreeMap's dark style and receives stations live via Server-Sent Events.

## Tech stack

- Java 25 (JDK 25 required to build), Spring Boot 4.0.2 (`spring-boot-starter-webmvc`, actuator, cache). Virtual threads are on (`spring.threads.virtual.enabled`): SSE streams run on Spring's task executor (`applicationTaskExecutor`, named explicitly because `@EnableScheduling` adds a task scheduler of the same type).
- Lombok (`@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`)
- Caffeine for the in-memory response cache
- GTFS CSV parsing is hand-written (no GTFS library).
- Jackson 3 (`tools.jackson`, from Spring Boot) for the on-disk JSON caches
- Maven (wrapper `mvnw` / `mvnw.cmd`). Tests use JUnit 5 + AssertJ.

## Running

Run from `backend/`; the cache paths are relative to the working directory. The commands below are for Git Bash; in PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

- **Full data:** `./mvnw package -DskipTests && java -Xmx12g -jar target/railreach-backend-0.0.1-SNAPSHOT.jar`
  - `data/gtfs-cache.json` is ~360 MB (3.6 GB before the trip-based format, S9); it loads in ~17 s.
- **Dev data:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
  - Austria + Hungary + Slovakia from `data/gtfs-dev/`, cache in `data/gtfs-dev-cache.json`.

**Startup flow:**
1. `GtfsDataService` loads the GTFS cache file if present. Otherwise it parses the zips only if `auto-load-from-files` is true (true for dev, false for default). With no data, use `POST /api/admin/reload-gtfs`.

## Directory layout

```
RailReach/
├── CLAUDE.md, docs/ (ARCHITECTURE, TODO, decisions), scripts/api.sh
├── frontend/index.html               # markup; served at / by Spring (static-locations: file:../frontend/)
├── frontend/css/app.css              # all styles and the map palette (CSS variables)
├── frontend/js/                      # ES modules: main (entry), api, map (only MapLibre user), search, query-form,
│                                     #   reachability (SSE), coverage (Turf), popup, debug, dev-auth (dev login), admin-panel (admin
│                                     #   endpoints as buttons), feeds (Feeds tab of the admin panel), sidebar (collapse toggle), icons (vendored Lucide SVGs;
│                                     #   `<span data-icon>` in the markup), util, config
├── frontend/backend_test.html        # small manual API test page
└── backend/
    ├── pom.xml
    ├── src/main/java/com/railreach/{controller,service,dto,model,config}/
    ├── src/main/resources/application.yml (+ application-dev.yml), borders/europe.geojson (country borders),
    │                                          #   station-aliases.txt, train-categories.txt, train-category-columns.txt,
    │                                          #   gtfs-feeds.txt (download URLs per feed)
    ├── src/test/java/com/railreach/           # service/ (GtfsDataService, Reachability*, GtfsTestFixtures), util/, config/ tests
    ├── src/test/resources/gtfs-mini/          # hand-written fixture feeds (see its README)
    └── data/                                  # GITIGNORED
        ├── gtfs/            # gtfs_<country>.zip, previous/ (zips replaced by the update center), broken/, list.txt (old download list), maps
        ├── gtfs-dev/        # 3 small feeds for the dev profile
        └── gtfs-cache.json  # processed graph: stations + trips (generated)
```

## Source files

Package `com.railreach.<sub>` lives in `backend/src/main/java/com/railreach/<sub>/`.

| File | Package | Role |
|---|---|---|
| `RailReachBackendApplication` | root | Spring Boot entry point |
| `RailReachController` | controller | Public API under `/api` |
| `AdminController` | controller | Admin/debug API under `/api/admin` |
| `GtfsDataService` | service | Fills the `TransitGraph` at startup (cache file, else zips) and on `reload-gtfs`, then rebuilds the station search index and (on reload) clears the reachability caches |
| `TransitGraph` | service | The merged network: stations, their translated names, edges, connections, trip indexes, `TripInfo`s and each trip's `ServiceDays`. Everything that reads GTFS data injects this |
| `GtfsFeedLoader` | service | Parses the rail part of each zip into the graph, with per-feed stop dedup |
| `RailTripReader` | service | A feed's rail routes (name, operator from `agency.txt`) and rail trips (route, service ID, `TripInfo`) from routes/agency/trips.txt |
| `RareTrainFilter` | service | Drops a feed's trains (route + train number, all their trips together) that run on fewer than 8 days, before stop_times are read (B26) |
| `ServiceCalendarReader` | service | A feed's services and the days they run, from `calendar.txt` (weekly pattern in a date range) and `calendar_dates.txt` (days added or removed) |
| `ServiceDays` | service | The days one service runs, as a bitset over epoch days; `sharesDayWith(later, dayShift)` for the transfer check, `intersect(later, dayShift)` and `union` for a journey's days, `dayCount`. `EVERY_DAY` for trips without a calendar entry |
| `TripInfo` | service | A trip's train number (`trip_short_name`, leading zeros of a numeric one stripped, prefixed with a `TrainCategories` category), headsign and operator |
| `TrainCategories` | service | Where a train's category comes from: per source the trips.txt column that holds it, from `train-category-columns.txt` (Netherlands `trip_long_name`, Poland `plk_category_code`); else, for bare train numbers, rules by source, agency, route type and optionally route short name, from `train-categories.txt` (e.g. DELFI's DB Fernverkehr: 101 → `ICE`, 102 → `IC`/`ECE`) |
| `GtfsLoadProgress` | service | Phase (PARSING → MERGING → SAVING → DONE/FAILED), feeds and zip bytes parsed of the running or last load from the zips; read by `/gtfs-load-status` |
| `TripRowBatcher` | service | Buffers stop_times rows per trip and hands whole trips to the loader in batches |
| `StopNameTranslations` | service | A feed's translated `stop_name`s from `translations.txt`, by stop ID or by untranslated name; reads the current and the pre-2019 (`trans_id`) format |
| `GtfsCsvReader` | service | Row-by-row reader for one CSV file inside a GTFS zip; strips a UTF-8 BOM from the header |
| `StationSearchIndex` | service | Station name search over the name variants of each station's name and translated names: trigram → station positions, in degree order; rebuilt after each load |
| `StationNameVariants` | service | Searchable forms of a station name: folded (lowercase, no accents), German umlaut spelling (`koeln`), and the local city name replaced by its translations from `station-aliases.txt` (`cologne`) |
| `StationDeduplicator` | service | Within-country and cross-border station merging after all feeds are loaded; runs the merge rules in order |
| `StationGrid` | service | Stations bucketed into square degree cells; `near` = the 3×3 cells around a station |
| `SharedTrainMatcher` | service | Cross-border rule: two stops are one station if ≥ 3 trains serve both at the same minute with the same neighbour station |
| `ForeignStopSnapper` | service | Cross-border rule: a feed's stop outside its home country is merged into the nearest station of that country's feed (≤ 1 km) |
| `FeedUpdateCenter` | service | Update center for the GTFS zips: status of each zip (`FeedStatus`, validity memoized per file and modification time), a scheduled log check (`railreach.feeds.check-interval`, first run at startup) that WARNs about expired and expiring feeds, and downloads of registered feeds. A download is refused (`BUSY`) during a GTFS load or another download |
| `FeedValidity` | service | First and last day any service of a feed runs, from `ServiceCalendarReader` over the whole feed (not only rail) |
| `FeedRegistry` | service | Download URLs (with request headers) per source, from `gtfs-feeds.txt`; a source on several lines gets fallback URLs in line order |
| `FeedDownloader` | service | Downloads a feed, trying its URLs in order until one passes the checks below (JDK `HttpClient`, unpacks `Content-Encoding: gzip`) to `gtfs_<source>.zip.part`, checks it (a zip with stops/stop_times/trips/routes.txt, at least one rail route, service days in the calendar, at least half the size of the current zip, running today, not ending before the current zip), moves the old zip to `previous/` and moves the new one in. If every URL fails or is rejected, the zip is unchanged and the result lists each URL's reason |
| `CountryLocator` | service | Point → ISO country code, from `borders/europe.geojson` (Natural Earth 1:10m, Europe) |
| `GtfsCacheStore` | service | Reads/writes the graph as the JSON cache file (`GtfsCacheData`: format version, stations, translated names, services, trips); a file with another version is ignored |
| `CachedTrip` | service | One trip in the cache file: route name, `TripInfo`, service ID, stop IDs, per-leg dep/arr seconds. Rebuilds the trip's edges and connections on load. Connections matching no leg (B19) are left out |
| `ReachabilityService` | service | Thin orchestrator: request defaults, POST response (`@Cacheable`), SSE streaming, triggers warming |
| `ReachabilitySearch` | service | Round-based search, one round per transfer; best path per station and transfer count |
| `TripTimetable` | service | One trip as arrays for the search: stops, service days, leg times, minute offsets; `Departure` = trip leaving a stop |
| `TransferWindow` | service | Sliding window over a station's arrivals: the arrival that boards a departure with the shortest journey, among those passing a filter (the day check); scans the wait range when the window's best fails it |
| `ReachabilityCache` | service | Raw search results per `SearchKey`; get-or-compute (stored, cut down from a stored higher transfer count, or searched); Caffeine eviction and stats |
| `ReachabilityWarmer` | service | `@Async` predictive warming of the maximum transfer count (own bean so the proxy applies) |
| `JourneyLegBuilder` | service | Search path → `ReachableStation` with TRAIN/TRANSFER legs |
| `JourneyRunningDays` | service | Days on which every train of a returned journey runs, and their summary ("Runs Mon–Fri", "Irregular: 2 days in the timetable") |
| `RegularJourneyChooser` | service | Picks a station's journey among the search's candidates with the same transfer count: the fastest that runs on at least half as many days as the most frequent one (B26) |
| `CacheConfig` | config | Caffeine `CacheManager` ("reachability" cache), `@EnableAsync` |
| `AdminTokenInterceptor` | config | Guards `/api/admin/**`: `X-Admin-Token` must equal `railreach.admin.token` (constant-time); 401 if wrong or missing, 403 for every call when no token is configured; CORS preflights pass |
| `WebConfig` | config | Registers `AdminTokenInterceptor` on `/api/admin/**` (same path matching as the controllers) |
| `Station` | model | id, name, latitude, longitude, parentStation, country (ISO code from the coordinates, null if outside every border) |
| `Connection` | model | One edge of one trip: tripId, routeName, from/to, dep/arr times as **int seconds since midnight** (string getters/setters for JSON) |
| `JourneyPath` | model | Internal search result: stationIds, connections, transfers, totalDurationMinutes |
| `ReachabilityRequest` | dto | startStationId, maxTransfers, maxJourneyTimeHours, min/maxTransferTimeMinutes |
| `ReachabilityResponse` | dto | Start info, count, calcTime, list of `ReachableStation` |
| `ReachableStation` | dto | Station (incl. country) + transfers, duration, path ids/names, `legs`, `runningDays` (summary, null without calendar data), `hasCommonDay` |
| `GtfsLoadStatus` | dto | `GtfsLoadProgress` snapshot plus live station/connection counts; ETA extrapolated from zip bytes parsed |
| `JourneyLeg` | dto | `TRAIN` or `TRANSFER` leg with from/to station names and countries, times and duration; TRAIN legs also carry route name, train number, headsign and operator |
| `FeedStatus` | dto | One zip: source, file name, size, download time, first/last service date, days left, state (`OK`, `EXPIRING`, `EXPIRED`, `NOT_YET_VALID`, `NO_CALENDAR`, `UNREADABLE`), download URL (the first one in `gtfs-feeds.txt`; null = updated by hand) |
| `FeedDownloadResult` | dto | Outcome (`REPLACED`, `REJECTED`, `BUSY`), message, `FeedStatus` before and after |
| `GtfsTime` | util | Parse/format GTFS times, minute diffs with overnight wrap, clock minute of a time, duration text |
| `StopIds` | util | Canonical `<source>:<rawId>` stop IDs |
| `FeedFiles` | util | Feed zip names: `gtfs_<source>.zip` ↔ source |
| `ResourceLines` | util | Lines of a classpath `.txt` config file without blanks and `#` comments |
| `Coordinates` | util | Degree-box proximity check, distance in metres |

## Data pipeline (`GtfsDataService.loadFromFiles`)

`GtfsFeedLoader`, for each `data/gtfs/*.zip` (sorted). The source name comes from the filename, e.g. `gtfs_germany.zip` → `germany`:
1. **routes.txt / agency.txt** (`RailTripReader`): keep only rail route types `2`, `100–106` and `109` (S-Bahn), each with its operator (`agency_name`; a route without `agency_id` gets the feed's only agency).
2. **stops.txt / trips.txt / calendar**: load stops and the rail trips: route, service ID and `TripInfo` (train number, headsign, operator). `ServiceCalendarReader` reads the days of each service; the services of rail trips go to the graph as `<source>:<serviceId>`. A trip whose service is in neither calendar file (or whose feed has neither) runs every day, with a WARN count. `RareTrainFilter` then drops every train that runs on fewer than 8 days of the feed, past days included: one-off specials and timetable-change leftovers (B26: NJ 479 ran 3 days, the regular NJ 469 is 15 min slower from Aachen). A train is all trips of one route with the same train number, because feeds split one train into several trips with different days. Trips without a train number are kept, and so is a train with a trip that has no calendar entry. A trip with a value in its feed's category column (`train-category-columns.txt`) gets that category in front of its train number: `2835` → `Intercity 2835`, `1900/1 Zosia` → `IC 1900/1 Zosia`. Otherwise a numeric train number on a route that matches a `train-categories.txt` rule gets the rule's category: `002310` → `ICE 2310`.
3. **Per-feed stop dedup**: a stop whose `parent_station` is in the feed (with coordinates) is merged into that parent, at any distance. The other stops are merged when they have the same name and lie within ~0.0015°. IDs are canonicalised to `<source>:<id>`, with `_ARR`/`_DEP`/`ARRIVAL_` style suffixes and prefixes stripped.
4. **stop_times.txt**: streamed and buffered per trip (`TripRowBatcher`), rail trips only, and handed to the graph in batches of `railreach.gtfs.trip-batch-size` (100k) trips. A batch is only cut where a new trip starts, so trips stay whole in files grouped by `trip_id`. If a trip shows up again after another one started, the rest of the file is read in one batch, and rows of a trip already built are dropped with a WARN. Each trip's consecutive stop pairs produce:
   - `neighbors`: stopId → neighbour stopIds
   - `connections`: `"from->to"` → list of `Connection`
   - `tripStops`: tripId → ordered stop list; `tripInfos`: tripId → `TripInfo`; `tripServiceDays`: tripId → its service's shared `ServiceDays`; `stopTrips`: stopId → tripIds

   Then `translations.txt` (optional, `StopNameTranslations`): each stop's translated `stop_name`s go to its canonical station in `translatedNames` (stopId → names), minus the station's own name, for stations that a rail trip added. Only search uses them; the API returns the original name.

A feed that adds no connections at all (e.g. unreadable headers) is logged as a `!!!` WARN.

After all feeds are loaded (`StationDeduplicator`, then `TransitGraph.mergeStations`), every station first gets its `country` from `CountryLocator`; a merge keeps the canonical station's country.
5. **Within-country dedup**: same exact name, same source, within ~200 m.
6. **Cross-border dedup**, on pairs of stations of different sources within ~500 m (found via a ~1 km `StationGrid`), in two passes:
   - **by name**: token Jaccard similarity ≥ 0.4, ignoring case and accents (`StationNameVariants.fold`);
   - **by shared trains** (`SharedTrainMatcher`): at least 3 trains leave both at the same clock minute towards the same next station (or arrive from the same previous one), neighbours compared after the merges so far. Catches translated names ("Aachen, West Bf" / "Aix-la-Chapelle Ouest"). Repeated until a pass merges nothing.

   In both, the station with more connections becomes canonical.
7. **Foreign stops** (`ForeignStopSnapper`): a source's home country is where more than half of its located stations lie. A stop outside its source's home country is merged into the nearest station within 1 km of a source at home in the stop's country, which stays canonical. Every map is remapped. A canonical station can itself be merged later (A→B, then B→C); `mergeStations` first resolves such chains so every alias maps straight to the last station. The alias's translated names move to the canonical station.
8. `GtfsCacheStore` saves stations, translated names, services and trips (`CachedTrip`) to the GTFS cache file (format version 8). On load, `TransitGraph.restore` rebuilds `neighbors`, `connections`, `tripStops`, `tripInfos`, `tripServiceDays` and `stopTrips` from the trips.

`StationSearchIndex.rebuild` then indexes the variants of each station's name and translated names. `TransitGraph` builds lazy indexes on first access: `timetables` (trip → `TripTimetable`), `departuresByStop` (stop → departures by time) and `connectionByTrip` (edge → tripId → Connection).

## Reachability algorithm (`ReachabilityCache`, `ReachabilitySearch`)

`ReachabilityCache.getOrCompute` is the central dispatcher. It is keyed by `SearchKey(start, maxTransfers, minTransfer, maxTransfer)`:
1. **Raw cache hit** → return the stored result immediately.
2. **Cut down**: if a result with more transfers (same start and window) is stored, keep only its paths with ≤ N transfers. A round never depends on later rounds, so this equals a search with N.
3. **Search** (`ReachabilitySearch.search`), stored afterwards.

The search has no start time: every trip leaving the start station can begin a journey, and duration counts from its departure. It runs one round per transfer count (like RAPTOR):
- Round 0 rides every trip from the start station. Round k boards trips from the arrivals of round k−1. Riding a trip forward records each stop as an arrival.
- **Pruning is per trip**: each trip keeps the shortest duration per stop (`bestDurationsByTrip`). A ride stops as soon as it cannot beat that value, because an earlier ride got there at the same time with no more transfers and stays ahead for the rest of the trip. Arrival time is part of the state, so a slower but earlier arrival that catches a better train is kept (B2).
- **Transfers**: per station and round, departures are swept in time order against the arrivals sorted by time (`TransferWindow`). Each departure is boarded only from the arrival that catches it within the min/max wait window with the shortest journey so far. Waits past midnight use a second window with the arrivals moved a day back.
- **Service days (B25)**: there is no travel date, but a transfer needs the two trains to run on a common day (for a wait past midnight: the departing train the day after). If the window's best arrival fails this, the arrivals in the wait range are scanned for the best one that passes. Only consecutive trains are checked, not the whole journey, and round 0 boards every trip regardless of its days. Feeds whose validity periods don't overlap (B24) therefore don't connect at transfers.
- **Result**: per station, a list of paths by ascending transfers. During the search, each station keeps per transfer count the fastest ride and every ride at most `MAX_SLOWER_MINUTES` (30) slower, all faster than every ride with fewer transfers. Rides are kept as `previous` pointers and turned into `JourneyPath`s at the end, where `RegularJourneyChooser` picks one per transfer count: the fastest that runs on at least half as many days as the most frequent candidate (`JourneyRunningDays.withRide`, worked out once per ride because candidates share their earlier rides; no calendar data counts as often enough). Only the chosen ride becomes a `JourneyPath`. Without a start time, every departure of the day with a similar duration is a candidate (tens per station on the dev data), so the check per arriving ride compares only against the fastest duration per transfer count (O(1)). So a slightly faster train that runs on few days doesn't hide the regular one (B26). A chosen path that is not faster than the one with fewer transfers is left out. Only the shown journey changes: a transfer still continues from the fastest arrival (the `TransferWindow` sweep), so a later train is only reached via the faster feeder.
- The response picks per station the path with the fewest transfers that fits `maxJourneyTimeHours` (B4). Without a limit, that is the first path.

Overnight wrap: a time difference below −60 min gets +24 h added.

`JourneyLegBuilder` merges consecutive connections into one TRAIN leg (same tripId, or same route name and continuous in time) and inserts TRANSFER legs between them. A TRAIN leg's train number, headsign and operator come from its first trip.

`JourneyRunningDays` intersects the days of every ride on the path, counted from the first train's service day: a ride boarded at an earlier clock time than the previous one arrived is shifted by a day (the search's overnight transfer). A ride's days are the union of its trip's and those of every trip leaving the boarding station and reaching the alighting station at the same times, because feeds publish one train as several trips with different days and the search keeps only one. The summary is the weekdays on which the journey runs on at least half of their dates ("Runs daily", "Runs Mon–Fri", "Runs Sat, Sun"); with fewer than 4 days or no such weekday it is "Irregular: N days in the timetable". No dates are shown. No common day sets `hasCommonDay` to false, which the popup shows as a warning (the B25 check only covers consecutive trains).

**Caching layers:**
- `ReachabilityCache` (Caffeine, max `cache.raw-max-entries` (30) results) holds raw results (station → paths). A stored result also answers every lower transfer count.
- Caffeine `"reachability"` cache holds full `ReachabilityResponse`s from `POST /api/reachability` (max 10k entries, 60 min TTL).
- **Predictive warming**: after a streaming request with N < `predictive-max-transfers` (5), `ReachabilityWarmer` searches that maximum with the same transfer window on a background thread, into the raw cache only (the stream never reads Caffeine). Every count from N+1 up is then cut down from it.

**Streaming** (`calculateReachabilityStreaming`): the search runs to completion first (paths improve during the search, so nothing is sent early). The result is then sent as SSE `stations` events in batches of 30, followed by a `done` event (`{total, calculationTimeMs}`), or an `error` event on failure.

## REST API

**Public (`/api`)**, CORS `*`:
| Method | Path | Notes |
|---|---|---|
| GET | `/reachability/stream?stationId=&maxTransfers=&minTransferMinutes=0&maxTransferMinutes=180&maxJourneyHours=` | SSE; used by the frontend |
| POST | `/reachability` | JSON `ReachabilityRequest` → `ReachabilityResponse` (Caffeine-cached) |
| GET | `/stations/search?q=&limit=50` | Case- and accent-insensitive substring match on any variant of the station name or of its `translations.txt` names (`StationNameVariants`; names are still returned in the local language); only stations with edges, sorted by degree. Candidates come from the rarest trigram of the query (`StationSearchIndex`); queries under 3 characters scan in degree order and stop at `limit`. Each result: id, name, latitude, longitude, country |
| GET | `/stations/{id}` | Single station |
| GET | `/stats` | stations / graphNodes / totalEdges |
| GET | `/health` | UP/DOWN depending on whether data is loaded |

**Admin (`/api/admin`)**: every call needs the header `X-Admin-Token: <railreach.admin.token>` (see `AdminTokenInterceptor`). `scripts/api.sh` sends `RAILREACH_ADMIN_TOKEN` (default `dev`). In the browser, the "Dev login" at the bottom of the side panel (`js/dev-auth.js`) checks the token against `/debug/status`, keeps it in `localStorage` and only then starts the debug polling and shows the "Admin panel" button. That opens a `<dialog>` (`js/admin-panel.js`) with two tabs. "Tools" has buttons for the stats, debug-trigger, reload and cache-delete endpoints below; it prints the raw JSON response and asks for confirmation before reload and delete. "Feeds" (`js/feeds.js`) widens the panel to nearly the whole window and lists every zip from `/feeds`: last service day (first day as a tooltip), days left, state, size, download date, source host, and a download button for feeds in `gtfs-feeds.txt` ("manual" otherwise). "Download outdated" and "Download all" call `/feeds/{source}/download` for one feed after another, so each row shows its progress and the result of its last download. During a reload it shows a progress bar with phase, feeds, % parsed, elapsed time, ETA and graph size.
| Method | Path | Notes |
|---|---|---|
| GET | `/cache-stats` | Caffeine stats, warming jobs, rawPathCache keys |
| POST | `/reload-gtfs` | Full reparse of the zips (minutes), rewrite of the cache, clears the raw and response reachability caches |
| DELETE | `/gtfs-cache` | Deletes the cache file |
| GET | `/gtfs-stats` | Cache file and data counts |
| GET | `/feeds` | `FeedStatus` of every zip in the GTFS directory, sorted by state (expired, not yet valid, no calendar, unreadable, expiring, OK), then by days left, then by file name |
| POST | `/feeds/{source}/download` | Downloads a new version of the feed and swaps it in (`FeedDownloadResult`); 404 if not in `gtfs-feeds.txt`, 409 when busy. The graph uses it only after `reload-gtfs` |
| POST | `/feeds/download-outdated` | The same for every expired or expiring feed with a URL, one after the other (`scripts/api.sh feeds-download-outdated`; the admin panel downloads feed by feed instead) |
| GET | `/gtfs-load-status` | `GtfsLoadStatus` of the running or last load from the zips; the admin panel polls it every second during a reload |
| POST / DELETE | `/debug/trigger` | Toggle the frontend overlay that shows all stations |
| GET | `/debug/status` | Polled every 2 s by a browser logged in as dev; also used to check a dev login |
| GET | `/debug/stations` | All connected stations with edge counts |

## Configuration (`application.yml`, prefix `railreach.`)

- `gtfs.directory` (`./data/gtfs`, env `GTFS_DIRECTORY`), `gtfs.auto-load-from-files` (false), `gtfs.cache-file`
- `feeds.expiring-days` (30): feeds that end within this many days are `EXPIRING`; `feeds.check-interval` (`PT24H`): how often the expired/expiring feeds are logged
- `algorithm.max-transfer-time-minutes` (180), `algorithm.cache-size` (10000), `algorithm.cache-ttl-minutes` (60)
- `cache.predictive-enabled` (true), `cache.predictive-max-transfers` (5), `cache.raw-max-entries` (30)
- `admin.token` (empty, env `RAILREACH_ADMIN_TOKEN`): shared secret for the admin API; empty disables it

`application-dev.yml` overrides the GTFS directory and cache file, sets `auto-load-from-files: true`, turns predictive warming off and defaults `admin.token` to `dev`.

## Known issues and TODOs

See `docs/TODO.md`.
