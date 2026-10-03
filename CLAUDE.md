# RailReach

Spring Boot backend + single-file Leaflet frontend. Given a start station and a max number of transfers, it shows every European rail station you can reach.
GTFS feeds → merged rail graph (cross-border station dedup) → transfer-bounded trip BFS → results streamed to the map via SSE.
Details: `docs/ARCHITECTURE.md`. Read it only when the task needs more than this file.

## Layout
- `backend/src/main/java/com/railreach/`
  - `controller/`: `/api` public API and `/api/admin` endpoints
  - `service/`: `GtfsDataService` (load, dedup, graph), `ReachabilityService` (BFS, caching, SSE), `HubRoutingService`
  - `dto/`, `model/`, `config/`
- `backend/src/test/`: unit tests on the `gtfs-mini` fixture (see `src/test/resources/gtfs-mini/README.md`)
- `frontend/index.html`: the whole UI in one file (~1600 lines). Grep for the function you need instead of reading all of it.
- `docs/TODO.md` (backlog + known bugs), `docs/decisions.md` (why things are the way they are)
- `scripts/api.sh`: curl shortcuts (`scripts/api.sh help`)

## Commands (run from repo root)
- Compile: `mvn -q -f backend/pom.xml compile`
- Test: `mvn -q -f backend/pom.xml test` (~10 s; prints nothing on success)
- One test class: `mvn -q -f backend/pom.xml test -Dtest=ReachabilityServiceTest`
- Run with small dev data: use the `/run-dev` skill. Then use `scripts/api.sh health`, `scripts/api.sh search wien`, etc.
- Full data (12 GB heap, minutes to start): only when explicitly asked. See ARCHITECTURE.md.

## Hard rules
- **Never read, grep or cat anything in `backend/data/`**. It holds multi-GB caches and zips. Use `ls -la` for sizes only.
- Never call `reload-gtfs`, `compute-hubs` or the cache-delete endpoints against the full dataset unless asked.
- A change is done only when `compile` and `test` pass. Say so explicitly if you could not run them.
- Fixing a known bug: enable its `@Disabled` test first (or write a failing test), then fix.
- Ask before changing the public API (`/api/**` request/response shapes). The frontend depends on it.

## Code conventions
- Lombok: DTOs/models use `@Data @Builder @NoArgsConstructor @AllArgsConstructor`. Services use constructor injection and `@Slf4j`.
- Log with `{}` placeholders only (not `{:.2f}`).
- Times are **int seconds since midnight** (may exceed 24h per GTFS). Overnight wrap: a diff below −60 min gets +24 h.
- Stop IDs are canonical `<source>:<rawId>`, e.g. `germany:8011160`. The source comes from the zip name `gtfs_<source>.zip`.
- Config lives under `railreach.*` in `application.yml`. Inject with `@Value` and a default.
- Tests: JUnit 5 + AssertJ, plain objects (no Spring context) via `GtfsTestFixtures.load(tempDir, "alpha", "beta")`.
- Keep new classes focused. The two big services are slated for splitting (see TODO.md), so don't grow them further.

## Workflow
- Small, focused changes. Plan first when a change spans several files.
- Update `docs/TODO.md` when you finish an item or discover an issue. Add a line to `docs/decisions.md` for non-obvious choices.
- Commit after each completed task. Use short imperative messages, and don't commit `backend/data/`.
