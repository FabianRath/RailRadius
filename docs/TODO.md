# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B39 · F26 · S27

## Bugs (data loading)
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.

## Structure
- [ ] **S23** A large 5-transfer search still holds ~250 MB (target ≤ 200 MB held, missed in S22 and S21). Full data, Stockholm, fresh app. Done so far: dead boardings are dropped (boardings held per round 118 → 42 MB), and a round's rides are rows with labels only for candidates and arrivals boarded from (arena 6.97 M → 1.24 M labels, held 363–385 → 252 MB; `decisions.md` 2026-10-10). Left in the 218 MB scratch: trip progress 79 MB, labels 50 MB (1.24 M labels just over the arena's 1.20 M growth step), grouped arrivals 49 MB, rides 36 MB; plus ~42 MB of boardings during a round. Gate: zero diff on gtfs-mini, dev and full data, no slower.
- [ ] **S24** 5-transfer p50 was 1.70 s (target ≤ 1.5 s; p95 2.66 s meets its ≤ 3 s). Full data, `-Xmx3g`, `scripts/bench.sh`. Gate: zero diff. Since dead boardings are dropped (S23, partly), two runs measured p50 1.42 s and 1.48 s (p95 2.25 s and 2.33 s, `HOSTING.md`): under the target, but close to it. Confirm with more runs before closing.
- [ ] **S25** `HOSTING.md` still recommends 16 GB RAM and `-Xmx9g`, sized for the legacy engine. The compact engine measured 405 MB idle heap, ~450 MB startup peak and ~250 MB per large search at `-Xmx3g` (S23) (4 concurrent 5-transfer searches fit in `-Xmx2g`). Re-measure concurrency, then pick a smaller server and heap and update the recommendation.
- [ ] **S26** `backend/data/gtfs-store.bin` still carries the station merges from before B29/B30. `gtfs-store-s21.bin` is a fresh parse of the current zips with the current rules, and `data/snapshots/*-s21-*` are its reference dumps. Swap the store file in, make the s21 dumps the goldens, and delete the outdated dumps and stores no longer needed for the `pre-compact` rollback.
