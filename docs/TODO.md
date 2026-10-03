# TODO / Known issues

Keep this list current: add issues when found, and remove or tick items when done.

## Bugs (data loading)
- [ ] **UTF-8 BOM breaks header parsing, so Austria is silently dropped.** `gtfs_austria.zip` files start with `EF BB BF`. `indexOf(headers, "route_id")` then returns -1, and the feed loads 0 routes, 0 stops and 0 trips (log: "Routes: 273 total, 259 rail routes selected" then "Loaded 0 rail routes, 0 stops"). It's the only affected feed today. Fix: strip a leading `﻿` from the header line in the CSV loaders, add a BOM fixture test, then rebuild the caches. Also make a feed that loads 0 stops a loud warning.

## Bugs (algorithm)
- [ ] **BFS pruning ignores arrival time.** `runBFSInternal` keeps one best path per station, ranked by (transfers, duration). An earlier-arriving but longer trip is pruned even when it enables a much better onward transfer. Repro: the disabled test `ReachabilityServiceTest.pruningShouldNotDiscardEarlierArrivalsThatEnableBetterTransfers` gets 250 min where 120 is possible. Probably the root cause of "min transfer times filter out connections" and "turning off transfer wait makes connections disappear" below. Fix direction: keep a Pareto set per station (arrival time × transfers), or switch to a time-dependent algorithm (e.g. RAPTOR / CSA profile).
- [ ] Min transfer time filters out connections that should be kept (anything other than 5 min min / 180 max). Likely the pruning issue above.
- [ ] Turning the transfer wait time off makes many connections disappear.
- [ ] Hub precompute always uses a 0–180 min transfer window, whatever the request asks for.
- [ ] Route-type filter is `2 || 100–106`, but the comment says 107/109 are included. Decide and align.

## Bugs (caching / infrastructure)
- [ ] `@Async warmHigherTransferCounts` and `@Cacheable calculateReachability` are called from inside the same class, so Spring's proxy is bypassed. Warming runs synchronously on the SSE thread, and warmed results never reach Caffeine.
- [ ] The warming cache key hardcodes min-transfer `0`, so warming never matches requests with a min transfer time.
- [ ] `rawPathCache` is unbounded and not cleared on `reload-gtfs` (`clearRawCache()` is never called).
- [ ] The cross-border dedup log uses `{:.2f}`, which isn't valid SLF4J. Use `{}` with `String.format`.
- [ ] `searchStations` is a linear scan per request.

## Features / UX
- [ ] Rework station naming: map lat/long to OpenStreetMap station names and use that name for grouping as well.
- [ ] Weird station names in transfers, e.g. `germany:000008700014` or `belgien:gs:nmbssncb:8101073`. A raw ID is shown when the name lookup fails.
- [ ] Max journey time has to be entered repeatedly (frontend).
- [ ] Zoom level after calculation (frontend).
- [ ] `/api/admin/debug/stations?includeIsolated=true` is referenced in old notes but not implemented.

## Structure
- [ ] Split `GtfsDataService` (~1100 lines) into `GtfsFeedLoader`, `StationDeduplicator`, `TransitGraph` and `GtfsCacheStore`.
- [ ] Split `ReachabilityService` (~870 lines) into `ReachabilitySearch`, `JourneyLegBuilder`, `ReachabilityCache` and a thin orchestrator. This also fixes the self-invocation issue above.
- [ ] Remove dead code: `StopSequence`, `ReachabilityService.totalDuration/parseTime`, unused gtfs-lib dependency (only `Stop`/`StopTime` POJOs used).
- [ ] Feeds that failed to load: `data/gtfs/broken/` (finland, lithuania).
