# Hosting

How much server RailReach needs, measured on the full data. Re-measure when the data or the search changes a lot.

## Plan

- The server only answers searches. GTFS zips are parsed on the dev machine, and only `data/gtfs-cache.json` is uploaded.
- Updating data: parse locally, upload the cache, restart the app. The cache is read only at startup, so each update means ~50 s of downtime.
- The server needs the jar, `frontend/` and `data/gtfs-cache.json` (~650 MB). No zips, so a small disk is enough.
- Leave `RAILREACH_ADMIN_TOKEN` unset, so `/api/admin/**` answers 403 (no reload, cache delete or feed download from outside).
- Without zips, the feed update center logs all feeds as missing at startup and once a day. That is expected.

## Size

- **16 GB RAM, run with `-Xmx9g -XX:+UseCompactObjectHeaders`.** The process then uses ~10 GB, which leaves room for the OS. 32 GB is only needed for many searches at the same time.
- CPU: one 5-transfer search keeps a core busy for ~3–15 s. 8 shared vCores are fine for a small audience.
- Candidate (2026-10-08): netcup VPS Lite 3 (8 vCores, 16 GB, 320 GB SSD, €16.66/month incl. VAT, cancellable monthly). Hetzner's cheap cloud lines (CX, CAX) were sold out and its 32 GB plans had become much more expensive after 2026 DRAM price increases. Hetzner's Server Auction was the alternative for dedicated hardware.

## Measurements (2026-10-08)

Full data: 49 feeds, 34,081 stations, cache 649 MB. Windows 10 dev machine, 12 cores, Java 25, G1.

| Test | Result |
|---|---|
| Startup from cache | 48 s (re-measured 2026-10-09: reading the cache ~25 s, search timetables 21 s). Live heap after a full GC: **2.85 GB** |
| Raw result cache | ~25 MB per 5-transfer result, at most 30 entries (`raw-max-entries`), so ≤ ~0.75 GB |
| Full parse of all zips (only on the dev machine) | 6¼ min, peak working set 4.5 GB, at most 3.6 GB live after a GC |
| 15 hubs × 5 transfers, 3 at a time, `-Xmx12g` | OK, 106 s |
| same, `-Xmx6g` | OK, 57 s, but 16 full GCs: at the limit |
| same, `-Xmx5g` | OutOfMemoryError |
| same, `-Xmx4g` | OutOfMemoryError, app stuck in GC |

Rule of thumb: ~3 GB for the data (~1.7 GB since S16) plus ~1 GB per 5-transfer search running at the same time. Since S15 the search gate runs at most `search.gate.permits` (2) searches at once and queues up to 6 more, so peak heap is about the data plus 2 GB plus the result cache.

## Search gate test (S15, 2026-10-09)

Full data, `-Xmx9g`, G1, warmer on, gate at 2 permits and 6 queued. 5 clients at the same time, each streaming 6 searches over the 20 starts of `bench-stations.txt` (alternating 4 and 5 transfers), plus one client that closes its 5-transfer stream after 4 s.

| Measure | Result |
|---|---|
| Outcome | No OutOfMemoryError, all 30 streams ended with `done`, none turned away. 1.5 min in all |
| Searches | 22 by visitors, never more than 2 at once. Queue waits 0.5–44 s (median ~9 s); a stream's time includes its wait, up to 50 s |
| Warmer | 2 warmed with a free permit, 13 skipped because none was free |
| Closed client | Its stream stopped 1 s after the client left; nothing stored |
| Live heap after the run (`bench.sh memory`) | 3,243 MB, with 22 stored results in the raw cache |
| Peak working set | 9.5 GB: G1 grows the heap towards `-Xmx` |

## Journeys and result cache on the store (S19, 2026-10-10)
Full data, compact engine (`--railreach.search.engine=compact`), `-Xmx3g`, compact object headers, warmer off. Results are byte-identical to S18 (`SearchSnapshot`, gtfs-mini, dev and full).
| Measure | How | Result |
|---|---|---|
| Startup | log `Started … in` | 38–39 s at `-Xmx3g`: only the timetable store (13 s) is built, no `SearchTimetables` |
| Idle live heap | `bench.sh memory` | **1,542 MB** (S18: 1,880 MB) |
| One stored result | `bench.sh entry 5`, after a first search grew the scratch | **2 MB** (Wien, Basel, München; S16: 32 MB). The `Search stats (compact)` line logs 1.5–1.96 MB for the 20 full starts. On a fresh app the first `entry` reads 152 MB per result: that is the one ~444 MB scratch, kept for the next search |
| 4 concurrent 5-transfer searches, 4 permits | 4 streams at once (Stockholm, Bruxelles-Midi, København, Roma) | **OutOfMemoryError** in all four: 4 scratches of ~444 MB don't fit next to 1.54 GB. The streams then hang (B38). Target moved to S22 |
| The same 4 streams, 2 permits (default) | as above | All `done` in 8.7–13.2 s (2 of them queued), no OOM; live heap afterwards 2,334 MB (2 scratches, 4 results) |

## Compact search engine (S18, 2026-10-09)

The compact engine against the S16 baselines below, measured the same way with `--railreach.search.engine=compact` (warmer off, `-Xmx12g`, compact object headers). Results are byte-identical to the legacy engine (`SearchSnapshot`, gtfs-mini, dev and full), so the average station counts per transfer count match S16 exactly.

| Measure | How | Result |
|---|---|---|
| Startup | log `Started … in` | 57–58 s: the timetable store (14 s) is built next to the search timetables (18 s) |
| Idle live heap | `bench.sh memory` | 1,880 MB: S16 plus the 184 MB store |
| Held during a search | `bench.sh held`, 5 transfers | Stockholm on a fresh app: **646 MB** (legacy 896 MB). With a scratch that an earlier search already grew: 130 MB München, 133 MB Basel |
| Scratch per search | the `Search stats (compact)` log line | Up to ~445 MB on the largest searches (15–17 M stop arrivals): labels 274 MB, trip progress 118 MB. Kept for the next search, one per gate permit |

The gate of ≤ 200 MB per search is not met. Rounds 3–5 of a large search each make 5–6 M arrivals, and the next round boards from all of them; with up to 22 M boardings per search on top, the round structure the semantics contract fixes keeps the peak above ~300 MB even with smaller labels. See `decisions.md`.

Search time (`bench.sh times full`; the second run after riding started from the parallel remap, and after midnight, so its station counts are a day later):

| Transfers | Stream p50 | Stream p95 | S16 p95 | Share of S16 p95 |
|---|---|---|---|---|
| 0 | 7–8 ms | 26–27 ms | 38 ms | 70 % |
| 1 | 50–64 ms | 117–121 ms | 327 ms | 37 % |
| 2 | 183–246 ms | 565–571 ms | 1.2 s | 47 % |
| 3 | 625–755 ms | 1.43–1.47 s | 3.0 s | 48–49 % |
| 4 | 1.68–1.71 s | 2.91–3.02 s | 5.8 s | 50–52 % |
| 5 | 2.71–2.91 s | 4.14–4.50 s | 9.0 s | 46–50 % |

Output (the cached replay) is unchanged at ~100–130 ms for 4–5 transfers.

## Baselines (S16, 2026-10-09)

The numbers the later items of the compact search store (S17–S21) are measured against. Taken like the S13 baselines below, but with one String per station ID (S16) and `-XX:+UseCompactObjectHeaders`. Results are byte-identical to S13 (`SearchSnapshot`, dev and full).

| Measure | How | Result |
|---|---|---|
| Startup | log `Started … in` | 45–49 s: search timetables 19 s |
| Startup peak | `gc.log` (largest heap before a GC), `bench.sh memory` (peak working set) | heap 5.0 GB used, 5.4 GB committed; working set 5.1 GB |
| Idle live heap | `bench.sh memory` | **1,693–1,699 MB** (−39 % from 2,770; class histogram: 1,689 MB in 49.8 M objects) |
| Largest idle classes | `GC.class_histogram` | `Connection` 368 MB (11.5 M), `HashMap$Node` 302 MB, `Object[]` 162 MB, `byte[]` 155 MB, `int[]` 145 MB, `String` 113 MB (4.7 M), `HashMap$Node[]` 99 MB, `TripTimetable$Departure` 86 MB |
| Allocated per search | `bench.sh alloc` | 5 transfers: 5.4–6.3 GB Wien, 7.5–7.7 GB Bruxelles-Midi (S13: 4.9 GB, not explained); 3 transfers: 0.74 GB Brennero |
| Held during a search | `bench.sh held` | 5 transfers: 329 MB München, 610 MB Basel, 896 MB Stockholm. Sampled seconds apart, so it varies a lot between runs |
| One stored result | `bench.sh entry` on a fresh start | 5 transfers: **32 MB** (Paris, Zürich, Praha). After other searches the same command gave 2–27 MB: the live heap before it still held something of the earlier searches |

Search time per transfer count, measured as in S13:

| Transfers | Stream p50 | Stream p95 | Output p50 | Output p95 | Output share | Stations (avg) |
|---|---|---|---|---|---|---|
| 0 | 11 ms | 38 ms | 1 ms | 3 ms | 10 % | 244 |
| 1 | 87 ms | 327 ms | 9 ms | 38 ms | 13 % | 3,085 |
| 2 | 468 ms | 1.2 s | 50 ms | 76 ms | 9 % | 9,902 |
| 3 | 1.4 s | 3.0 s | 84 ms | 110 ms | 5 % | 16,663 |
| 4 | 3.6 s | 5.8 s | 108 ms | 128 ms | 3 % | 21,317 |
| 5 | 5.8 s | 9.0 s | 109 ms | 125 ms | 2 % | 24,476 |

## Baselines (S13, 2026-10-09)

The numbers before the compact search store; S16 replaces them as the reference. Full data as above, same machine, `-Xmx12g`, G1, no compact object headers, warmer off (`--railreach.cache.predictive-enabled=false`), one search at a time. Repeat with `scripts/bench.sh` (its header has the start command):

| Measure | How | Result |
|---|---|---|
| Startup | log `Started … in` | 48–49 s: reading the cache ~25 s, building the search timetables 21 s |
| Startup peak | `gc.log` (largest heap before a GC), `bench.sh memory` (peak working set) | heap 5.4 GB used, 5.9 GB committed; working set 5.9 GB |
| Idle live heap | `bench.sh memory` (`GC.run` + `GC.heap_info`) | **2,770 MB** (class histogram: 2,685 MB in 74.8 M objects) |
| Largest idle classes | `GC.class_histogram` | `byte[]` 659 MB, `Connection` 438 MB (11.5 M), `String` 393 MB (17.2 M), `HashMap$Node` 385 MB, `Object[]` 158 MB, `int[]` 147 MB, `TripTimetable$Departure` 123 MB |
| Allocated per search | `bench.sh alloc` (JFR `settings=profile`, thread allocation totals) | 5 transfers: 6.6 GB Wien, 4.9 GB Bruxelles-Midi; 3 transfers: 0.76 GB Brennero |
| Held during a search | `bench.sh held` (class histogram every ~2 s, peak minus before) | 5 transfers: 585 MB München, 707 MB Basel, 689 MB Stockholm. The samples are seconds apart, so the true peak can be higher |
| One stored result | `bench.sh entry` (live heap growth per raw cache entry) | 5 transfers: **29 MB** (average of Paris, Zürich, Praha) |
| Working set after searches | `bench.sh memory` after 7 searches | 9.6 GB: G1 grows the heap towards `-Xmx` |

Search time per transfer count (`bench.sh times full`: the 20 starts of `bench-stations.txt`, each 0 → 5 transfers, after a warm-up search). "Stream" is the time to the `done` event of a fresh search; it includes sending each round's stations. "Output" is the same stream again from the result cache, so only building the summaries and writing the SSE events:

| Transfers | Stream p50 | Stream p95 | Output p50 | Output p95 | Output share | Stations (avg) |
|---|---|---|---|---|---|---|
| 0 | 12 ms | 46 ms | 2 ms | 4 ms | 16 % | 244 |
| 1 | 93 ms | 436 ms | 11 ms | 35 ms | 13 % | 3,085 |
| 2 | 465 ms | 1.5 s | 50 ms | 102 ms | 12 % | 9,902 |
| 3 | 1.7 s | 5.2 s | 82 ms | 116 ms | 4 % | 16,663 |
| 4 | 4.0 s | 7.7 s | 107 ms | 137 ms | 3 % | 21,317 |
| 5 | 6.5 s | 10.3 s | 119 ms | 188 ms | 2 % | 24,476 |

Output share is the sum of output times over the sum of stream times. It stays far under 30 %, so the SSE output needs no separate work; the search is the cost.
