# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B19 · F7 · S9

## Bugs (data loading)
- [ ] **B18** (high priority) The loader keeps every stop, not only rail stops. `GtfsFeedLoader.registerStations` adds every `stops.txt` row with coordinates as a `Station`, but only rail trips create edges: full data has 761,952 stations and 26,416 graph nodes, so ~97 % have no edges. A count over the raw stops of all 25 feeds (1.44M) gives 79 % bus-only, 1.2 % tram/metro-only, 10 % unserved parents of non-rail stops, 3.5 % entrances/nodes (`location_type` 2–4, mostly Germany), 0.9 % unserved parents of rail stops (duplicates of their platforms, e.g. France 1:1) and only 3 % rail-served. They are never searchable but cost memory and cache size (5.4 GB live heap, 4 GB cache). Fix: register only stops used by rail trips (keep parents only if needed for merging), add a fixture test with a bus-only stop, a parent and an entrance, then rebuild the caches.
- [ ] **B1** UTF-8 BOM breaks header parsing, so Austria is silently dropped. `gtfs_austria.zip` files start with `EF BB BF`. `indexOf(headers, "route_id")` then returns -1, and the feed loads 0 routes, 0 stops and 0 trips (log: "Routes: 273 total, 259 rail routes selected" then "Loaded 0 rail routes, 0 stops"). It's the only affected feed today. Fix: strip a leading `﻿` from the header line in the CSV loaders, add a BOM fixture test, then rebuild the caches. Also make a feed that loads 0 stops a loud warning.
- [ ] **B17** Trips can be split across stop_times batches. `GtfsFeedLoader.loadStopTimes` turns buffered trips into edges every 100k trips. If a feed's `stop_times.txt` is not grouped by trip, one trip's rows can land in two batches: the second batch overwrites the trip's stop list with only its part (`TransitGraph.addTrip`), and the connection between the last stop of the first part and the first stop of the second is lost. Only affects feeds with >100k rail trips and unsorted rows. Fix direction: flush only trips that cannot get more rows (e.g. require grouped input and detect violations), or merge partial trips by stop_sequence; add a fixture test with interleaved rows and a small batch size.
- [ ] **S8** Feeds that failed to load: `data/gtfs/broken/` (finland, lithuania).

## Bugs (algorithm)
- [ ] **B2** BFS pruning ignores arrival time. `ReachabilitySearch` keeps one best path per station, ranked by (transfers, duration). An earlier-arriving but longer trip is pruned even when it enables a much better onward transfer. Repro: the disabled test `ReachabilityServiceTest.pruningShouldNotDiscardEarlierArrivalsThatEnableBetterTransfers` gets 250 min where 120 is possible. Probably the root cause of B3 and B4. Fix direction: keep a Pareto set per station (arrival time × transfers), or switch to a time-dependent algorithm (e.g. RAPTOR / CSA profile).
- [ ] **B3** Min transfer time filters out connections that should be kept (anything other than 5 min min / 180 max). Likely caused by B2.
- [ ] **B4** Turning the transfer wait time off makes many connections disappear. Likely caused by B2.
- [ ] **B5** Hub precompute always uses a 0–180 min transfer window, whatever the request asks for.
- [ ] **B6** Route-type filter is `2 || 100–106`, but the comment says 107/109 are included. Decide and align. Impact: 109 is suburban railway (S-Bahn), and 3,571 stops are served only by it (Germany 3,260, Finland 238, Denmark 73). S-Bahn trips through stations that are in the graph are dropped too, so a large part of the regional network is missing. Including it adds many short, frequent trips (memory, BFS time); measure after B18.

## Bugs (caching / infrastructure)
- [ ] **B8** The warming cache key hardcodes min-transfer `0`, so warming never matches requests with a min transfer time.
- [ ] **B9** The raw path store in `ReachabilityCache` is unbounded and not cleared on `reload-gtfs`.
- [ ] **B15** The cross-border dedup `MERGED` log compares station names with stop IDs (`s1.getName().equals(alias)` in `StationDeduplicator.tryCrossBorderMerge`). That is always false, so both the alias and the canonical stop are logged with s2's name. Log only; the merge itself is unaffected.
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
