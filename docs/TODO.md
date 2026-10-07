# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B37 · F26 · S12

## Bugs (data loading)
- [ ] **B34** (High priority) A feed can look valid in the update center while its long-distance trains have already ended: the Swedish feed (exported 2026-01-27) has service days until 2026-12-12, but only from regional trains planned that far; SJ's trains (e.g. SJ 534, SJ 94) end at the timetable change on 2026-06-14, so Sweden has no long-distance trains after that and journeys from Germany (feed 2026-09-12 – 12-12) can't continue north. The admin panel only shows the calendar's last date. Possible fix: also show per feed the last day of its trains per operator or route type, and warn (EXPIRING) when a main operator ends long before the feed's last day. Workaround: download the feed again.
- [ ] **B30** (Low priority) About 18 duplicated stations are 400 m – 2 km apart, mostly because one feed's coordinates are off, so no distance-based rule merges them. Examples: the Swiss feed's French stations Saint-Nabord, Irigny, Saint-Amand-Montrond, La Levade (~1.5 km off), Albertville (Eurostar), Viareggio and Desenzano (Trenord vs Trenitalia), Abfaltersbach and Weitlanbrunn (Austria vs Italy), Hergenrath, Apach, Lauterbourg, Tende, Travedona. About 11 of them carry the same UIC station code on both sides in different forms: `switzerland:8714621` / `france:…OCE87146217` (French form adds a check digit), `italy-trenord:S02084` / `italy:…830002084`, `austria:Pat:47:3877` / `italy:…810003877`. Possible fix: a merge rule matching by UIC code, with one extraction rule per feed ID format. A station can have several codes (København H `8600626` in the Danish feed, `8601309` in the German one), so only equal codes merge and the other rules stay.
- [ ] **B36** Some countries have no current GTFS feed, so their trains are missing. State on 2026-10-06 (`gtfs-sources.md` has the details): every zip in `backend/data/gtfs/` runs until at least 2026-12-12, but three feeds are not there. `blues3.transitpdf.com`, their only source, no longer exists. Estonia: new source `eu-gtfs.remix.com/elron.zip` in `gtfs-feeds.txt`, not downloaded yet (update center → download, then reload). Northern Ireland: no GTFS found; Translink publishes only TransXChange on OpenDataNI. Bosnia: the only source still ends on 2025-12-13.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F23** (Decide first) While a station's journey is shown (F21), the dots of the stations it passes through keep their own transfer-level colour, so they don't always match the line colour of the train passing them (first train green, second cyan, …): a stop on the third train can be green because another train reaches it directly. The route looks inconsistent, but the colour also tells the user that this stop can be reached with fewer transfers, and clicking it shows that shorter journey. Options: (1) recolour the path's dots by the train they are on while the journey is shown; (2) keep the station's own colour (current behaviour), maybe explained in the details panel; (3) both, e.g. the train's colour as fill and the station's own level as outline. Decide with the user, then implement.

- [ ] **F24** Label journeys that pass the same station twice. The wait limit (e.g. 5–180 min) applies to each transfer, so the search can bridge a longer wait with a round trip: Aachen → Luleå (5 transfers) reaches Stockholm at 09:45, but the night train to Luleå leaves at 18:12 (8h 27m wait), so the journey rides Stockholm → Karlstad → Stockholm (waits 1h 21m and 56m) and arrives no earlier than waiting would. Valid under the current rules, but it dodges what the limit means. Detect a journey whose legs visit a station more than once and show a label (e.g. "contains a round trip") in its journey details. The station stays reachable; filtering such journeys out is a separate decision.

## Structure
