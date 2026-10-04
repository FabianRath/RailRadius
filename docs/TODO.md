# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B22 · F7 · S12

## Bugs (data loading)
- [ ] **B19** Some connections match no leg of their trip's stop list. Full-data reload: `belgien:gt:nmbssncb:88____:L72::8015345:8841004:15:852:20251212` has a connection `netherlands:2993355->netherlands:2993124`, but its stop list goes `netherlands:2993355` → `germany:000008400307`, and `2993124` is not in it. The BFS walks stop lists via `getConnectionByTrip`, so it can never use such a connection (only `neighbors`/stats see it), and the trip may be cut short there. The GTFS cache (S9) drops these and logs a WARN with the count. Suspects: B17 (a split trip's stop list overwritten by its second part), or a chain of station merges (A→B, then B→C; `mergeConnections` resolves one hop). Diagnose by logging the trip's raw stop_times and the alias map entries for the involved stops.
- [ ] **S8** Feeds that failed to load: `data/gtfs/broken/` (finland, lithuania).

## Bugs (algorithm)
- [ ] **B2** BFS pruning ignores arrival time. `ReachabilitySearch` keeps one best path per station, ranked by (transfers, duration). An earlier-arriving but longer trip is pruned even when it enables a much better onward transfer. Repro: the disabled test `ReachabilityServiceTest.pruningShouldNotDiscardEarlierArrivalsThatEnableBetterTransfers` gets 250 min where 120 is possible. Probably the root cause of B3, B4 and B21. Fix direction: keep a Pareto set per station (arrival time × transfers), or switch to a time-dependent algorithm (e.g. RAPTOR / CSA profile).
- [ ] **B21** The incremental search (N seeded from the stored N−1 result) loses stations compared with a search from scratch. Full data, Bruxelles-Midi (`belgien:gs:nmbssncb:8814001`), 0–179 min wait: from scratch 6,181 / 11,767 / 15,233 / 17,165 stations for 2 / 3 / 4 / 5 transfers, incremental 6,117 / 11,581 / 15,026 / 16,961 (up to ~1.6 % fewer). Smaller losses for Paris Gare de Lyon (up to 58), Wien Hbf (up to 6) and Hamburg Hbf (up to 1); Hatvan none. It hits users who query N and then N+1, and everything predictive warming fills. Likely caused by B2: the incremental step only continues from stations reached with exactly N−1 transfers, each with its single best path, so arrival times that a search from scratch still explores are missing. `ReachabilityCacheTest` passes on `gtfs-mini`, which is too small to show it. Related: with 5 transfers, a 179 min max wait reached more stations (17,165) than 180 min (17,138).
- [ ] **B3** Min transfer time filters out connections that should be kept (anything other than 5 min min / 180 max). Likely caused by B2.
- [ ] **B4** Turning the transfer wait time off makes many connections disappear. Likely caused by B2.
- [ ] **B5** Hub precompute always uses a 0–180 min transfer window, whatever the request asks for.
- [ ] **B6** Route-type filter is `2 || 100–106`, but the comment says 107/109 are included. Decide and align. Impact: 109 is suburban railway (S-Bahn), and 3,571 stops are served only by it (Germany 3,260, Finland 238, Denmark 73). S-Bahn trips through stations that are in the graph are dropped too, so a large part of the regional network is missing. Including it adds many short, frequent trips (memory, BFS time); measure after B18.

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F1** Rework station naming: map lat/long to OpenStreetMap station names and use that name for grouping as well.
- [ ] **F3** Max journey time has to be entered repeatedly (frontend).
- [ ] **F4** Zoom level after calculation (frontend).
- [ ] **F5** `/api/admin/debug/stations?includeIsolated=true` is referenced in old notes but not implemented.
- [ ] **F6** Add authentification to acces the site with something like https://clerk.com (Seperate dev accounts and user accounts with different acces)


## Structure
