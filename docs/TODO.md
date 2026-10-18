# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B54 · F50 · S41

## Bugs (data loading)
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Bugs (security)

## Bugs (frontend)
- [ ] **B40** Search list shows station ids: each result line shows the id instead of "N connections". The search response (`StationSearchResult` in `RailRadiusController`) has no `connections` field, so that branch in `stationItemHtml` (`frontend/js/search.js`) never runs.
- [ ] **B51** The Wait cell shows "5–… min" when the minimum wait is 0, but the search uses 0. `updateTransferTimeHint` (`frontend/js/query-form.js`) reads the field with `parseInt(…) || HINT_MIN_TRANSFER_MINUTES`, so 0 falls back to 5. Show 0; only an empty field should fall back.
- [ ] **B52** The form (and a deep link, F46) accepts a minimum wait above the maximum, e.g. 200–100 min. The backend rejects it (`SearchRequestValidator`) and the user only sees "Search failed: The wait at changes must be …". Show a clear message at the wait fields before the search is sent.
- [ ] **B53** (Check first, full data) Possible: a stop ID that contains `/` can't be looked up through `GET /api/stations/{id}`. The frontend encodes it as `%2F`, which Tomcat rejects by default, so a deep link (F46) to that station would say "Station from the link not found". Check whether any feed in the full dataset has such stop IDs (capped peeks only, e.g. `unzip -p <zip> stops.txt | grep -m 5 …`, see CLAUDE.md hard rules); if yes, reproduce and fix, if no, delete this item.

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.
- [ ] **F31** (Decide first, hosting) A visit while the backend is down (restart) gets no page at all, since the backend serves the frontend; F28's "updating timetables" notice only helps a page that is already open. Decide whether and how to cover this, e.g. a maintenance page from the reverse proxy on 502/503 (nginx `error_page`, Caddy `handle_errors`), or serving `frontend/` from the proxy so the page loads and shows the notice itself. Depends on the hosting setup (`HOSTING.md`).

- [ ] **F45** (Before launch, legal) Impressum and privacy notice (Datenschutzerklärung), required for a public site run from Germany. The notice covers: the search rate limit keeps a key per visitor (IPv4 address, IPv6 /64: `ClientKeys`) in memory only, dropped after `search.rate-limit.capacity / refill-per-second` idle seconds (200 s by default), never logged; after a failed admin login, the login throttle keeps the same key in memory for 15 min, and each failure writes the full IP to the application log (WARN, `AdminLoginThrottle`), kept as long as the server keeps its logs (the app logs to stdout only, so set and state that retention with S38); the proxy's access logs, with retention; the map tiles loaded from OpenFreeMap (a third party that sees visitor IPs); no cookies except the admin one (a random session id, in memory for 12 h, `AdminSessions`). Link both from the About dialog, next to the source attribution page (S27 follow-up). Check the wording with a generator or a lawyer.

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
- [ ] **S36** The SSE stream timeout is 30 minutes (`RailRadiusController.java:78`) although a search takes seconds. Shorten it to a few minutes, so dead connections don't linger.
- [ ] **S37** Two identical searches that start at the same moment with free permits both run (a queued one re-checks the cache, these don't). Let the second wait for the first's result instead, so a shared link opened by many at once costs one search.
- [ ] **S38** Ops checklist in `HOSTING.md`, before launch: proxy settings for SSE (buffering off, read timeout above the longest search), HSTS, connection caps, `RAILRADIUS_FORWARD_HEADERS_STRATEGY=native`; `-XX:+ExitOnOutOfMemoryError` and an automatic restart (systemd); firewall open only for 80/443 and SSH, SSH by key only; an external uptime check on `/api/health`; keep the previous store file for rollback.
- [ ] **S40** A passing `mvn -q -f backend/pom.xml test` prints ~5,600 lines (1.2 MB), almost all our own INFO logs from loading the fixture (`GtfsFeedLoader`, `GtfsDataService`, `RailTripReader`, …), plus Spring's startup for `RailRadiusBackendApplicationTests`. It buries real failures and contradicts CLAUDE.md ("prints nothing on success"). Set test logging to WARN (e.g. a `logback-test.xml` in `src/test/resources`, which also covers the plain unit tests outside Spring) and keep the deliberate WARNs quiet in tests if they are expected.
- [ ] **S39** Abuse probe script (`scripts/`): the requests from the 2026-10-17 review (invalid and oversized parameters, unknown stations, `limit=-1`, path tricks on `/api/admin`, header check), run against the full data before launch and after security changes. Each must get the expected status.