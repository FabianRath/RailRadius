# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B37 · F26 · S12

## Bugs (data loading)
- [ ] **B30** (Low priority) A few duplicated stations are 400 m – 2 km apart, mostly because one feed's coordinates are off, and share no station code, so no rule merges them (the same-code rule merges the rest). Remaining examples: Apach and Lauterbourg (the German feed gives these French stations DB numbers, `germany:000008000591` / `000008003597`, the French feed UIC codes `OCE87191643` / `OCE87212464`), Hergenrath (`belgien:…S8844644` vs `germany:be:63000:4312`), Tende, Albertville (Eurostar: its stop IDs are names, the UIC code is only in `stop_code`). Possible fix: a code from `stop_code` for feeds like Eurostar; for the rest, a table of known pairs.
- [ ] **B36** Bosnia has no current rail feed, and Northern Ireland's can't be refreshed. State on 2026-10-07 (`gtfs-sources.md` has the details). Bosnia: the only source ends on 2025-12-13, so no Bosnian trains. Northern Ireland: the zip on disk (GTFS.pro copy from transitpdf, 2026-07-27) has NI Railways until mid-2027, but transitpdf is gone and Translink publishes only TransXChange, so no download URL is left in `gtfs-feeds.txt`. Check for a new GTFS source before mid-2027.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.

## Structure
