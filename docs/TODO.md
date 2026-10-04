# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B23 · F10 · S12

## Bugs (data loading)
- [ ] **S8** Feeds that failed to load: `data/gtfs/broken/` (finland, lithuania). Diagnosed 2026-10-04: both parse fine but contain no rail routes. `gtfs_finnland.zip` has route types 1104 (384, air) and 3 (798, bus); `gtfs_lithuania.zip` has 3 (3,039, bus), 4 (33, ferry) and 11 (31, trolleybus). Fix: download the rail operators' feeds instead (Finland: VR, e.g. via Fintraffic/Digitraffic; Lithuania: LTG Link) and update `docs/gtfs-sources.txt`; the zip for Finland should then be named `gtfs_finland.zip`.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F1** Rework station naming. I would like to see the coutry code displayed in some way (DE, NL...)
- [ ] **F4** Zoom level after calculation (frontend) The zoom level should be adjusted so that all rendered stations on the map are visible.
- [ ] **F5** `/api/admin/debug/stations?includeIsolated=true` is referenced in old notes but not implemented.
- [ ] **F6** Add authentification to acces the site with something like https://clerk.com (Seperate dev accounts and user accounts with different acces)
- [ ] **F7** We currently display the amount of reachable stations under our Calcualte Reachbility button, the same label also shows our selected station when we have not clicked on calculate yet. This label is redundant as we already display the amount of reachable stations under Results just below that.
- [ ] **F8** Lets make our "side panel" in which the user enters the station etc. something that can be toggled to disapear into a button. That way different screen resulutions can still see the map.


## Structure
