# gtfs-mini test fixture

Tiny feeds. `GtfsTestFixtures` zips each folder to `gtfs_<name>.zip`, where the name is the folder name up to the first `-`.

- **alpha**: A1 Alpha Central, A2/A2b Alpha North (duplicate stop, merged per feed), A3 Border Town, A4 Alpha West, A9 bus stop (bus route, filtered out), AP parent station of A1 and AE its entrance (no rail trip uses either, so neither becomes a station)
  - T1 RE1: A1 08:00 → A2b 08:30 → A3 09:00
  - T4 NJ: A4 22:50 → A3 23:30
  - T2 bus: A1 → A9 (not rail, ignored)
- **beta** (every file starts with a UTF-8 BOM, like the Austrian feed; the U1 and U2 rows of stop_times.txt are interleaved, which GTFS allows): B1 Border Town Station (merged with A3 across borders), B2 Beta City
  - U1 IC5: B1 09:20 → B2 10:00
  - U2 IC5: B1 12:30 → B2 13:10
  - U3 EN: B1 00:10 → B2 01:10 (overnight connection from T4)
- **gamma** + **delta** (only loaded together, by the merge-chain and shared-train tests): gamma GX/G2 Chain Junction (~190 m apart: too far for the per-feed stop merge, close enough for the within-country dedup, so G2 → GX), G3 Gamma Start, G4 Gamma End, G5 Gamma Border, G6 Gamma Sidings, G7 Twin Halt; delta D1 Chain Junction Hbf (next to GX, better connected, so GX → D1 across the border), D2 Delta North, D3 Delta South, D4 Delta Grens (~330 m from G5), D5 Delta Yard (~330 m from G6), D6 Twin Halt (next to G7, merged by name). No name of G5/D4 or G6/D5 shares a word. The ID `GX` is picked so the HashMap merges GX before G2, the order that used to strand G2's trip on the removed GX.
  - V1 R7: G3 08:00 → GX 08:30 → G4 09:00
  - V2 R7: G3 10:00 → G2 10:30
  - V3/V4/V5 R7: G5 11:00/12:00/13:00 → G7; V6/V7/V8 R7: G6 14:00/15:00/16:00 → G7
  - W1 IC9: D1 08:00 → D2 08:40; W2 IC9: D1 09:00 → D3 09:40
  - W3/W4/W5 IC9: D4 11:00/12:00/13:00 → D6, the same trains as V3–V5, so G5 and D4 merge (B22 test)
  - W6/W7/W8 IC9: D5 14:05/15:05/16:05 → D6, 5 min off V6–V8, so G6 and D5 stay apart
- **kappa** + **lambda** (only loaded together, by the foreign-stop tests). `borders.geojson` puts made-up country KA at lon 30–31 and LA at lon 31–32 (lat 50–51); every other feed lies outside both. kappa: K1 Kappa Central, K2 Kappa East, K5 Kappa North (in KA, so kappa's home is KA), K3 Lambda Gate and K4 Lambda Field (in LA); lambda: L1 Lambdastadt Ost (~700 m from K3), L2 Lambdastadt. K3 merges into L1; K4 has no lambda station within 1 km and stays.
  - KT1 RE2: K1 08:00 → K2 08:30 → K3 09:00; KT2 RE2: K5 10:00 → K4 11:00
  - LT1 R3: L1 09:30 → L2 10:00
- **alpha-fast-train**: alpha plus T6 NJ A1 21:00 → A3 21:30. The faster train reaches the border later and misses U1 (B2 test).
- **epsilon** (loaded alone): E1 Epsilon Start, E2 Epsilon Fork, E3 Epsilon Junction, E4 Epsilon East, E5 Epsilon Detour, E6 Epsilon End, E7 Epsilon Suburb; route EX (type 2) and S-Bahn route S7 (type 109)
  - Y1: E1 08:00 → E3 08:40; Y2: E1 08:50 → E3 08:58; Y3: E3 09:00 → E4 09:30. The faster Y2 leaves only 2 min to catch Y3, the slower Y1 20 min (B3 test)
  - X1: E1 10:00 → E2 10:30; X2: E2 10:40 → E5 11:00; X3: E5 11:10 → E6 11:30; X4: E2 22:00 → E6 22:30. E6 takes 1 transfer with an 11.5 h wait, or 2 transfers in 90 min (B4 test)
  - S1 S7: E4 09:40 → E7 09:50. E7 is served only by the S-Bahn (B6 test)
