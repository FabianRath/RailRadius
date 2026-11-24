# RailRadius

Spring Boot backend + vanilla MapLibre frontend (served by the backend at `http://localhost:8080/`). Given a start station and a max number of transfers, it shows every European rail station you can reach.
GTFS feeds → merged rail graph (cross-border station dedup) → transfer-bounded trip BFS → results streamed to the map via SSE.
Details: `docs/ARCHITECTURE.md`. Read it only when the task needs more than this file.

## Layout
- `backend/src/main/java/com/railradius/`
  - `controller/`: `/api` public API and `/api/admin` endpoints
  - `service/`: `GtfsDataService` (startup/reload) → `GtfsFeedLoader` and `StationDeduplicator` (into a load-time `RailNetwork`), `BinaryCacheStore`; `TransitGraph` (what is served: stations + `TimetableStore`), `StationSearchIndex`, `ReachabilityService` (orchestrator, SSE) → `ReachabilityCache` → `CompactReachabilitySearch` (round-based search), `ReachabilityWarmer`, `JourneyLegBuilder`
  - `dto/`, `model/`, `config/`
- `backend/src/test/`: unit tests on the `gtfs-mini` fixture (see `src/test/resources/gtfs-mini/README.md`)
- `frontend/`: `index.html` (markup), `css/app.css`, `js/*.js` (one ES module per concern, entry `main.js`). See guidelines §8.
- Backlog and known bugs: GitHub Issues, read and written with `gh` (guidelines §9)
- `docs/decisions.md` (why things are the way they are), `docs/HOSTING.md` (server size, memory measurements)
- `scripts/api.sh`: curl shortcuts (`scripts/api.sh help`); `scripts/bench.sh`: memory and speed baselines (`docs/HOSTING.md`); `scripts/probe.sh`: abuse probe (hostile requests, expected statuses)

## Environment: Windows, not Linux
- Dev machine is Windows 10. Claude's Bash tool is Git Bash; the user's own terminal is PowerShell 5.1.
- Every shell command in docs, skills and scripts must work in Git Bash on Windows. No Linux-only tools or paths (`lsof`, `pkill`, `ps aux`, `nohup`, `xdg-open`, `/tmp`, `/proc`). Use instead:
  - ports/processes: `netstat -ano | grep :8080`, then `taskkill //PID <pid> //F` (double slashes in Git Bash)
  - temp files: the session scratchpad
- Commands the user should type themselves: give PowerShell syntax (`.\mvnw.cmd`, `$env:X = "…"`, `;` instead of `&&`).

## Commands (run from repo root)
- Compile: `mvn -q -f backend/pom.xml compile`
- Test: `mvn -q -f backend/pom.xml test` (~10 s; prints nothing on success)
- One test class: `mvn -q -f backend/pom.xml test -Dtest=ReachabilityServiceTest`
- "Unresolved compilation problem", `ClassFormatError` or a bean that fails to resolve while the source is fine: stale classes, because VS Code's Java compiler also writes into `backend/target/classes`. Run `mvn -q -f backend/pom.xml clean test` before debugging.
- Run with small dev data: use the `/run-dev` skill. Then use `scripts/api.sh health`, `scripts/api.sh search wien`, etc.
- Full data (3 GB heap, ~3 s to start from the binary cache): only when explicitly asked. See ARCHITECTURE.md.

## Hard rules
- **`backend/data/` holds multi-GB caches and zips. Look, don't read.**
  - OK (small, bounded output): `ls -la`, `du -sh`, `find -maxdepth`, `unzip -l <zip>` (list GTFS files), peeks capped by `head` (`unzip -p <zip> stops.txt | head -n 20`, `head -c 2000 gtfs-dev-cache.json`), `grep -m 5 …`, `wc -l`. Small text files (e.g. `gtfs/list.txt`) may be read normally.
  - Never: the Read tool (denied in settings), `cat`, `grep`/`jq`/`unzip -p` without a cap, or anything whose output scales with file size. Cache JSON can be one huge line, so cap by bytes (`head -c`), not lines.
  - The app reads only the binary `gtfs-store.bin` / `gtfs-dev-store.bin` (S21). The `*-cache.json` files are kept only for rolling back to the `pre-compact` tag.
- Never call `reload-gtfs` or the cache-delete endpoint against the full dataset unless asked.
- A change is done only when `compile` and `test` pass. Say so explicitly if you could not run them.
- Fixing a known bug: enable its `@Disabled` test first (or write a failing test), then fix.
- Ask before changing the public API (`/api/**` request/response shapes). The frontend depends on it.

## Coding guidelines
All code changes must follow these. They cover allowed libraries, style, reuse and anti-bloat rules, and the pre-done checklist.

@docs/CODING_GUIDELINES.md

## Workflow
- Small, focused changes. Plan first when a change spans several files.
- Keep `docs/ARCHITECTURE.md` in sync **in the same commit** as the code change. Update it when you add, remove, rename or re-scope a class or frontend module; change an endpoint, SSE event, DTO/model field, config key or default; change the data pipeline, cache file format, BFS/caching behaviour, or a documented constant (radii, batch sizes, thresholds); or change the directory layout or run commands. Before editing a section, check its claims against the code: the doc must describe the code as it is, not as planned. Pure internal refactors that touch none of these need no update.
- Close an item's issue from its commit (`Fixes #12`). Ask the user before opening an issue for a newly found bug (guidelines §9). Add a line to `docs/decisions.md` for non-obvious choices.
- Commit after each completed task. Use short imperative messages, and don't commit `backend/data/`.
