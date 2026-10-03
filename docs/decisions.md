# Decisions

Short log of notable choices, newest first. Format: date — decision — why.

- **2026-10-03 — Coding guidelines in `docs/CODING_GUIDELINES.md`, imported into CLAUDE.md via `@`.** The import loads them into every Claude session automatically. The dependency list is closed and shared logic has one named home, to stop style drift and copy-paste duplication (time helpers already exist 4×). Existing code is migrated as it is touched, not in one big rewrite.
- **2026-10-03 — Monorepo at `RailReach/`** with `backend/` and `frontend/`. Claude (and you) can see both sides of the API in one session.
- **2026-10-03 — All large data lives in `backend/data/` and is gitignored.** This covers GTFS zips, `gtfs-cache.json` (~4 GB) and `hub-cache.json` (~0.8 GB). It keeps the repo small and keeps tools from scanning multi-GB files. Download sources are versioned in `docs/gtfs-sources.txt`.
- **2026-10-03 — `dev` profile uses Austria + Hungary + Slovakia** (~8 MB of feeds in `data/gtfs-dev/`). It's small enough to start fast, and the three countries share border stations, so cross-border merging gets exercised. (Until the BOM bug in TODO.md is fixed, Austria loads 0 stops and only HU+SK are active: ~2,000 stations, startup ~6 s.)
- **2026-10-03 — Tests use a hand-written `gtfs-mini` fixture** (`src/test/resources/gtfs-mini/`), zipped on the fly by `GtfsTestFixtures`. Tests run in milliseconds and every expected value can be checked by hand. The Spring context test runs with profile `test`, which loads no data.
- **2026-10-03 — Known bugs are captured as `@Disabled` tests** with the reason in the annotation. Re-enable the test when fixing the bug.
- **2026-10-03 — Source files moved into folders matching their declared packages** (`controller`, `service`, `dto`, `model`, `config`). Before this, the files sat in one flat folder.
