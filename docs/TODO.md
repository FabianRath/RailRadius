# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B40 · F45 · S33

## Bugs (data loading)
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

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

- [ ] **F37** Top bar, part 2: "Changes" stepper and "Max journey".
  1. Replace the slider block (`.slider-container`, `#transferSlider`, `#transferValue`, the "0 = direct trains only" hint) in `.board-changes` (`flex: none`) with:
     ```
     <span class="board-label" id="transferLabel">Changes</span>
     <div class="stepper" title="0 = direct trains only">
       <button type="button" class="stepper-btn" id="transferMinus" aria-label="Fewer changes">−</button>
       <input type="number" id="transferInput" class="stepper-value" min="0" max="5" step="1" value="1"
              aria-labelledby="transferLabel" aria-describedby="transferHint">
       <button type="button" class="stepper-btn" id="transferPlus" aria-label="More changes">+</button>
     </div>
     <span id="transferHint" hidden>0 = direct trains only</span>
     ```
     The default stays 1. `hidden` keeps the hint out of the layout, while `aria-describedby` still reads it to screen readers.
  2. Styles:
     - `.stepper { display: flex; align-items: center; gap: 8px; }`
     - `.stepper-value { font: 900 28px/1 var(--font-board); color: var(--amber); width: 1.4ch; text-align: center; }` (Doto, the dot-matrix face), plus the spin-button hiding rules that already exist for number inputs
     - `.stepper-btn { width: 28px; height: 28px; background: transparent; border: 1px solid var(--line); border-radius: var(--radius); color: var(--muted); font: 600 18px/1 var(--font-ui); cursor: pointer; }`, hover `border-color: var(--amber); color: var(--text);`, `:disabled { opacity: 0.35; cursor: default; }`
  3. `query-form.js`:
     - Replace `transferSlider`/`transferValue` with `transferInput`, `transferMinus` and `transferPlus`.
     - Minus and plus call `transferInput.stepDown()`/`stepUp()` and then `updateStepperButtons()`, which disables minus at `transferInput.min` and plus at `transferInput.max`. Call it once in `initQueryForm` and on the input's `input` event.
     - `readQuery()` clamps the typed value: `maxTransfers: clampTransfers(transferInput.value)`, where `clampTransfers` uses `Number(transferInput.min)` and `Number(transferInput.max)` and maps empty or invalid input to the min.
     - Delete the old slider listener.
  4. Delete the CSS for `.slider-container`, `.slider-label`, `.slider-value` and `input[type="range"]` with its thumb rules.
  5. `.board-maxtime` (`flex: none`):
     - `<label class="board-label" for="maxJourneyTime">Max journey</label>`, then a row with `<input type="number" id="maxJourneyTime" class="board-value" placeholder="any" min="0.5" max="24" step="0.5" title="Leave empty for no time limit">` (`width: 3ch; text-align: right;`) and `<span class="board-unit">h</span>` (`font: 500 13px var(--font-ui); color: var(--muted); margin-left: 4px;`).
     - `#maxJourneyTime` loses the class `search-input`. Remove the old `.field` wrapper, `.field-label`, `.field-optional` and `.field-hint` markup.
  6. Copy changes from the mockup: "Maximum Transfers" → "Changes", "Max Journey Time (hours) — optional" → "Max journey". The two hints become `title` tooltips; the transfers hint is also the screen-reader description.
  Done when: the smoke check passes. Minus at 0 and plus at 5 are disabled. A typed 9 searches with 5 and an empty field with 0. An empty max time means no limit, and 8 limits to 8 h (compare the counts with the same query before this ticket).

- [ ] **F38** Top bar, part 3: the "Wait" cell and its options panel (wait-time limit and coverage areas).
  1. `.board-options` (`flex: none`) holds a toggle button and the panel:
     ```
     <button type="button" class="board-options-toggle" id="optionsToggle"
             aria-expanded="false" aria-controls="optionsPanel">
       <span class="board-label">Wait</span>
       <span class="board-value"><span id="transferTimeToggleHint"></span> <span class="board-caret">▾</span></span>
     </button>
     <div id="optionsPanel" class="options-panel" hidden> … </div>
     ```
     - The toggle fills the cell like a field (`display: flex; flex-direction: column; justify-content: center; gap: 4px; height: 100%; background: transparent; border: 0; color: inherit; text-align: left; padding: 0; cursor: pointer;`). The cell keeps the `.board-field` padding.
     - `.board-caret`: 14px, `--muted`.
     - When the limit is off, the hint has class `is-off` (already set by `updateTransferTimeHint`) and gets `color: var(--muted)`.
  2. Hint text as in the mockup: `updateTransferTimeHint()` writes `${minMins}–${maxMins} min` (e.g. "5–180 min") instead of the `fmtMins` form, and "off" when the limit is off. `fmtMins` is then unused in `query-form.js`. Remove the import, and delete `fmtMins` from `util.js` if no other module uses it (`fmtSeconds` uses it, so it probably stays).
  3. `#optionsPanel`: `position: absolute; top: calc(100% + 3px); right: 0; width: 300px; z-index: 20; background: var(--board-2); border: 1px solid var(--line); border-top: 0; padding: 16px; display: grid; gap: 14px;`. Contents, top to bottom, all moved from the old option boxes with their ids:
     - **Toggle row.** "Limit wait at changes" (`font: 600 15px var(--font-ui)`, copy change from "Limit Transfer Wait Time") on the left, the existing `#transferTimeToggle` switch on the right.
       - Switch: `width: 36px; height: 20px; border: 0; border-radius: 10px; background: var(--line);`. Knob (`::after`): 14px circle, `background: var(--board)`, `top: 3px; left: 3px;`.
       - `.is-on`: `background: var(--amber)`, knob `left: 19px`.
       - Remove the old `#transferTimeToggleHint` placement inside the panel; the hint now lives in the toggle button.
     - **Wait range.** `#transferTimeInputWrap` with `#minTransferTime` and `#maxTransferTime` side by side.
       - Each input has a label above it, "Min" or "Max" (`.board-label`).
       - Inputs lose `search-input` and get `.panel-input`: `width: 70px; background: var(--board-3); border: 1px solid var(--line); border-radius: var(--radius); padding: 6px 10px; font: 600 16px var(--font-ui); color: var(--text); text-align: center;`.
       - A `–` between them and `min` after them, both `--muted`.
       - The wrap stays `hidden` while the limit is off, as today.
     - **Divider.** A 1px `--line` rule (`<hr class="panel-rule">`).
     - **Coverage checkbox.** The existing `#showMergedZones` checkbox row with the copy "Show reach as areas (slower)" (was "Show merged coverage areas (slower)"): 14px, `display: flex; gap: 8px; align-items: center;`, checkbox `accent-color: var(--amber); width: 16px; height: 16px;`. See the F32 rule above.
  4. Open and close in `query-form.js` (it owns these controls):
     - The toggle flips `optionsPanel.hidden` and `aria-expanded`.
     - Escape inside `.board-options` closes the panel, calls `event.stopPropagation()` (so it doesn't also close the journey) and returns focus to the toggle.
     - A `pointerdown` outside `.board-options` closes it.
     - The existing listeners (`toggleTransferTimeLimit`, the input listeners, the coverage `change` listener) stay as they are.
  5. Delete the now-unused rules `.option-box`, `.option-row`, `.option-title`, `.option-controls`, `.toggle-hint`, `.range-inputs`, `.range-field`, `.range-label`, `.range-separator`, `.checkbox-row` and `.field*`.
  Done when: the smoke check passes. The cell shows "5–180 min", or "off" in muted grey. The panel opens and closes (click, Escape, click outside). Switching the limit off hides the range and makes the search unlimited, as before. "Show reach as areas" still draws the areas.

- [ ] **F39** Top bar, part 4: result count and the GO button.
  1. `.board-count#resultsSection`:
     - `flex: none; margin-left: auto; padding: 10px 22px; border-left: 1px solid var(--line); display: flex; flex-direction: column; justify-content: center; align-items: flex-end; gap: 2px;`
     - Content: `<b id="reachableCount" class="board-digits">—</b>`, styled `font: 900 40px/1 var(--font-board); color: var(--amber); min-width: 4ch; text-align: right;`. The fixed width keeps the cell from jumping while the stream counts up.
     - Below it `<span class="board-label">stations · max <span id="maxTransfersUsed">—</span> changes</span>`.
     - `reachability.js` already writes both ids: the count with `toLocaleString()`, "…" while waiting for the first stations, "—" after a failure or a cancel with no results. Keep that unchanged.
     - Delete the old `.stat-value` rule.
  2. `#calculateBtn` gets class `board-go` (replacing `btn`):
     - `flex: none; min-width: 110px; padding: 0 22px; border: 0; border-radius: 0; background: var(--amber); color: var(--on-amber); font: 700 18px var(--font-ui); text-transform: uppercase; letter-spacing: 0.06em; cursor: pointer;`
     - Hover (not disabled): `filter: brightness(1.08)`. `:disabled`: `opacity: 0.4; cursor: not-allowed;`.
     - `.btn-label` text: "Go" (was "Calculate Reachability").
  3. Loading state. `reachability.js` toggles `.btn-loading` on the button (unchanged). Inner spans:
     - `.btn-label` "Go": hidden while loading.
     - `.btn-busy`: a small spinner and the text "Stop" (was "Calculating…"), shown while loading.
     - `.btn-cancel-hint`: delete it from the markup and CSS. "Stop" says it, and it also works on touch screens, where there is no hover.

     Loading look: `background: var(--board-2); color: var(--amber); box-shadow: inset 0 0 0 2px var(--amber);`. On hover while loading, `color: var(--danger); box-shadow: inset 0 0 0 2px var(--danger);`, and the spinner's top colour turns `--danger`.
  4. `.spinner`: `border: 2px solid var(--line); border-top-color: var(--amber);`, keep the animation, and add `@media (prefers-reduced-motion: reduce) { .spinner, .loading-pulse::after { animation: none; } }`. Delete the old `.btn`, `.btn:hover`, `.btn:disabled` and `.btn-loading*` gradient rules.
  Done when: the smoke check passes, and the bar matches the mockup end to end: amber dot-matrix count, an amber "stations · max N changes" label, an amber GO that is disabled before a station is chosen, "Stop" while streaming (red on hover), and the partial results kept after Stop.

- [ ] **F40** Journey column ("Connection" board). Rewrites the HTML that `station-details.js` builds. The data, `journeyRoute`, `trainTitle` and the open/close behaviour stay as they are.
  1. Column and header:
     - `#stationDetails.journey`: `background: var(--board-2); border-left: 1px solid var(--line);` (width and flex from F35).
     - `.journey-head` (the existing `.station-details-header`, renamed): `padding: 16px 18px 12px; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; align-items: flex-start;`.
     - Eyebrow "Connection": `font: 600 12px var(--font-ui); text-transform: uppercase; letter-spacing: 0.12em; color: var(--amber);`.
     - Close button `#stationDetailsClose`: `width: 28px; height: 28px; background: transparent; border: 1px solid var(--line); border-radius: var(--radius); color: var(--muted); font-size: 18px; line-height: 1;`, hover `color: var(--text); border-color: var(--amber);`.
     - Body `#stationDetailsBody`: `flex: 1; overflow-y: auto; padding: 0;`.
  2. `stationDetailsHtml(station)` builds the following, top to bottom. Station fields, as `station-details.js` uses them today (`ReachableStation`/`JourneyLeg` DTOs): `stopName`, `country`, `transfers`, `totalDuration`, `runningDays`, `hasCommonDay`, `roundTripStation`, `legs` (each `type` `TRAIN` or `TRANSFER`; TRAIN legs have `departureTime`, `arrivalTime` (GTFS `HH:MM:SS`), `fromStation`, `fromCountry`, `toStation`, `headsign`, `operator`, `duration`, `trainName`, `trainNumber`; TRANSFER legs have `fromStation`, `fromCountry`, `duration`), `path` and `pathNames`.
     - `.journey-summary` (`padding: 4px 18px 12px; border-bottom: 1px solid var(--line);`) contains:
       - `.journey-dest`: `stopName` plus country badge (`font: 700 26px/1.1 var(--font-ui); margin-top: 4px;`).
       - `.journey-meta` (`font-size: 14px; color: var(--muted);`): `"{dep} – {arr} · {totalDuration} · {changes}"`. Here dep is the first TRAIN leg's `departureTime` and arr the last TRAIN leg's `arrivalTime`, both through `fmtGtfsClock`. changes is "direct" for 0 transfers, "1 change", or "{n} changes". Leave missing parts out, together with their " · ", and never print "N/A".
     - `.journey-note` lines, one each for running days (calendar icon), "No single day on which all these trains run" and "Contains a round trip: passes {station} twice" (triangle icon). Same conditions and texts as today. Style: `display: flex; gap: 8px; align-items: center; padding: 8px 18px; border-bottom: 1px solid var(--line); background: var(--board-3); font-size: 13px; color: var(--text);` with the icon in `--amber`.
     - `.journey-thead`: `display: grid; grid-template-columns: 72px 1fr auto; column-gap: 10px; padding: 8px 18px; position: sticky; top: 0; background: var(--board-2); border-bottom: 1px solid var(--line);`. Cells are `.board-label`s with the texts "Time", "Station" and "Train" (right-aligned).
     - Per TRAIN leg, two `.journey-row`s (same grid; `padding: 10px 18px; border-bottom: 1px solid var(--line); font-size: 16px;`; `.journey-row:nth-child(even of .journey-row) { background: var(--row-stripe); }`):
       - row 1: time = departure; station = `fromStation` (weight 600) plus badge, and under it a `.journey-sub` line (13px, `--muted`) "towards {headsign} · {operator}" with only the parts present; train = the badge.
       - row 2: time = arrival; station = "→ {toStation}" in `--muted`; train = the leg `duration` (13px, `--muted`, right-aligned).
     - Time cells: `fmtGtfsClock` returns "HH:MM" or "HH:MM +1". Split off the "+N" in `station-details.js` and render `<span class="journey-time">HH:MM</span>` (`font: 900 18px var(--font-board); color: var(--amber);`) followed by `<small class="journey-day">+1</small>` (`font: 600 11px var(--font-ui); color: var(--amber); margin-left: 2px;`).
     - Train badge: `<span class="train-badge level-N">` (classes, since inline styles are not allowed), with `.level-0 { background: var(--transfer-0); }` … `.level-5`. N is the train's index among the TRAIN legs (0 for the first train), capped at 5. That is the same index `journeyRoute` uses for the line colour, so badge and route segment match. Badge: `display: inline-block; padding: 0 6px; border-radius: var(--radius); font: 700 14px/20px var(--font-ui); color: var(--on-amber); white-space: nowrap;`. The text is `trainTitle(leg)`.
     - Per TRANSFER leg, one `.journey-change` row: `padding: 6px 18px 6px 100px; font-size: 13px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted); border-bottom: 1px solid var(--line);`, text "Change at {station} · {duration}". The padding lines it up with the station column (18 + 72 + 10).
     - Fallback without legs (`pathHtml`): the same thead and rows, with "—" in the time cell and no badges. Rows are the first stop, a "Change at …" row per middle stop, and the last stop.
  3. Drop the START/DESTINATION rows, the "Total Journey Time" line and the "Journey details" heading. Their information is now in the summary. This module no longer uses the icons `map-pin`, `flag`, `train-front`, `arrow-left-right` and `clock`. Keep `calendar` and `triangle-alert`.
  4. Delete the CSS for `.station-details`, `.station-details-header`, `.station-details-close`, `.station-details-body`, `.popup-path`, `.popup-total`, `.popup-heading` and every `.leg*` rule. Keep `.popup-station`, `.popup-meta`, `.popup-warning` and `.popup-mono`: the debug popup uses them.
  Done when: the smoke check passes. Clicking a station in the Krems result opens the column on the right with the board rows. The map narrows to make room, and the badge colours match the route segments. A journey past midnight shows "+1". × / Escape / clicking empty map close it. If a route ends up cut off at the right edge in normal use, report it to the owner rather than adding a refit.

- [ ] **F41** Things floating over the map: notices, first-visit hint, loading pill, debug banner, dev login. Positions are already set (F35); this ticket sets their look.
  1. One notice look, `.notice`:
     - `background: var(--board-2); border: 1px solid var(--line); border-left: 3px solid var(--muted); border-radius: var(--radius); padding: 10px 14px; font-size: 14px; color: var(--text);`
     - `#updatingNotice` and `#statusMessage` keep their `role="status"`. `showStatus()` in `util.js` sets `className = 'status status-{type}'`; change it to `'notice status-{type}'`, and rename the class on `#updatingNotice` in the markup.
     - Variants: `.status-info` left border `--muted`; `.status-warning` and `#updatingNotice` left border `--amber`; `.status-error` left border `--danger`, text `--danger`.
     - Delete the old `.status*` rules.
  2. `#introHint`: `.notice` with an amber left border. Text unchanged, `display: flex; gap: 12px; align-items: flex-start;`. The dismiss × uses the journey close-button look (class `board-close`, shared with `#stationDetailsClose`). The `localStorage` dismissal stays.
  3. `#loadingOverlay` (shown with `.visible`, as now):
     - Box: `bottom: 28px; left: 50%; transform: translateX(-50%); background: var(--board-2); border: 1px solid var(--line); border-radius: var(--radius); padding: 10px 18px; gap: 14px; align-items: center; pointer-events: none;`
     - `.loading-pulse` dot and ring `--amber`.
     - `.loading-text` "Calculating…": `font: 600 12px var(--font-ui); text-transform: uppercase; letter-spacing: 0.1em; color: var(--amber);`
     - `.loading-count`: `font: 500 15px var(--font-ui); color: var(--text);`
     - The spinner uses the F39 look.
  4. `#debugBanner`: `left: 50%; transform: translateX(-50%); background: var(--board-2); border: 1px dashed var(--amber); color: var(--amber); border-radius: var(--radius); padding: 8px 16px; font: 13px var(--font-mono);`. Content and the "clear" button are unchanged.
  5. `.dev-footer`:
     - Box: `background: var(--overlay); border: 1px solid var(--line); border-radius: var(--radius); padding: 6px 10px; font: 12px var(--font-ui);`
     - `.dev-toggle` is `--muted`; hover and `.is-active` are `--amber`.
     - `.dev-login` (the token form) is a row below the buttons inside the same box. `#devToken` loses `search-input` and gets `.panel-input` (F38) with `width: 180px; text-align: left; font-size: 13px;`.
     - `.dev-error` is `--danger`.
     - `dev-auth.js` is unchanged.
  6. Delete `.search-input` and its rules if nothing uses the class any more (grep `index.html` and `js/`).
  Done when: the smoke check passes, and each element shows in its place with the board look:
  - the hint on a first visit (clear `localStorage` to see it);
  - "Updating timetables" (stop the backend while the page is open; it disappears after the restart);
  - an error status (search while the backend is stopped, if the notice doesn't take over);
  - the loading pill while streaming;
  - the debug banner (`scripts/api.sh debug-on` while logged in as dev);
  - the dev box bottom left.

- [ ] **F42** Dialogs: About and the admin panel in board style.
  1. Shared dialog look for `.about-panel` and `.admin-panel`:
     - `background: var(--board-2); color: var(--text); border: 1px solid var(--line); border-top: 3px solid var(--amber); border-radius: var(--radius); padding: 24px; font: 15px var(--font-ui);` and `::backdrop { background: var(--overlay); }`
     - Titles (`.about-title`, `.admin-title`): `font: 700 18px var(--font-ui); text-transform: uppercase; letter-spacing: 0.06em; color: var(--text);`
     - The × buttons (`.admin-close`, used by both dialogs) get the `board-close` look (F41).
     - Section headings inside ("Covered countries", "Inspect", "Debug overlay", "GTFS cache") change from `.section-title` to `.board-label` with `margin-bottom: 8px`. Then delete `.section-title` if nothing else uses it.
  2. About: `.about-text` 15px, `--muted`, line-height 1.5. The country list keeps its content and columns, text `--text`.
  3. Admin panel:
     - Font 14px `--font-ui` with tabular figures (was `--font-mono`). `.admin-storage` 13px `--muted`.
     - `.admin-tabs`: bottom border `--line`. `.admin-tab`: `font: 600 13px var(--font-ui); text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted);`. `.is-active`: `color: var(--amber); border-bottom: 2px solid var(--amber);`.
     - `.admin-btn`: `background: var(--board-3); border: 1px solid var(--line); border-radius: var(--radius); color: var(--text); font: 600 14px var(--font-ui); padding: 8px 12px;`. Hover border `--amber`. `.admin-btn-danger` hover: border and text `--danger`. `[aria-pressed="true"]`: border and text `--amber`.
     - Progress bar: track `--board`, value `--amber`, radius `--radius`.
     - `.admin-output` stays `--font-mono` (it shows JSON), with `background: var(--board); border: 1px solid var(--line); border-radius: var(--radius); color: var(--muted);`. `.is-error` is `--danger`.
  4. Feed and alert tables (`.feed-table`):
     - `th` is sticky with `background: var(--board-3)` and the `.board-label` look.
     - Cells: `padding: 6px 8px; border-bottom: 1px solid var(--line);`. A hovered row gets `--board-3`.
     - State and result colours, set dynamically by `feeds.js` and `feed-alerts.js`: `feed-state-ok` and `feed-result-replaced` → `--ok`; `feed-state-expiring` and `feed-result-busy` → `--amber`; `feed-state-missing`, `-expired`, `-unreadable`, `feed-result-rejected` and `-failed` → `--danger`; `feed-muted`, `feed-state-not-yet-valid`, `-no-calendar` and `feed-result-pending` → `--muted`.
     - `.feed-summary` is `--muted`, and its `.is-error` is `--danger`. The wide Feeds layout (`.admin-panel-wide`) keeps its sizing.
  5. Scrollbars: track `--board`, thumb `--line`, thumb hover `--muted`.
  Done when: the smoke check passes, and About and all three admin tabs (Tools, Feeds, Alerts) look like the board. Every admin button and table still works; use only the harmless buttons against dev data, per CLAUDE.md.

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
