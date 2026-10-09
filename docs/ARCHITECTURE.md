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
  - `data/gtfs-cache.json` is ~650 MB (3.6 GB before the trip-based format, S9); it loads in ~18 s.
  - Server size and memory measurements: `docs/HOSTING.md`.
- **Dev data:** `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
  - Austria + Hungary + Slovakia from `data/gtfs-dev/`, cache in `data/gtfs-dev-cache.json`.
- **Fresh checkout:** `backend/data/` is not in git, so there are no zips. Start the app (it starts empty), open the admin panel → Feeds, click "Download outdated" (it includes the `MISSING` feeds), then Tools → reload. Or with `scripts/api.sh`: `feeds-download-outdated`, then `reload-gtfs`. Which feeds are expected comes from `feeds.sources` (dev: austria, hungary, slovakia; default: every feed in `gtfs-feeds.txt`). `hungary` downloads only with MÁV credentials in `feeds.auth.hungary` (see Configuration).

- **Search oracle (S12):** `SearchSnapshot` dumps every path a search returns, one sorted line each: station, transfers, minutes, the path's stations, each ride as `tripId from departureSeconds to arrivalSeconds`, the exact running days as epoch-day ranges and their label. One section per start and transfer limit 0–5, with today pinned, because it decides the days (B31). Two runs must give the same bytes.
  - gtfs-mini: `SearchSnapshotTest` compares a search from every station of each feed combination with `src/test/resources/search-snapshots/`. After an intended change, rewrite them with `./mvnw -q test -Dtest=SearchSnapshotTest -Dsnapshot.update=true` and explain every changed line.
  - Dev and full data: the starts of one set in `docs/bench-stations.txt`, written to the gitignored `data/snapshots/` (gzipped if the name ends in `.gz`; a full-data dump is several GB as text): `./mvnw spring-boot:run -Dspring-boot.run.main-class=com.railreach.service.SearchSnapshot -Dspring-boot.run.jvmArguments=-Xmx3g "-Dspring-boot.run.arguments=data/gtfs-dev-cache.json dev 2026-10-09 data/snapshots/dev-2026-10-09.txt.gz"`. For full data use `data/gtfs-cache.json full` and `-Xmx12g` (about 9 min; 2.7 M lines, 136 MB gzipped). Compare two dumps with `cmp`, and list the differing lines with `diff <(gzip -dc a.txt.gz) <(gzip -dc b.txt.gz)`.

**Startup flow:**
1. `GtfsDataService` loads the GTFS cache file if present. Otherwise it parses the zips only if `auto-load-from-files` is true (true for dev, false for default) and the GTFS directory has a zip; with none (fresh checkout) it logs a WARN and starts empty. With no data, use `POST /api/admin/reload-gtfs`.

## Directory layout

```
RailReach/
├── CLAUDE.md, docs/ (ARCHITECTURE, TODO, decisions, bench-stations.txt), scripts/api.sh
├── frontend/index.html               # markup; served at / by Spring (static-locations: file:../frontend/)
├── frontend/css/app.css              # all styles and the map palette (CSS variables)
├── frontend/js/                      # ES modules: main (entry), api, map (only MapLibre user), search, query-form,
│                                     #   reachability (SSE; fetches a station's journey on click), coverage (Turf), station-details (clicked station's journey, shown over the side panel), journey (its lines on the map), debug, dev-auth (dev login), admin-panel (admin
│                                     #   endpoints as buttons), feeds (Feeds tab of the admin panel), sidebar (collapse toggle), icons (vendored Lucide SVGs;
│                                     #   `<span data-icon>` in the markup), util, config
├── frontend/backend_test.html        # small manual API test page
└── backend/
    ├── pom.xml
    ├── src/main/java/com/railreach/{controller,service,dto,model,config}/
    ├── src/main/resources/application.yml (+ application-dev.yml), borders/europe.geojson (country borders),
    │                                          #   station-aliases.txt, station-codes.txt, train-categories.txt, train-category-columns.txt,
    │                                          #   gtfs-feeds.txt (download URLs per feed), feed-roots.pem (extra TLS roots)
    ├── config/application.yml                 # GITIGNORED local secrets (feed credentials), optional
    ├── src/test/java/com/railreach/           # service/ (GtfsDataService, Reachability*, GtfsTestFixtures), util/, config/ tests
    ├── src/test/resources/gtfs-mini/          # hand-written fixture feeds (see its README)
    ├── src/test/resources/search-snapshots/   # search oracle dumps of gtfs-mini, one per feed combination
    └── data/                                  # GITIGNORED
        ├── gtfs/            # gtfs_<country>.zip, previous/ (zips replaced by the update center), broken/, list.txt (old download list), maps
        ├── gtfs-dev/        # 3 small feeds for the dev profile
        ├── snapshots/       # search oracle dumps of dev and full data
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
| `TransitGraph` | service | The merged network: stations, their translated names, edges, connections, trip indexes, `TripInfo`s and each trip's `ServiceDays`; `upcomingDays` and `todayEpochDay` (by its `Clock`), and its `SearchTimetables`, built on first use after a load. Everything that reads GTFS data injects this |
| `GtfsFeedLoader` | service | Parses the rail part of each zip into the graph, with per-feed stop dedup |
| `RailTripReader` | service | A feed's rail routes (name, operator from `agency.txt`) and rail trips (route, service ID, `TripInfo`) from routes/agency/trips.txt |
| `RareTrainFilter` | service | Drops a feed's trains that run on fewer than 8 days, all their trips together, before the graph is built (B26). A train is route + train number (if any, B32) + first stop + departure time (B33). `regularDepartures`: per stop of a trip, whether all trips leaving there at that time run on 8 days or more; the search doesn't ride a trip away from a stop where they don't (B33) |
| `ServiceCalendarReader` | service | A feed's services and the days they run, from `calendar.txt` (weekly pattern in a date range) and `calendar_dates.txt` (days added or removed) |
| `ServiceDays` | service | The days one service runs, as a bitset over epoch days; `from(epochDay)` for the days from today on, `intersect(later, dayShift)` and `union` for a journey's shown days and the rare-train rule, `dayCount`. `EVERY_DAY` for trips without a calendar entry |
| `WeekPeriodDays` | service | The search's coarse days: a `long` with one bit per weekday of each of 9 six-week periods from the Monday of today's week (about a year), set if the service runs on such a date from today on; `movedForward` for a wait past midnight. `OfDay` caches the masks per service for one day (`TransitGraph.weekPeriodDays`) |
| `TripInfo` | service | A trip's train number (`trip_short_name`, leading zeros of a numeric one stripped, prefixed with a `TrainCategories` category), headsign and operator |
| `TrainCategories` | service | Where a train's category comes from: per source the trips.txt column that holds it, from `train-category-columns.txt` (Netherlands `trip_long_name`, Poland `plk_category_code`); else, for bare train numbers, rules by source, agency, route type and optionally route short name, from `train-categories.txt` (e.g. DELFI's DB Fernverkehr: 101 → `ICE`, 102 → `IC`/`ECE`) |
| `GtfsLoadProgress` | service | Phase (PARSING → MERGING → SAVING → DONE/FAILED), feeds and zip bytes parsed of the running or last load from the zips; read by `/gtfs-load-status` |
| `TripRowBatcher` | service | Buffers stop_times rows per trip and hands whole trips to the loader in batches |
| `StopNameTranslations` | service | A feed's translated `stop_name`s from `translations.txt`, by stop ID or by untranslated name; reads the current and the pre-2019 (`trans_id`) format |
| `GtfsCsvReader` | service | Row-by-row reader for one CSV file inside a GTFS zip, at the top level or inside one top-level folder (ÖBB); strips a UTF-8 BOM from the header |
| `StationSearchIndex` | service | Station name search over the name variants of each station's name and translated names: trigram → station positions, in degree order; rebuilt after each load |
| `StationNameVariants` | service | Searchable forms of a station name: folded (lowercase, no accents), German umlaut spelling (`koeln`), and the local city name replaced by its translations from `station-aliases.txt` (`cologne`) |
| `StationCodes` | service | Cross-border rule: the UIC station code in a stop ID, by per-source formats from `station-codes.txt` (`france:StopArea:OCE87146217` and `switzerland:8714621` → `8714621`) |
| `StationNameMatcher` | service | Cross-border rule: name similarity and name containment of two nearby stations (see Data pipeline → cross-border dedup) |
| `StationDeduplicator` | service | Within-country and cross-border station merging after all feeds are loaded; runs the merge rules in order |
| `StationGrid` | service | Stations bucketed into square degree cells; `near` = the 3×3 cells around a station |
| `SharedTrainMatcher` | service | Cross-border rule: two stops are one station if ≥ 3 trains serve both at the same minute with the same neighbour station |
| `ForeignStopSnapper` | service | Cross-border rule: a feed's stop outside its home country is merged into the nearest station of that country's feed (≤ 1 km), or one of the same name (≤ 5 km) |
| `FeedUpdateCenter` | service | Update center for the GTFS zips: status of each zip (`FeedStatus`, validity and early-ending operators memoized per file and modification time; a big feed takes seconds, as trips.txt is read too) plus a `MISSING` status for each expected feed (`railreach.feeds.sources`) without a zip, a scheduled log check (`railreach.feeds.check-interval`, first run at startup) that WARNs about missing, expired and expiring feeds, disk space of the zips in use and in `previous/` (`FeedStorage`), and downloads of registered feeds. A download is refused (`BUSY`) during a GTFS load or another download |
| `FeedValidity` | service | First and last day any service of a feed runs, from `ServiceCalendarReader` over the whole feed (not only rail) |
| `EarlyEndingOperators` | service | Main rail operators of a feed (≥ 5 % of its rail trips, `MAIN_OPERATOR_MIN_TRIP_SHARE`) whose last train runs before the feed's last day, from routes.txt, trips.txt and the calendar (B34: the Swedish calendar ran to December, SJ's trains only to July) |
| `FeedRegistry` | service | Download URLs (with request headers) per source, from `gtfs-feeds.txt`; a source on several lines gets fallback URLs in line order. A header value may contain `${setting}` placeholders, kept unresolved here |
| `FeedTrust` | service | TLS trust for feed downloads only: the JDK's root certificates plus `feed-roots.pem` (roots the JDK lacks: Microsec e-Szigno Root CA 2009, used by MÁV) |
| `FeedDownloader` | service | Downloads a feed, trying its URLs in order until one passes the checks below (JDK `HttpClient` with `FeedTrust`'s TLS roots, unpacks `Content-Encoding: gzip`; fills in `${setting}` placeholders in headers from the Spring environment at download time, and rejects the URL if a setting is not configured) to `gtfs_<source>.zip.part` (creating the GTFS directory if needed), checks it (a zip with stops/stop_times/trips/routes.txt, at least one rail route, service days in the calendar, at least half the size of the current zip, running today, not ending before the current zip), moves the old zip to `previous/` and moves the new one in. If every URL fails or is rejected, the zip is unchanged and the result lists each URL's reason |
| `CountryLocator` | service | Point → ISO country code, from `borders/europe.geojson` (Natural Earth 1:10m, Europe) |
| `GtfsCacheStore` | service | Reads/writes the graph as the JSON cache file (`GtfsCacheData`: format version, stations, translated names, services, trips); a file with another version is ignored |
| `CachedTrip` | service | One trip in the cache file: route name, `TripInfo`, service ID, stop IDs, per-leg dep/arr seconds. Rebuilds the trip's edges and connections on load. Connections matching no leg (B19) are left out |
| `ReachabilityService` | service | Thin orchestrator: request defaults, POST response (`@Cacheable`), SSE streaming, triggers warming |
| `ReachabilitySearch` | service | Round-based search, one round per transfer; best path per station and transfer count, chosen at the end of each round and handed to a listener |
| `TripProgress` | service | Per stop of one trip during a search: the shortest duration that reached it, the days of that ride and of every ride recorded there (the B33 pruning) |
| `Ride` | service | One ride of a search: trip, board and alight stop, transfers, duration, its journey's days (`WeekPeriodDays`) and the ride before its transfer |
| `StationCandidates` | service | A station's candidate rides per transfer count during a search (the fastest and those at most `MAX_SLOWER_MINUTES` slower) |
| `SearchCounters` | service | What one search did, for its timing log |
| `TripTimetable` | service | One trip as arrays for the search: its index, stops and their station indexes, service days, each leg's connection, leg times, minute offsets; `Departure` = trip leaving a stop |
| `SearchTimetables` | service | Every trip as a `TripTimetable` (numbered in trip ID order; trips with the same stations and leg times are merged into the first one, running on all their days), every stop's departures by time (equal times in trip order) and per trip which departures may be ridden (`RareTrainFilter.regularDepartures`), built in one pass; stations numbered for the search; the trips' `WeekPeriodDays`, worked out once per day |
| `TransferWindow` | service | Sliding window over a station's arrivals: the arrival that boards a departure with the shortest journey, among those passing a filter (the day check); scans the wait range when the window's best fails it |
| `ReachabilityCache` | service | Raw search results per `SearchKey`; get-or-compute (stored, cut down from a stored higher transfer count, or searched, optionally passing each round's new paths on); Caffeine eviction and stats. A stored result keeps the days it was searched with, so it can include a day that has passed since (B31) |
| `ReachabilityWarmer` | service | `@Async` predictive warming of the maximum transfer count (own bean so the proxy applies) |
| `JourneyLegBuilder` | service | Search path → `ReachableStation` with TRAIN/TRANSFER legs |
| `JourneyRunningDays` | service | Days on which every train of a returned journey runs, and their summary ("Runs Mon–Fri", "Irregular: 2 days in the timetable") |
| `RegularJourneyChooser` | service | Picks a station's journey among the search's candidates with the same transfer count: the fastest that runs on at least half as many days as the most frequent one (B26) |
| `SearchSnapshot` | service | Search oracle: every path of a search as one sorted text line, for each start and transfer limit 0–5; `main` dumps a set of `docs/bench-stations.txt` from a cache file |
| `CacheConfig` | config | Caffeine `CacheManager` ("reachability" cache), `@EnableAsync` |
| `AdminTokenInterceptor` | config | Guards `/api/admin/**`: `X-Admin-Token` must equal `railreach.admin.token` (constant-time); 401 if wrong or missing, 403 for every call when no token is configured; CORS preflights pass |
| `WebConfig` | config | Registers `AdminTokenInterceptor` on `/api/admin/**` (same path matching as the controllers) |
| `Station` | model | id, name, latitude, longitude, parentStation, country (ISO code from the coordinates, null if outside every border) |
| `Connection` | model | One edge of one trip: tripId, routeName, from/to, dep/arr times as **int seconds since midnight** (string getters/setters for JSON) |
| `JourneyPath` | model | Internal search result: stationIds, connections, transfers, totalDurationMinutes |
| `ReachabilityRequest` | dto | startStationId, maxTransfers, maxJourneyTimeHours, min/maxTransferTimeMinutes |
| `ReachabilityResponse` | dto | Start info, count, calcTime, list of `ReachableStation` |
| `ReachableStation` | dto | Station (incl. country) + transfers, duration, path ids/names, `legs`, `runningDays` (summary, null without calendar data), `hasCommonDay` (false if the trains share no date: the search's day check is coarse), `roundTripStation` (a station the journey passes twice, else null) |
| `ReachableStationSummary` | dto | What the stream sends per station: id, name, country, coordinates, transfers, duration; the journey is fetched on click |
| `GtfsLoadStatus` | dto | `GtfsLoadProgress` snapshot plus live station/connection counts; ETA extrapolated from zip bytes parsed |
| `JourneyLeg` | dto | `TRAIN` or `TRANSFER` leg with from/to station names, stop IDs and countries, times and duration; TRAIN legs also carry route name, train number, headsign and operator |
| `FeedStatus` | dto | One zip: source, file name, size, download time, first/last service date, days left, `earlyEndingOperators` (`OperatorEnd`: operator, last service date; earliest first), state (`MISSING` = expected but no zip yet, `OK`, `EXPIRING`, `EXPIRED`, `NOT_YET_VALID`, `NO_CALENDAR`, `UNREADABLE`; `EXPIRING` also when the first early-ending operator stops within `feeds.expiring-days` or already has), download URL (the first one in `gtfs-feeds.txt`; null = updated by hand) |
| `FeedDownloadResult` | dto | Outcome (`REPLACED`, `REJECTED`, `BUSY`), message, `FeedStatus` before and after |
| `FeedStorage` | dto | Count and total bytes of the zips in the GTFS directory (in use) and of the replaced ones in `previous/` |
| `GtfsTime` | util | Parse/format GTFS times, minute diffs with overnight wrap, clock minute of a time, duration text |
| `StopIds` | util | Canonical `<source>:<rawId>` stop IDs |
| `FeedFiles` | util | Feed zip names: `gtfs_<source>.zip` ↔ source |
| `ResourceLines` | util | Lines of a classpath `.txt` config file, or of a file, without blanks and `#` comments |
| `Coordinates` | util | Degree-box proximity check, distance in metres |

## Data pipeline (`GtfsDataService.loadFromFiles`)

`GtfsFeedLoader`, for each `data/gtfs/*.zip` (sorted). The source name comes from the filename, e.g. `gtfs_germany.zip` → `germany`:
1. **routes.txt / agency.txt** (`RailTripReader`): keep only rail route types `2`, `100–106` and `109` (S-Bahn), each with its operator (`agency_name`; a route without `agency_id` gets the feed's only agency).
2. **stops.txt / trips.txt / calendar**: load stops and the rail trips: route, service ID and `TripInfo` (train number, headsign, operator). `ServiceCalendarReader` reads the days of each service; the services of rail trips go to the graph as `<source>:<serviceId>`. A trip whose service is in neither calendar file (or whose feed has neither) runs every day, with a WARN count. `RareTrainFilter` then drops every train that runs on fewer than 8 days of the feed, past days included: one-off specials and timetable-change leftovers (B26: NJ 479 ran 3 days, the regular NJ 469 is 15 min slower from Aachen). A train is all trips of one route with the same train number that leave the same first stop (canonical, so platforms match) at the same time, because feeds split one train into several trips with different days. Trips without a train number (all of norway, france, hungary, …) are grouped the same way (B32), and the departure keeps a rare timing variant of a regular train apart from it (B33: ICE 104 from Köln at 19:17 on 6 days). For this the filter reads stop_times.txt once more beforehand, keeping only each trip's first row. A train with a trip that has no calendar entry is kept, and so is a trip without stop_times. A trip with a value in its feed's category column (`train-category-columns.txt`) gets that category in front of its train number: `2835` → `Intercity 2835`, `1900/1 Zosia` → `IC 1900/1 Zosia`. Otherwise a numeric train number on a route that matches a `train-categories.txt` rule gets the rule's category: `002310` → `ICE 2310`.
3. **Per-feed stop dedup**: a stop whose `parent_station` is in the feed (with coordinates) is merged into that parent, at any distance. The other stops are merged when they have the same name and lie within ~0.0015°. IDs are canonicalised to `<source>:<id>`, with `_ARR`/`_DEP`/`ARRIVAL_` style suffixes and prefixes stripped.
4. **stop_times.txt**: streamed and buffered per trip (`TripRowBatcher`), rail trips only, and handed to the graph in batches of `railreach.gtfs.trip-batch-size` (100k) trips. A batch is only cut where a new trip starts, so trips stay whole in files grouped by `trip_id`. If a trip shows up again after another one started, the rest of the file is read in one batch, and rows of a trip already built are dropped with a WARN. Each trip's consecutive stop pairs produce:
   - `neighbors`: stopId → neighbour stopIds
   - `connections`: `"from->to"` → list of `Connection`
   - `tripStops`: tripId → ordered stop list; `tripInfos`: tripId → `TripInfo`; `tripServiceDays`: tripId → its service's shared `ServiceDays`; `stopTrips`: stopId → tripIds

   Then `translations.txt` (optional, `StopNameTranslations`): each stop's translated `stop_name`s go to its canonical station in `translatedNames` (stopId → names), minus the station's own name, for stations that a rail trip added. Only search uses them; the API returns the original name.

A feed that adds no connections at all (e.g. unreadable headers) is logged as a `!!!` WARN.

After all feeds are loaded (`StationDeduplicator`, then `TransitGraph.mergeStations`), every station first gets its `country` from `CountryLocator`; a merge keeps the canonical station's country.
5. **Within-country dedup**: same exact name, same source, within ~200 m.
   **By station code** (`StationCodes`, B30): stations of different sources with the same UIC code (from the stop ID, formats in `station-codes.txt`) merge when at most 5 km apart (`SAME_CODE_MAX_DISTANCE_METERS`), the better connected one canonical. Catches stations where one feed's coordinates are 1–4 km off (the Swiss feed's French stations, Trenord vs Trenitalia, Austria vs Italy). The limit keeps codes that repeat far away apart (the Austrian numbers repeat per state).
6. **Cross-border dedup**, on pairs of stations of different sources within ~500 m (found via a ~1 km `StationGrid`), in two passes:
   - **by name** (`StationNameMatcher`): word-set Jaccard similarity ≥ 0.4; or, within ~100 m, every word of one name is in the other ("Split" / "Split željeznički kolodvor"). Words are compared folded (`StationNameVariants.fold`), Cyrillic transliterated (Bulgarian system), without words for "station" and link words (stazione, kolodvor, di, …), with "C.LE" → centrale, "Hbf"/"…bf" → (haupt)bahnhof, airport words as one, and oe/ae/ue as o/a/u ("Koebenhavn"); two words also match one edit apart from 5 letters on and two edits apart from 8, if they start with the same letter. The best score over the names' city translations (`StationNameVariants.variants`: Padua / Padova) counts. The ~100 m limit keeps nearby separate stations apart (Cotral's "Viterbo" / "Viterbo Porta Fiorentina", 126 m);
   - **by shared trains** (`SharedTrainMatcher`): at least 3 trains leave both at the same clock minute towards the same next station (or arrive from the same previous one), neighbours compared after the merges so far. Catches translated names ("Aachen, West Bf" / "Aix-la-Chapelle Ouest"). Repeated until a pass merges nothing.

   In both, the station with more connections becomes canonical.
7. **Foreign stops** (`ForeignStopSnapper`): a source's home country is where more than half of its located stations lie. A stop outside its source's home country is merged into the nearest station within 1 km of a source at home in the stop's country, which stays canonical. With none that close, it is merged into the nearest one within 5 km whose name has the same words, ignoring a country in brackets at the end (`StationNameMatcher.sameName`: "Apach(fr)" / "Apach"; B30), as some feeds place their stops abroad 1–4 km off. Every map is remapped. A canonical station can itself be merged later (A→B, then B→C); `mergeStations` first resolves such chains so every alias maps straight to the last station. The alias's translated names move to the canonical station.
8. `GtfsCacheStore` saves stations, translated names, services and trips (`CachedTrip`) to the GTFS cache file (format version 8). On load, `TransitGraph.restore` rebuilds `neighbors`, `connections`, `tripStops`, `tripInfos`, `tripServiceDays` and `stopTrips` from the trips.

`StationSearchIndex.rebuild` then indexes the variants of each station's name and translated names. `GtfsDataService` then has `TransitGraph` build its `SearchTimetables` in one pass (about 18 s on the full data, most of it the rare-departure check per trip and stop; clearing or merging the graph drops them, and the next search rebuilds them), so the first search is as fast as later ones. `connectionByTrip` (edge → tripId → Connection) is still built lazily, but the search no longer uses it.

## Reachability algorithm (`ReachabilityCache`, `ReachabilitySearch`)

`ReachabilityCache.getOrCompute` is the central dispatcher. It is keyed by `SearchKey(start, maxTransfers, minTransfer, maxTransfer)`:
1. **Raw cache hit** → return the stored result immediately.
2. **Cut down**: if a result with more transfers (same start and window) is stored, keep only its paths with ≤ N transfers. A round never depends on later rounds, so this equals a search with N.
3. **Search** (`ReachabilitySearch.search`), stored afterwards.

The search has no start time: every trip leaving the start station can begin a journey, and duration counts from its departure. It runs one round per transfer count (like RAPTOR):
- Round 0 rides every trip from the start station. Round k boards trips from the arrivals of round k−1. Riding a trip forward records each stop as an arrival.
- **Pruning is per trip**: each trip keeps per stop the shortest duration, the days (`WeekPeriodDays`) of that ride and the days of every ride recorded there (`TripProgress`). A ride stops as soon as it cannot beat the duration and all its days are already reached, because earlier rides got there at the same time with no more transfers and stay ahead for the rest of the trip. On some of those days the shown journey can be a bit slower than the fastest possible. Arrival time is part of the state, so a slower but earlier arrival that catches a better train is kept (B2), and so is a slower ride on days the faster one doesn't run (B33).
- **Transfers**: per station and round, departures are swept in time order against the arrivals sorted by time (`TransferWindow`). Each departure is boarded from the arrival that catches it within the min/max wait window with the shortest journey so far, then from slower ones only for days of the departing trip that no faster one covers. Waits past midnight use a second window with the arrivals moved a day back. Stations choose what to board in parallel on the common fork-join pool, since that only reads earlier rounds; riding the boarded trips writes the per-trip state, so it runs afterwards in station index order (arrivals are grouped by station index, each station's in the order they came), and the result is the same as on one thread. Choosing each station's path at the end of a round is parallel too; the running days per ride are shared in a concurrent map.
- **Service days (B25, B31, B33)**: there is no travel date, but a journey's days start with its first train's days from today on, so a train whose days are all past, like every train of an expired feed, is never boarded. A transfer needs a day on which the whole journey so far runs and the departing train runs too (for a wait past midnight: the day after). Days are compared as `WeekPeriodDays` (weekday × 6-week period), not dates: exact dates made the full-data search about 4× slower (see decisions.md). So trains running on the same weekdays of the same period but in different weeks can connect; the shown journey then has no common date and the station details say so (`hasCommonDay`). Each ride carries its journey's days, counted on its own trip's service day. Checking only consecutive trains let a chain of six trains pass whose first three ran only in autumn and last two only in spring, bridged by an all-year regional train. If the window's best arrival fails the check, the arrivals in the wait range are scanned for the best one that passes. Feeds whose validity periods don't overlap therefore don't connect at transfers. A ride also ends at a stop where its trip leaves at a time that, over all trips leaving there then, runs on fewer than 8 days (`RareTrainFilter.regularDepartures`): a variant that matches its regular train at the first stop is not ridden past the point where it deviates. Trips with the same stations and times (one train published as several trips with different days, or the same train in two feeds: 390k of 840k trips on the full data) are one search trip that runs on all their days; under the whole-journey check that is exact.
- **Result**: per station, a list of paths by ascending transfers. During the search, each station keeps per transfer count the fastest ride and every ride at most `MAX_SLOWER_MINUTES` (30) slower, all faster than every ride with fewer transfers. Rides are kept as `previous` pointers. When round k ends, every station it reached gets its path with k transfers: `RegularJourneyChooser` picks one among the candidates with k transfers (later rounds only add rides with more transfers, so these are final): the fastest that runs on at least half as many days as the most frequent candidate (`JourneyRunningDays.withRide`, worked out once per ride because candidates share their earlier rides; no calendar data counts as often enough). Only the chosen ride becomes a `JourneyPath`. Without a start time, every departure of the day with a similar duration is a candidate (tens per station on the dev data), so the check per arriving ride compares only against the fastest duration per transfer count (O(1)). So a slightly faster train that runs on few days doesn't hide the regular one (B26). A chosen path that is not faster than the one with fewer transfers is left out. Only the shown journey changes: a transfer still continues from the fastest arrival (the `TransferWindow` sweep), so a later train is only reached via the faster feeder.
- The response picks per station the path with the fewest transfers that fits `maxJourneyTimeHours` (B4). Without a limit, that is the first path.

Overnight wrap: a time difference below −60 min gets +24 h added.

**Timing log:** each search logs one INFO line (`Search stats`) with the stop arrivals, trips and stations, the time of the whole search and of choosing paths at the end of each round, the departures scanned and how often `TransferWindow` had to scan the wait range because its best arrival failed the day check (with the arrivals those scans checked). The same per round is logged at DEBUG. A stream also logs its total time and when it sent the first stations.

`JourneyLegBuilder` merges consecutive connections into one TRAIN leg (same tripId, or same route name and continuous in time) and inserts TRANSFER legs between them. A TRAIN leg's train number, headsign and operator come from its first trip. Its `fromStopId`/`toStopId` are the boarding and alighting stops as they appear in `path`, which lists every stop the trains call at; while a station's details are open, the frontend (`js/journey.js`) uses them to draw one straight-segment line per train through those stops, coloured by the train's index like the transfer levels, and hides all stations not on the path.

`JourneyRunningDays` intersects the days of every ride on the path, counted from the first train's service day and from today on (B31): a ride boarded at an earlier clock time than the previous one arrived is shifted by a day (the search's overnight transfer). A ride's days are the union of its trip's and those of every trip leaving the boarding station and reaching the alighting station at the same times, because feeds publish one train as several trips with different days and the search keeps only one. The summary is the weekdays on which the journey runs on at least half of their dates ("Runs daily", "Runs Mon–Fri", "Runs Sat, Sun"); with fewer than 4 days or no such weekday it is "Irregular: N days in the timetable". No dates are shown. The search compares days by weekday and period only, so a journey can have no common date; `hasCommonDay` is then false and the station details show a warning instead of the summary.

The wait limit applies to each transfer, so a journey can bridge a longer wait by riding away and back to the same station (F24); it arrives no earlier than waiting would. `JourneyLegBuilder` sets `roundTripStation` to the name of the station in `path` that occurs more than once and is visited first (for a ride out and back on one line, the station where the loop starts, not a stop on the way), and the station details show a warning naming it. Such journeys are not filtered out.

**Caching layers:**
- `ReachabilityCache` (Caffeine, max `cache.raw-max-entries` (30) results) holds raw results (station → paths). A stored result also answers every lower transfer count.
- Caffeine `"reachability"` cache holds full `ReachabilityResponse`s from `POST /api/reachability` (max 10k entries, 60 min TTL).
- **Predictive warming**: after a streaming request with N < `predictive-max-transfers` (5), `ReachabilityWarmer` searches that maximum with the same transfer window on a background thread, into the raw cache only (the stream never reads Caffeine). Every count from N+1 up is then cut down from it.

**Streaming** (`calculateReachabilityStreaming`): a search sends each round's stations as soon as the round ends; a stored result is sent at once. A station is sent once, with its first path within `maxJourneyHours`; paths come by ascending transfers, so that is the path the response picks too (B4), and a later round never changes a sent station. Stations go out as `ReachableStationSummary`s (no path, legs or running days: on the full data those made the stream about 100 MB) in SSE `stations` events in batches of 30, followed by a `done` event (`{total, calculationTimeMs}`), or an `error` event on failure.

## Search semantics contract

What every search engine must keep, so a rewrite (S17–S21) gives the same result as `SearchSnapshotTest`'s goldens. One rule, one test (`ReachabilityServiceTest` unless named otherwise):
- **Slower arrival kept (B2):** a slower arrival at a station is kept when it catches a train the fastest arrival misses. `slowerEarlierArrivalIsKeptWhenItEnablesAFasterTransfer`
- **Past midnight (B25):** a transfer whose wait crosses midnight boards a train running the day after a day of the journey so far. `transferPastMidnightBoardsATrainRunningTheNextDay`
- **From today (B31):** a journey's days start with its first train's days from today on, so a train whose days are all past is never boarded. `trainsWhoseDaysAreAllPastAreNotRidden`
- **Whole journey (B33):** a transfer needs a `WeekPeriodDays` day on which every train of the journey so far runs together with the next one, and a faster ride does not cut off a slower one on days it lacks. `journeyWhoseTrainsShareNoDayGivesWayToOneThatRuns`
- **Regular journey (B26):** a station shows, among its candidates with the same transfer count (the fastest and those at most 30 min slower), the fastest that runs on at least half as many exact days as the most frequent one. A train over 30 min slower is no candidate, so a much faster rare train beats it. `rareTrainMoreThanHalfAnHourFasterStillWins`
- **Merged trips:** trips with the same stations and leg times are one search trip that runs on the union of their days and keeps the smallest trip ID. `SearchTimetablesTest.tripsWithTheSameStationsAndTimesBecomeOneThatRunsOnAllTheirDays`
- **Twin trips:** a ride's exact days are its trip's days united with those of every trip leaving the boarding station at the same time and reaching the alighting station at the same time (`JourneyRunningDays.rideDays`). The test covers twins that are also merged; twins differing elsewhere on the trip have no test yet. `rideRunsOnTheDaysOfEveryTripWithTheSameTimes`
- **Rare departures:** a ride ends at a stop where its trip leaves at a time that, over all trips leaving there then, runs on fewer than 8 days. `rideEndsWhereItsTripLeavesAtATimeThatRunsOnFewDays`
- **Overnight window:** a wait past midnight lies within the min/max wait and is at most 24 h − 61 min (`GtfsTime.MAX_OVERNIGHT_GAP_SECONDS`), so a train leaving up to an hour before the arrival's clock time is not caught the next day. `trainLeavingLessThanAnHourBeforeTheArrivalIsNotCaughtTheNextDay` (the wrap itself: `transferAcrossMidnightWrapsToNextDay`)
- **Start excluded:** a ride through the start station records no arrival there, so no journey transfers at or ends at the start; its result is the one empty path. `ReachabilitySearchTest.startStationKeepsOnlyItsEmptyPathThoughATrainReturnsToIt`

**Processing order.** Ties are decided by who comes first, so the result depends on this order:
- **Numbering:** trips are walked in trip ID order (string order). Station indexes follow the first appearance of each stop on that walk. Merged trips get indexes in the order of their smallest trip ID.
- **Departures at a station:** by departure seconds, equal times in trip index order (a stable sort).
- **Round 0:** the start's departures in that order. Each trip is ridden stop by stop.
- **Round k:** the previous round's arrivals, minus those a faster ride of their own round overtook on all their days, grouped by station index ascending. Within a station they keep the order they arrived in, then are sorted stably by arrival seconds. Departures are matched in departure order. Each one boards first from the arrival with the shortest journey so far, then from slower ones for days no faster one covers. Overnight beats same-day only when it is strictly shorter. Boarded trips are then ridden station by station in that order.
- **Tie-breaks that depend on that order:**
  - `TripProgress` keeps the first ride's duration and days when a later one is equally fast.
  - `TransferWindow`'s queue prefers the later arrival when two give the same journey start. Its fallback scan prefers the earlier one.
  - `StationCandidates` keeps rides in the order they were offered. `RegularJourneyChooser` takes the first of equally fast candidates.

## REST API

**Public (`/api`)**, CORS `*`:
| Method | Path | Notes |
|---|---|---|
| GET | `/reachability/stream?stationId=&maxTransfers=&minTransferMinutes=0&maxTransferMinutes=180&maxJourneyHours=` | SSE; used by the frontend |
| GET | `/reachability/journey?stationId=&maxTransfers=&minTransferMinutes=0&maxTransferMinutes=180&maxJourneyHours=&targetId=` | One station's `ReachableStation` (path, legs, running days) for the stream's parameters, built from the stored search result (searched again if it was evicted); 404 if the target is not reachable within them. The frontend calls it when a station is clicked |
| POST | `/reachability` | JSON `ReachabilityRequest` → `ReachabilityResponse` (Caffeine-cached) |
| GET | `/stations/search?q=&limit=50` | Case- and accent-insensitive substring match on any variant of the station name or of its `translations.txt` names (`StationNameVariants`; names are still returned in the local language); only stations with edges, sorted by degree. Candidates come from the rarest trigram of the query (`StationSearchIndex`); queries under 3 characters scan in degree order and stop at `limit`. Each result: id, name, latitude, longitude, country |
| GET | `/stations/{id}` | Single station |
| GET | `/stats` | stations / graphNodes / totalEdges |
| GET | `/health` | UP/DOWN depending on whether data is loaded |

**Admin (`/api/admin`)**: every call needs the header `X-Admin-Token: <railreach.admin.token>` (see `AdminTokenInterceptor`). `scripts/api.sh` sends `RAILREACH_ADMIN_TOKEN` (default `dev`). In the browser, the "Dev login" at the bottom of the side panel (`js/dev-auth.js`) checks the token against `/debug/status`, keeps it in `localStorage` and only then starts the debug polling and shows the "Admin panel" button. That opens a `<dialog>` (`js/admin-panel.js`) with two tabs. Below the title, a label shows the disk space of the zips in use and of the replaced ones in `previous/` (`/feeds/storage`, refreshed when the panel opens and whenever the Feeds tab reloads its list). "Tools" has buttons for the stats, debug-trigger, reload and cache-delete endpoints below; it prints the raw JSON response and asks for confirmation before reload and delete. "Feeds" (`js/feeds.js`) widens the panel to nearly the whole window and lists every zip and missing feed from `/feeds`: last service day (first day as a tooltip), days left, state with the early-ending main operators below it, size, download date, source host, and a download button for feeds in `gtfs-feeds.txt` ("manual" otherwise). "Download outdated" (missing, expired and expiring feeds) and "Download all" call `/feeds/{source}/download` for one feed after another, so each row shows its progress and the result of its last download. During a reload it shows a progress bar with phase, feeds, % parsed, elapsed time, ETA and graph size.
| Method | Path | Notes |
|---|---|---|
| GET | `/cache-stats` | Caffeine stats, warming jobs, rawPathCache keys |
| POST | `/reload-gtfs` | Full reparse of the zips (minutes), rewrite of the cache, clears the raw and response reachability caches |
| DELETE | `/gtfs-cache` | Deletes the cache file |
| GET | `/gtfs-stats` | Cache file and data counts |
| GET | `/feeds` | `FeedStatus` of every zip in the GTFS directory and of every expected feed without one, sorted by state (missing, expired, not yet valid, no calendar, unreadable, expiring, OK), then by days left, then by file name |
| GET | `/feeds/storage` | `FeedStorage`: count and size of the zips in the GTFS directory and of the replaced zips in `previous/` |
| POST | `/feeds/{source}/download` | Downloads a new version of the feed and swaps it in (`FeedDownloadResult`); 404 if not in `gtfs-feeds.txt`, 409 when busy. The graph uses it only after `reload-gtfs` |
| POST | `/feeds/download-outdated` | The same for every missing, expired or expiring feed with a URL, one after the other (`scripts/api.sh feeds-download-outdated`; the admin panel downloads feed by feed instead) |
| GET | `/gtfs-load-status` | `GtfsLoadStatus` of the running or last load from the zips; the admin panel polls it every second during a reload |
| POST / DELETE | `/debug/trigger` | Toggle the frontend overlay that shows all stations |
| GET | `/debug/status` | Polled every 2 s by a browser logged in as dev; also used to check a dev login |
| GET | `/debug/stations` | All connected stations with edge counts |

## Configuration (`application.yml`, prefix `railreach.`)

- `gtfs.directory` (`./data/gtfs`, env `GTFS_DIRECTORY`), `gtfs.auto-load-from-files` (false), `gtfs.cache-file`
- `feeds.expiring-days` (30): feeds that end, or whose first early-ending main operator stops, within this many days are `EXPIRING`; `feeds.check-interval` (`PT24H`): how often the missing/expired/expiring feeds are logged; `feeds.sources` (empty = every feed in `gtfs-feeds.txt`): comma-separated feeds expected in the GTFS directory, listed as `MISSING` without a zip; `feeds.auth.<source>` (no default, never in git): credentials that `gtfs-feeds.txt` headers refer to, e.g. `feeds.auth.hungary` = base64 of `user:password` for MÁV's Basic auth. Set it in `backend/config/application.yml` (gitignored; Spring Boot reads `config/` in the working directory on top of the bundled file) or as env var `RAILREACH_FEEDS_AUTH_HUNGARY`
- `algorithm.max-transfer-time-minutes` (180), `algorithm.cache-size` (10000), `algorithm.cache-ttl-minutes` (60)
- `cache.predictive-enabled` (true), `cache.predictive-max-transfers` (5), `cache.raw-max-entries` (30)
- `admin.token` (empty, env `RAILREACH_ADMIN_TOKEN`): shared secret for the admin API; empty disables it

`application-dev.yml` overrides the GTFS directory and cache file, sets `auto-load-from-files: true` and `feeds.sources: austria,hungary,slovakia`, turns predictive warming off and defaults `admin.token` to `dev`.

## Known issues and TODOs

See `docs/TODO.md`.
