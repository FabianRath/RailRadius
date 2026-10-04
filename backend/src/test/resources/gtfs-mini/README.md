# gtfs-mini test fixture

Two tiny feeds. `GtfsTestFixtures` zips each folder to `gtfs_<name>.zip`, where the name is the folder name up to the first `-`.

- **alpha**: A1 Alpha Central, A2/A2b Alpha North (duplicate stop, merged per feed), A3 Border Town, A4 Alpha West, A9 bus stop (bus route, filtered out), AP parent station of A1 and AE its entrance (no rail trip uses either, so neither becomes a station)
  - T1 RE1: A1 08:00 → A2b 08:30 → A3 09:00
  - T4 NJ: A4 22:50 → A3 23:30
  - T2 bus: A1 → A9 (not rail, ignored)
- **beta** (every file starts with a UTF-8 BOM, like the Austrian feed): B1 Border Town Station (merged with A3 across borders), B2 Beta City
  - U1 IC5: B1 09:20 → B2 10:00
  - U2 IC5: B1 12:30 → B2 13:10
  - U3 EN: B1 00:10 → B2 01:10 (overnight connection from T4)
- **alpha-fast-train**: alpha plus T6 NJ A1 21:00 → A3 21:30. Used to show the known pruning issue (see docs/TODO.md).
