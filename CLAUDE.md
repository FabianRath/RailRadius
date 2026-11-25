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
- `browser-engine/`: the search compiled to WebAssembly with TeaVM, its browser stand-ins, and the check that it finds what the server finds (#10). Its build writes the engine to the gitignored `frontend/engine/`, where the page runs it (#12)
- `frontend/`: `index.html` (markup), `css/app.css`, `js/*.js` (one ES module per concern, entry `main.js`), `test/` (tests of the DOM-free modules). See guidelines §8.
- Backlog and known bugs: GitHub Issues, read and written with `gh` (guidelines §9)
- `docs/decisions.md` (why things are the way they are), `docs/HOSTING.md` (server size, memory measurements)
- `scripts/api.sh`: curl shortcuts (`scripts/api.sh help`); `scripts/bench.sh`: memory and speed baselines, of the server and of the browser engine in Node, and the full-data check of `perf` (`docs/HOSTING.md`); `scripts/probe.sh`: abuse probe (hostile requests, expected statuses)

## Environment: Windows first, also Linux cloud sessions
- Dev machine is Windows 10. Claude's Bash tool is Git Bash; the user's own terminal is PowerShell 5.1.
- Claude Code cloud sessions (claude.ai/code) run in a Linux container instead (#21): its setup script installs JDK 25, its proxy allows the map tiles but blocks the feed hosts, so `/run-dev` falls back to the offline `mini` profile (gtfs-mini test feeds). `uname -s` says which one you are in.
- Every shell command in docs, skills and scripts must work in Git Bash on Windows (and so also on Linux). No Linux-only tools or paths (`lsof`, `pkill`, `ps aux`, `nohup`, `xdg-open`, `/tmp`, `/proc`). Use instead:
  - ports/processes: stop the background task first; if the port stays busy, on Windows `netstat -ano | grep :8080`, then `taskkill //PID <pid> //F` (double slashes in Git Bash); on Linux `fuser -k 8080/tcp`
  - temp files: the session scratchpad
- Commands the user should type themselves: give PowerShell syntax (`.\mvnw.cmd`, `$env:X = "…"`, `;` instead of `&&`).

## Commands (run from repo root)
- Compile: `mvn -q -f backend/pom.xml compile`
- Test: `mvn -q -f backend/pom.xml test` (~10 s; prints nothing on success)
- One test class: `mvn -q -f backend/pom.xml test -Dtest=ReachabilityServiceTest`
- Frontend tests: `node --test "frontend/test/*.test.js"` (Node's built-in runner, no dependency; < 1 s)
- Browser engine check (after the backend's `test`; TeaVM build plus Node): `mvn -q -f browser-engine/pom.xml verify`. Run it when the search, `BrowserDataFile` or `SearchSnapshot` changes.
- "Unresolved compilation problem", `ClassFormatError` or a bean that fails to resolve while the source is fine: stale classes, because VS Code's Java compiler also writes into `backend/target/classes`. Run `mvn -q -f backend/pom.xml clean test` before debugging.
- Run with small dev data: use the `/run-dev` skill (dev feeds if `backend/data/gtfs-dev/` has them, else the offline `mini` profile from `scripts/mini-data.sh`). Then use `scripts/api.sh health`, `scripts/api.sh search wien`, etc.
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
- Changes reach `main` only through a pull request whose `test` check passed (ruleset on `main`, `.github/workflows/tests.yml`). Open that pull request yourself when a task is done, without being asked. Merge your own pull request yourself, with a merge commit, once `test` is green on its latest commit, then tell the user. Wait for the user's OK instead when it changes the public API (`/api/**`) or a feed's source or licence. Never merge around a red check: fix it.

## Agent skills

### Issue tracker

GitHub Issues on FabianRath/RailRadius via `gh`, under the rules in guidelines §9. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), next to the type labels `bug` / `feature` / `structure`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` (created lazily) and `docs/decisions.md` in place of ADRs. See `docs/agents/domain.md`.
