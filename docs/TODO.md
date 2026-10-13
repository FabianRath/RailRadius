# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B40 · F26 · S27

## Bugs (data loading)
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)
- [ ] **B39** A stream the search gate turns away (or one whose search fails) logs an ERROR stack trace although the client got its `error` event. `ReachabilityService.sendError` (`ReachabilityService.java:133`) sends the event, then calls `emitter.completeWithError(e)`, which hands the exception to Spring's dispatcher after the SSE response is committed: Tomcat logs `Servlet.service() … threw exception [… SearchGate$BusyException …]` and then WARNs `No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream'`. Repro: full data, default gate, start 9+ large 5-transfer streams at once (S25 burst in `HOSTING.md`). Only log noise, the client side works, but every busy rejection under load looks like a server error.

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.

## Structure
Recommended order (2026-10-13): judge **S24** on the server picked in S25 (`HOSTING.md`: 4 vCores, 8 GB, `-Xmx1280m`).

- [ ] **S24** 5-transfer search time targets: p50 ≤ 1.5 s, p95 ≤ 3 s. Full data, `-Xmx3g`, warmer off, `scripts/bench.sh times full`. Gate: zero diff. Not met on 2026-10-13: ten runs of HEAD (S23 done), each on a fresh app with the machine idle, measured p50 1.90–2.12 s and p95 3.37–3.69 s. The same code and store measured p50 1.36 s and 1.42 s earlier that afternoon (`HOSTING.md`): the dev machine flips between two speed levels, with the whole curve ~1.5× slower in the slow one (1 transfer: p50 ~60 ms instead of ~38 ms). Ruled out: the date (Stockholm does the same work as on 2026-10-10), background load, power plan and boost, the store file, and the startup feed check (it parses all zips for ~55 s after every start, so wait until the app is idle before benching; waiting changed nothing here). So on this machine bench only compares builds in alternating runs; judge the absolute targets on the server (picked in S25, `HOSTING.md`).
