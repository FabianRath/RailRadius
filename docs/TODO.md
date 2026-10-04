# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B22 · F7 · S12

## Bugs (data loading)
- [ ] **S8** Feeds that failed to load: `data/gtfs/broken/` (finland, lithuania). Diagnosed 2026-10-04: both parse fine but contain no rail routes. `gtfs_finnland.zip` has route types 1104 (384, air) and 3 (798, bus); `gtfs_lithuania.zip` has 3 (3,039, bus), 4 (33, ferry) and 11 (31, trolleybus). Fix: download the rail operators' feeds instead (Finland: VR, e.g. via Fintraffic/Digitraffic; Lithuania: LTG Link) and update `docs/gtfs-sources.txt`; the zip for Finland should then be named `gtfs_finland.zip`.

## Bugs (algorithm)
- [ ] **B6** Route-type filter is `2 || 100–106`, but the comment says 107/109 are included. Decide and align. Impact: 109 is suburban railway (S-Bahn), and 3,571 stops are served only by it (Germany 3,260, Finland 238, Denmark 73). S-Bahn trips through stations that are in the graph are dropped too, so a large part of the regional network is missing. Including it adds many short, frequent trips (memory, BFS time); measure after B18.

## Bugs (caching / infrastructure)

## Features / UX
- [ ] **F1** Rework station naming: map lat/long to OpenStreetMap station names and use that name for grouping as well.
- [ ] **F3** Max journey time has to be entered repeatedly (frontend).
- [ ] **F4** Zoom level after calculation (frontend).
- [ ] **F5** `/api/admin/debug/stations?includeIsolated=true` is referenced in old notes but not implemented.
- [ ] **F6** Add authentification to acces the site with something like https://clerk.com (Seperate dev accounts and user accounts with different acces)


## Structure
