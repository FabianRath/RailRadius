# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B29 · F20 · S12

## Bugs (data loading)
- [ ] **B28** The cross-border name merge doesn't ignore accents: `StationDeduplicator.tokenize` lowercases but keeps diacritics, so "Setúbal" and "Setubal" are different words. In a 2026-10-05 test load (Italy, Spain, Portugal and the new feeds) four nearby pairs stayed separate only because of this: OUIGO's MADRID-CHAMARTIN, VALENCIA JOAQUIN SOROLLA and MALAGA MARIA ZAMBRANO vs Renfe's accented names, and Fertagus' "Setúbal" vs CP's "Setubal". Trains of the two feeds then don't connect there. Folding accents (as `StationNameVariants` does for search) would merge all four. Not checked on the full data.
- [ ] **B27** (Possible, unconfirmed) The `european-sleeper` feed names its Aachen stop "Aachen Hauptbahnhof" (stop `8015345`), the other feeds "Aachen Hbf". The cross-border name merge (`StationDeduplicator`, Jaccard word similarity ≥ 0.4) scores the pair 1/3, so after a reload with the full data Aachen may exist twice and the sleeper train would not connect to other trains there. Not seen on the map yet: the feed was added on 2026-10-05 and the running data had not been rebuilt. A 2026-10-05 test load without the German feed showed it unmerged next to Belgium's "Aachen Hbf (DE)". Check after the next rebuild by searching "Aachen Hauptbahnhof". Possible fixes: treat "Hbf" and "Hauptbahnhof" as one word when merging, or match stations by UIC code (European Sleeper and Eurostar both use them).
- [ ] **B26** The Belgian feed (dated 2026-02-11) has Nightjet NJ 479 Brussels → Wien on only 10 and 12 Dec 2025: three trips (`…:3413:20251210`, `…:3413:20251210:0`, `…:3413:20251212`) whose services have no weekdays in `calendar.txt` and 1–2 added dates in `calendar_dates.txt`. The real train runs several times a week, so the popup shows "Irregular: 1 day in the timetable" (e.g. Aachen Hbf → Wien Meidling). Check whether a newer feed or the ÖBB feed has the full schedule.
- [ ] **B24** Several GTFS feeds have expired, so their trains can no longer be found on the operators' sites. Last service date per feed (checked 2026-10-05, from `calendar.txt`/`calendar_dates.txt`): greece 2019-01-31, france 2025-04-22, hungary 2025-11-23, bosnia 2025-12-13, croatia 2025-12-14, luxembourg 2026-04-30, denmark 2026-05-06, germany 2026-06-13, poland 2026-06-13. Download current feeds with the update center (admin panel → Feeds, or `scripts/api.sh feeds-download-outdated`), then reload. `s3.transitpdf.com` (greece, hungary, croatia and six others in `gtfs-feeds.txt`) did not answer on 2026-10-05. `gtfs-sources.md` lists replacements on jbb.ghsq.de.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F19** Sort the admin panel's Feeds tab by state instead of by name: Expired, Not yet valid, No calendar, Unreadable, Expiring, OK; within each group lowest `daysLeft` first (No calendar and Unreadable have none, so keep those by name). Today the rows come in zip-file order (`FeedUpdateCenter.java:130`).
- [ ] **F18** The loading overlay says "Calculating…" but its counter says "Searching… N s" (`frontend/js/reachability.js:148`, `:152`). Use "Calculating" in both places for consistency.

## Structure
