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

## Redesign "Abfahrt" (F33–F44)
The user picked concept 5, "Abfahrt", from the design council of 2026-10-16. The search becomes a departure board across the top of the screen (blue-black, amber, condensed type, dot-matrix numerals), the map fills the width below it, and a selected journey opens as a board column on the right.

**Reference.** The mockup is `council-report-2026-10-16.html` in the repo root (gitignored, so it exists only on the dev machine; don't delete it before F44 is done). Open it in a browser, section "5 · Abfahrt": the big frame is the desktop, the small one the phone. Its source holds the same values as these tickets: CSS block `/* ── C5 Abfahrt ── */`, markup in `<article id="abfahrt">`, colours in `RAMPS.c5` and `STYLES.c5`. Every pixel value in the mockup CSS and in these tickets is a real CSS pixel at 100 % zoom. The page only scales the 1100×640 frame down to fit, so take the values as they are.

**What is literal and what isn't.** Literal: colours, fonts, sizes, spacing, borders, the order and structure of the bar and the board rows, and every copy text a ticket names. Example content only: Wien Hbf, Kraków, the train numbers, times and the 1284 count in the mockup. The real content comes from the app's data, as each ticket describes. Values that are not in the mockup and were decided here (dot radii, casing width, breakpoints, states the mockup doesn't show) are marked **Decision** and are just as binding.

**Rules for every ticket in this block**
- Do them in order, one commit each, with the ID in the message (e.g. `Add design tokens and board fonts (F33)`). Every ticket leaves the app fully working and good enough to ship as an intermediate state. Nothing is half-moved or hidden. If a ticket can't be finished, revert it rather than commit a broken state.
- Behaviour stays the same: same API calls, search, SSE stream and journey data, same element ids unless a step renames one. Only markup, CSS and presentation code in JS change. The only behaviour changes are the ones named in a step, and each says why it is needed.
- No new libraries. Fonts are self-hosted OFL files (F33), never loaded from Google or any CDN at runtime.
- All colours, fonts, radii and fixed sizes are tokens in `css/tokens.css` (F33). No hex or `rgb()/rgba()` literal anywhere else in `css/` or `js/` (F44 checks this).
- Corners: `var(--radius)` (2px) everywhere, except the round toggle switch, dots and the start marker. No gradients, no `backdrop-filter`, no drop shadows or glows (`box-shadow` with blur). Zero-blur `box-shadow` used as an inset bar or ring is allowed. No transitions or animations except the existing spinner and pulse, which stop under `prefers-reduced-motion: reduce`.
- Keyboard: every interactive element shows `outline: 2px solid var(--amber); outline-offset: 2px` on `:focus-visible` (one global rule, F33). Keep the existing `role`, `aria-*` and `<label for>` links when moving elements. New toggles get `aria-expanded`/`aria-controls` as specified. The tab order follows the visual order (bar from left to right, then the map, then the journey column).
- **Smoke check after every ticket** (the frontend has no automated tests): `mvn -q -f backend/pom.xml compile` and `test` pass; start the app with the `/run-dev` skill and open `http://localhost:8080/`. Then check that:
  1. the page loads with no console errors;
  2. "Krems Bahnhof" is prefilled;
  3. typing "wien" lists stations and picking one works;
  4. a 2-transfer search streams dots and shows the count;
  5. clicking the button during a search cancels it and keeps the partial results;
  6. clicking a dot opens the journey, and × / Escape / clicking empty map closes it;
  7. the wait limit can be switched off and on;
  8. "Show reach as areas" draws the areas;
  9. About opens and lists the countries;
  10. dev login with the admin token shows the admin button, and the admin panel's three tabs open.

  Use the `claude-in-chrome` skill for the browser part if it is available. If no browser tool is available, say exactly which checks and visual comparisons were not done and ask the owner to do them. Don't mark a ticket done on unverified visuals.
- `docs/ARCHITECTURE.md` (the frontend lines under "Layout") and `docs/CODING_GUIDELINES.md` §8 (the module list) change in the ticket that changes what they describe. When a ticket is done, delete its line here (CODING_GUIDELINES §9). F44 deletes this heading and these rules.
- F32 (merged coverage areas, "decide first") may rework or remove the "Show reach as areas" option. If F32 is decided before F38, follow its outcome in F38 (keep, change or drop the checkbox). Otherwise build F38 as written.
- `frontend/backend_test.html` is a dev test page and is not part of this redesign.

- [ ] **F43** Narrower screens. The current app has none of this; the phone part follows the mockup's phone frame.
  1. **Decision**, 1024–1279px (laptops): `.board-tagline` hidden, `.board-brand` `width: auto; padding: 16px;`, `.board-from` `flex-basis: 200px`, `.board-field` `padding: 12px 14px`, `.board-value` 20px, `.board-digits` 32px, `.board-count` `padding: 10px 16px`. Check that the bar fits at 1024px with a long station name in the From field (e.g. "Wiener Neustadt Hauptbahnhof"), which gets cut with an ellipsis.
  2. Below 1024px, the bar wraps into rows:
     - `.app { grid-template-rows: auto 1fr; }`
     - `.board-bar { display: grid; grid-template-columns: repeat(3, 1fr); grid-template-areas: "brand brand count" "from from from" "changes maxtime options" "go go go"; gap: 0; }`, giving each cell its `grid-area`.
     - Cells: `border-right: 0; border-bottom: 1px solid var(--line); padding: 10px 14px;`.
     - Wordmark 18px, tagline hidden, count digits 28px.
     - GO is 44px high with `border-radius: 0`.
     - So the dropdown and the options panel span the full width: set `.board-field { position: static; }` at this width, so their `position: absolute` anchors to `.board-bar` (which is `position: relative`). Then `#stationResults` and `#optionsPanel` get `left: 0; right: 0; width: auto; top: 100%;`.
  3. Below 768px, the journey column becomes a bottom sheet over the map: `.board-main { grid-template-columns: 1fr; }`, `.journey { position: absolute; left: 0; right: 0; bottom: 0; width: auto; max-height: 60%; border-left: 0; border-top: 3px solid var(--amber); z-index: 8; }`. The map keeps its full size behind it. Calling `resizeMap()` does no harm here.
  4. Below 768px, things over the map:
     - `.map-notices { left: 8px; right: 8px; top: 8px; max-width: none; }`
     - `#loadingOverlay { left: 8px; right: 8px; transform: none; justify-content: center; bottom: 16px; }`
     - `.dev-footer { left: 8px; bottom: 8px; }`
     - `#debugBanner { left: 8px; right: 8px; transform: none; white-space: normal; }`
  5. Touch targets below 1024px: the stepper buttons, close buttons and the options toggle are at least 40×40px.
  Done when: the smoke check passes at 1280×800, 1024×768 and 390×844. Nothing scrolls sideways, every control can be reached and used, and a journey opens and closes on the phone layout.

- [ ] **F44** Clean-up and final check.
  1. Remove the bridge (F33 step 5): replace every remaining use of an old variable name (`--bg-*`, `--accent-*`, `--warning`, `--danger-bg`, `--text-primary`, `--text-secondary`, `--border`, `--divider`, `--font-sans`) with the token itself, then delete the old `:root` block in `app.css`.
  2. Delete CSS rules whose class or id appears nowhere in `index.html` or `js/`. Grep each selector. Keep:
     - classes set dynamically or by MapLibre: `feed-state-*`, `feed-result-*`, `status-*`, `is-*`, `level-*`, `btn-loading`, `visible`, `admin-panel-wide`, `maplibregl-*`, `station-popup`;
     - `[hidden]`, element selectors and pseudo-elements.
  3. Delete icons from `icons.js` that no module uses any more. Grep `icon('<name>')` and `data-icon="<name>"`. Leave the licence header.
  4. Colour check: `grep -rnE "#[0-9a-fA-F]{3,8}\b|rgba?\(" frontend/css frontend/js --include=*.css --include=*.js | grep -v "css/tokens.css"` must print nothing. Id selectors such as `#map` or `#loadingOverlay` can match the hex part; tighten the pattern, e.g. require the match after `:` or `(`, and check the rest by eye. Add the final command to `docs/CODING_GUIDELINES.md` §8, next to the rule against hardcoded colours.
  5. `docs/ARCHITECTURE.md`: check every frontend claim against the code, covering the board layout (top bar, map area, journey column, the two breakpoints), `tokens.css`, the fonts, and the map recolouring and dot sizes. Grep it for removed names (`sidebar`, `stat-card`, `slider`, `station-details-header`).
  6. Final visual comparison with the mockup at 1440×900 and 390×844:
     - bar, dropdown, options panel, count, GO states, map tones and dot sizes;
     - journey column, notices, loading pill, dev box, About, admin (all tabs);
     - focus outline visible on every control when tabbing through;
     - the network tab shows requests only to `localhost` and `tiles.openfreemap.org`.

     This needs a browser. Without one, hand it to the owner as a checklist.
  7. Delete this block's heading, intro and rules from `TODO.md`. Keep `council-report-2026-10-16.html` locally or delete it, as the owner prefers.

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
