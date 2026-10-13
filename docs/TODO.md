# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B40 · F30 · S31

## Bugs (data loading)
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.
- [ ] **F26** Per-IP search rate limit, before launch (council report 2026-10-07, Phase 0). Don't just count searches (the report's "about 10 a minute"): weight each one by how heavy it is, since a 0-transfer search costs a fraction of a 5-transfer one. E.g. a per-IP budget that refills over time, with a cost per search that grows with the transfer count (measure the real ratios with `scripts/bench.sh times`). A result served from `ReachabilityCache` without a search should cost little or nothing. Over budget: a clear "too many searches, try again in N s" reply (SSE error event / 429).
- [ ] **F27** First-visit hint, before launch (council report, Phase 0): one sentence explaining what the map shows, a pre-filled example search, and the label "0 = direct trains only" on the transfer slider.
- [ ] **F28** Show "updating timetables" while the server restarts or reloads the GTFS data, instead of failed searches or a broken page (council report, Phase 0).
- [ ] **F29** Alert for feeds that expire within 30 days (council report, Phase 5). `FeedStatus` already has `EXPIRED`/`EXPIRING` and the admin panel shows them, but nobody is told unless they look.

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
- [ ] **S28** CORS: `@CrossOrigin(origins = "*")` on `RailReachController` and `AdminController` lets any site use the API. Restrict it to the production domain (council report, Phase 0).
- [ ] **S29** Copy MapLibre GL JS and Turf into `frontend/vendor/` instead of loading them from unpkg (`index.html`, `js/map.js`), so visits don't send IP addresses to a third party (GDPR, LG München 2022; council report, Phase 0).
- [ ] **S30** Set the actuator web exposure to `health` explicitly in `application.yml`. Today only Spring's default keeps the other endpoints closed (council report, Phase 0).
