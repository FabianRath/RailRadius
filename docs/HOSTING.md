# Hosting

How much server RailReach needs, measured on the full data. Re-measure when the data or the search changes a lot.

## Plan

- The server only answers searches. GTFS zips are parsed on the dev machine, and only `data/gtfs-cache.json` is uploaded.
- Updating data: parse locally, upload the cache, restart the app. The cache is read only at startup, so each update means ~20–30 s of downtime.
- The server needs the jar, `frontend/` and `data/gtfs-cache.json` (~650 MB). No zips, so a small disk is enough.
- Leave `RAILREACH_ADMIN_TOKEN` unset, so `/api/admin/**` answers 403 (no reload, cache delete or feed download from outside).
- Without zips, the feed update center logs all feeds as missing at startup and once a day. That is expected.

## Size

- **16 GB RAM, run with `-Xmx9g`.** The process then uses ~10 GB, which leaves room for the OS. 32 GB is only needed for many searches at the same time.
- CPU: one 5-transfer search keeps a core busy for ~3–15 s. 8 shared vCores are fine for a small audience.
- Candidate (2026-10-08): netcup VPS Lite 3 (8 vCores, 16 GB, 320 GB SSD, €16.66/month incl. VAT, cancellable monthly). Hetzner's cheap cloud lines (CX, CAX) were sold out and its 32 GB plans had become much more expensive after 2026 DRAM price increases. Hetzner's Server Auction was the alternative for dedicated hardware.

## Measurements (2026-10-08)

Full data: 49 feeds, 34,081 stations, cache 649 MB. Windows 10 dev machine, 12 cores, Java 25, G1.

| Test | Result |
|---|---|
| Startup from cache | 18 s. Live heap after a full GC: **2.85 GB** |
| Raw result cache | ~25 MB per 5-transfer result, at most 30 entries (`raw-max-entries`), so ≤ ~0.75 GB |
| Full parse of all zips (only on the dev machine) | 6¼ min, peak working set 4.5 GB, at most 3.6 GB live after a GC |
| 15 hubs × 5 transfers, 3 at a time, `-Xmx12g` | OK, 106 s |
| same, `-Xmx6g` | OK, 57 s, but 16 full GCs: at the limit |
| same, `-Xmx5g` | OutOfMemoryError |
| same, `-Xmx4g` | OutOfMemoryError, app stuck in GC |

Rule of thumb: ~3 GB for the data plus ~1 GB per 5-transfer search running at the same time. Nothing limits concurrent searches, so enough of them at once can run out of heap on any size.
