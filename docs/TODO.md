# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B26 · F17 · S12

## Bugs (data loading)
- [ ] **B24** Several GTFS feeds have expired, so their trains can no longer be found on the operators' sites. Last service date per feed (checked 2026-10-05, from `calendar.txt`/`calendar_dates.txt`): greece 2019-01-31, france 2025-04-22, hungary 2025-11-23, bosnia 2025-12-13, croatia 2025-12-14, luxembourg 2026-04-30, denmark 2026-05-06, germany 2026-06-13, poland 2026-06-13. Download current feeds; a check at load time that warns about expired feeds would catch this earlier.

## Bugs (algorithm)
- [ ] **B25** The search ignores `calendar.txt`/`calendar_dates.txt`: all trips of all days form one timetable, so a path can combine trains that never run on the same day (e.g. Köln → Westerland on DB 2310, which only runs on some dates in May/June 2026). Needs service days per trip and a travel date in the search.

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F15** Popup: show the train category passengers see (ICE, IC, Intercity…) from feeds that keep it in extra columns: Netherlands `trip_long_name` ("Intercity"), Poland `plk_category_code`. Feeds that already put it in `route_short_name` or `trip_short_name` ("Ex 42", "RJ 658") need nothing. Germany's DELFI feed has no category column; its DB Fernverkehr categories come from `train-categories.txt` (F16).
- [ ] **F14** Popup: show the days a train leg runs on (from the GTFS calendar). Depends on loading the calendar (B25).

## Structure
