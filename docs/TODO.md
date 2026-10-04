# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B23 · F11 · S12

## Bugs (data loading)
- [ ] **S8** Feeds that failed to load: `data/gtfs/broken/` (finland, lithuania). Diagnosed 2026-10-04: both parse fine but contain no rail routes. `gtfs_finnland.zip` has route types 1104 (384, air) and 3 (798, bus); `gtfs_lithuania.zip` has 3 (3,039, bus), 4 (33, ferry) and 11 (31, trolleybus). Fix: download the rail operators' feeds instead (Finland: VR, e.g. via Fintraffic/Digitraffic; Lithuania: LTG Link) and update `docs/gtfs-sources.txt`; the zip for Finland should then be named `gtfs_finland.zip`.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F1** Rework station naming. I would like to see the coutry code displayed in some way (DE, NL...)
- [ ] **F4** Zoom level after calculation (frontend) The zoom level should be adjusted so that all rendered stations on the map are visible.


## Structure
