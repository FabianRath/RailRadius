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
Recommended order (2026-10-13): confirm **S24** with timing runs on a quiet machine, then **S25**: the per-search memory no longer changes (S23 done, ~195 MiB held).

- [ ] **S24** 5-transfer p50 was 1.70 s (target ≤ 1.5 s; p95 2.66 s meets its ≤ 3 s). Full data, `-Xmx3g`, `scripts/bench.sh`. Gate: zero diff. Since dead boardings are dropped (S23, partly), two runs measured p50 1.42 s and 1.48 s (p95 2.25 s and 2.33 s, `HOSTING.md`): under the target, but close to it. Confirm with more runs before closing. The S23 growth-step runs measured p50 ~2.2 s on both builds alike (machine slower that evening), so they don't count.
- [ ] **S25** `HOSTING.md` still recommends 16 GB RAM and `-Xmx9g`, sized for the legacy engine. The compact engine measured 405 MB idle heap, ~450 MB startup peak and ~195 MiB per large search at `-Xmx3g` (S23) (4 concurrent 5-transfer searches fit in `-Xmx2g`). Re-measure concurrency, then pick a smaller server and heap and update the recommendation.
