# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B37 · F26 · S12

## Bugs (data loading)
- [ ] **B30** (Low priority) A few duplicated stations are 400 m – 2 km apart, mostly because one feed's coordinates are off, and share no station code, so no rule merges them (the same-code rule merges the rest). Remaining examples: Apach and Lauterbourg (the German feed gives these French stations DB numbers, `germany:000008000591` / `000008003597`, the French feed UIC codes `OCE87191643` / `OCE87212464`), Hergenrath (`belgien:…S8844644` vs `germany:be:63000:4312`), Tende, Albertville (Eurostar: its stop IDs are names, the UIC code is only in `stop_code`). Possible fix: a code from `stop_code` for feeds like Eurostar; for the rest, a table of known pairs.
- [ ] **B36** Some countries have no current GTFS feed, so their trains are missing. State on 2026-10-06 (`gtfs-sources.md` has the details): every zip in `backend/data/gtfs/` runs until at least 2026-12-12, but three feeds are not there. `blues3.transitpdf.com`, their only source, no longer exists. Estonia: new source `eu-gtfs.remix.com/elron.zip` in `gtfs-feeds.txt`, not downloaded yet (update center → download, then reload). Northern Ireland: no GTFS found; Translink publishes only TransXChange on OpenDataNI. Bosnia: the only source still ends on 2025-12-13.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.

## Structure
