# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B41 · F45 · S33

## Bugs (data loading)
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Bugs (frontend)
- [ ] **B40** Search list shows station ids: each result line shows the id instead of "N connections". The search response (`StationSearchResult` in `RailRadiusController`) has no `connections` field, so that branch in `stationItemHtml` (`frontend/js/search.js`) never runs.

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.
- [ ] **F31** (Decide first, hosting) A visit while the backend is down (restart) gets no page at all, since the backend serves the frontend; F28's "updating timetables" notice only helps a page that is already open. Decide whether and how to cover this, e.g. a maintenance page from the reverse proxy on 502/503 (nginx `error_page`, Caddy `handle_errors`), or serving `frontend/` from the proxy so the page loads and shows the notice itself. Depends on the hosting setup (`HOSTING.md`).
- [ ] **F32** (Decide first) The "Show merged coverage areas (slower)" option (`frontend/js/coverage.js`) merges a buffer circle around every reached station into one area per transfer level. The user doesn't like how the merged areas look, and the cost is too high for it to be useful beyond a few hundred stations anyway: it runs one `turf.union` per station, sequentially on the main thread, after every search and again after every zoom (the buffer radius depends on the zoom). Decide with the user whether to redesign it (different look and a much cheaper approach) or remove it.

## Structure
Recommended order (2026-10-13): judge **S24** on the server picked in S25 (`HOSTING.md`: 4 vCores, 8 GB, `-Xmx1280m`).

- [ ] **S24** 5-transfer search time targets: p50 ≤ 1.5 s, p95 ≤ 3 s. Full data, `-Xmx3g`, warmer off, `scripts/bench.sh times full`. Gate: zero diff. Not met on 2026-10-13: ten runs of HEAD (S23 done), each on a fresh app with the machine idle, measured p50 1.90–2.12 s and p95 3.37–3.69 s. The same code and store measured p50 1.36 s and 1.42 s earlier that afternoon (`HOSTING.md`): the dev machine flips between two speed levels, with the whole curve ~1.5× slower in the slow one (1 transfer: p50 ~60 ms instead of ~38 ms). Ruled out: the date (Stockholm does the same work as on 2026-10-10), background load, power plan and boost, the store file, and the startup feed check (it parses all zips for ~55 s after every start, so wait until the app is idle before benching; waiting changed nothing here). So on this machine bench only compares builds in alternating runs; judge the absolute targets on the server (picked in S25, `HOSTING.md`).

- [ ] **S27** Licence audit, before launch (council report, "the one thing to do first"). The licence column in `docs/gtfs-sources.md` is filled in (2026-10-13); 31 feeds have a clear open licence. Still open, with possible fixes (any feed still unclear at launch gets dropped):
  - **No licence found:**
    - Bosnia, Kosovo, Moldova, North Macedonia (rehosts by Petr Novák): ask him what the source and licence are, or ask the operators.
    - Greece, Lithuania, Ukraine (jbb): ask Jonah Brüchert (Transitous).
    - Bulgaria (jbb): make the CC BY 4.0 livetransport.eu feed the main source (BDŽ only, 30 days ahead).
    - Serbia (vekejsn GitLab generator): ask the maintainer.
    - Cotral: look on cotralspa.it for an open-data page, or ask Cotral.
    - Slovakia: open the data.slovensko.sk dataset page in a browser (an older Mobility Database entry said CC0).
    - Northern Ireland (busmaps.com derivative, B36): ask busmaps.com, or drop it.
  - **Own terms, check by hand:**
    - Belgium: read SNCB's licence (belgiantrain.be, needs a browser) and check whether a public map counts as allowed reuse.
    - Hungary: check MÁV's approval email or the Hungarian NAP (napportal.kozut.hu) for terms.
    - Portugal (CP, Fertagus): read the conditions on the Portuguese NAP pages in a browser.
    - Great Britain: confirm National Rail's terms for the timetable, and ask Aubin about its compilation.
    - Spain OUIGO: the Spanish NAP licence requires the "Powered by MITRAMS" credit; look for OUIGO's own licence.
    - Turkey: the generator is MIT, but the data comes from TCDD's website; ask the maintainer, or drop it.
    - Croatia, EAV: their licences (CC0, CC BY 4.0) come only from Transitous; confirm them with the publishers.
  - **Follow-up:** almost every licence requires attribution, so the site needs a page naming every source.
- [ ] **S30** Set the actuator web exposure to `health` explicitly in `application.yml`. Today only Spring's default keeps the other endpoints closed (council report, Phase 0).
