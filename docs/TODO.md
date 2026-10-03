# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B17 · F7 · S9

## Bugs (data loading)
- [ ] **B1** UTF-8 BOM breaks header parsing, so Austria is silently dropped. `gtfs_austria.zip` files start with `EF BB BF`. `indexOf(headers, "route_id")` then returns -1, and the feed loads 0 routes, 0 stops and 0 trips (log: "Routes: 273 total, 259 rail routes selected" then "Loaded 0 rail routes, 0 stops"). It's the only affected feed today. Fix: strip a leading `﻿` from the header line in the CSV loaders, add a BOM fixture test, then rebuild the caches. Also make a feed that loads 0 stops a loud warning.

## Bugs (algorithm)
- [ ] **B2** BFS pruning ignores arrival time. `runBFSInternal` keeps one best path per station, ranked by (transfers, duration). An earlier-arriving but longer trip is pruned even when it enables a much better onward transfer. Repro: the disabled test `ReachabilityServiceTest.pruningShouldNotDiscardEarlierArrivalsThatEnableBetterTransfers` gets 250 min where 120 is possible. Probably the root cause of B3 and B4. Fix direction: keep a Pareto set per station (arrival time × transfers), or switch to a time-dependent algorithm (e.g. RAPTOR / CSA profile).
- [ ] **B3** Min transfer time filters out connections that should be kept (anything other than 5 min min / 180 max). Likely caused by B2.
- [ ] **B4** Turning the transfer wait time off makes many connections disappear. Likely caused by B2.
- [ ] **B5** Hub precompute always uses a 0–180 min transfer window, whatever the request asks for.
- [ ] **B6** Route-type filter is `2 || 100–106`, but the comment says 107/109 are included. Decide and align.

## Bugs (caching / infrastructure)
- [ ] **B7** `@Async warmHigherTransferCounts` and `@Cacheable calculateReachability` are called from inside the same class, so Spring's proxy is bypassed. Warming runs synchronously on the SSE thread, and warmed results never reach Caffeine.
- [ ] **B8** The warming cache key hardcodes min-transfer `0`, so warming never matches requests with a min transfer time.
- [ ] **B9** `rawPathCache` is unbounded and not cleared on `reload-gtfs` (`clearRawCache()` is never called).
- [ ] **B15** The cross-border dedup `MERGED` log compares station names with stop IDs (`s1.getName().equals(alias)` in `deduplicateCrossBorder`). That is always false, so both the alias and the canonical stop are logged with s2's name. Log only; the merge itself is unaffected.
- [ ] **B11** `searchStations` is a linear scan per request.

## Bugs (frontend)
- [ ] **B14** No status or error ever reaches the user: `showStatus` only logs to the console. Repro: stop the backend and click Calculate. The map clears, the counter stays at "…", and nothing says the stream failed.

## Features / UX
- [ ] **F1** Rework station naming: map lat/long to OpenStreetMap station names and use that name for grouping as well.
- [ ] **F2** Weird station names in transfers, e.g. `germany:000008700014` or `belgien:gs:nmbssncb:8101073`. A raw ID is shown when the name lookup fails.
- [ ] **F3** Max journey time has to be entered repeatedly (frontend).
- [ ] **F4** Zoom level after calculation (frontend).
- [ ] **F5** `/api/admin/debug/stations?includeIsolated=true` is referenced in old notes but not implemented.

## Structure
- [ ] **S1** Split `GtfsDataService` (~1100 lines) into `GtfsFeedLoader`, `StationDeduplicator`, `TransitGraph` and `GtfsCacheStore`.
- [ ] **S2** Split `ReachabilityService` (~870 lines) into `ReachabilitySearch`, `JourneyLegBuilder`, `ReachabilityCache` and a thin orchestrator. This also fixes B7.
- [ ] **S3** Remove dead code: `StopSequence`, `ReachabilityService.totalDuration/parseTime`, unused gtfs-lib dependency (only `Stop`/`StopTime` POJOs used).
- [ ] **S4** Consolidate duplicated GTFS time logic into `util/GtfsTime`: `Connection.formatTime/parseTime`, `GtfsDataService.formatTime/parseGtfsTime`, `ReachabilityService.parseTime/formatDuration/timeDiff/totalDuration` (see CODING_GUIDELINES §2).
- [ ] **S5** Extract stop-ID source parsing (`id.split(":")[0]`, 3×) into `util/StopIds`.
- [ ] **S6** Style cleanup when touching files: wildcard imports (7), inline fully-qualified names (`CacheConfig`, `ReachabilityService`), `QueueItem` → record, `double CELL_SIZE` local → constant, Paris/Köln debug-log special case in `deduplicateCrossBorder`, `@Value` without default for `max-transfer-time-minutes`.
- [ ] **S8** Feeds that failed to load: `data/gtfs/broken/` (finland, lithuania).
