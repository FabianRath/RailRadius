# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B39 · F26 · S23

## Bugs (data loading)
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)
- [ ] **B38** An `OutOfMemoryError` in a search leaves its SSE stream hanging and keeps its scratch. Seen on full data at `-Xmx3g` with 4 permits (2026-10-10): all four 5-transfer searches ran out of memory, no client got a `done` or `error` event (curl waited > 10 min), and the live heap stayed at 2.8 GB afterwards, because each failed search still gave its partly grown `SearchScratch` back for reuse. `ReachabilityService.calculateReachabilityStreaming` catches `Exception`, which an `Error` passes through; `CompactReachabilitySearch.search` offers the scratch back in `finally`.

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.

## Structure

## Compact search store (do in order)
Plan from the 2026-10-09 council (`council-report-2026-10-09.html`). Every item has a numeric gate. Results must stay byte-identical to the S12 goldens, and nothing is merged while a diff line is unexplained (accepted diffs go into `decisions.md`). Goal on full data: live heap ≤ 1.0 GB (stretch 500 MB), 4 concurrent 5-transfer searches + warmer at `-Xmx3g`, so the server can move from 16 GB to an 8 GB VPS.
- [ ] **S22** Compact engine memory (from S18, which held up to ~450 MB per large full-data search against a planned ≤ 200 MB; see `decisions.md` 2026-10-09 and `HOSTING.md`). Ideas, cheapest first: ride each batch of stations right after it chose its boardings, so no round holds all boardings (the trim then moves after the round); labels without `transfers` (round ranges) and with the exact-days memo in a side table; trip progress only for reached trips; then, if still needed, pruning arrivals that no departure can use. Gate: zero diff, p95 no worse, held per search measured with `bench.sh held` on a fresh app, and 4 concurrent 5-transfer searches at `-Xmx3g` (moved here from S19, whose 4 searches each needed a ~444 MB scratch over 1.54 GB idle and ran out of memory).
- [ ] **S21** Delete legacy. Tag `pre-compact` first (rollback = that jar + its JSON cache). Remove ReachabilitySearch, SearchTimetables, TripTimetable, Ride, TripProgress, StationCandidates, the old TransferWindow, RideKey, the engine switch, the server-side JSON reader, and TransitGraph's `connections`/`tripStops`/`stopTrips`/`neighbors`/`connectionByTrip` (what dedup still needs stays load-time only). Update `ARCHITECTURE.md` and `HOSTING.md`. Gate: zero diff, grep for the deleted names is empty. Final targets: live heap ≤ 1.0 GB, startup peak ≤ 1.5 GB, startup ≤ 3 s, held per 5-transfer search ≤ 200 MB, p50/p95 ≤ 1.5 s / 3 s, cached result ≤ 3 MB.
