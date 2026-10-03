# Coding Guidelines

Binding for every change. Existing code does not always follow them yet. New and changed lines must; don't mass-rewrite untouched code (log it in `TODO.md` instead).

## 1. Dependencies: closed list
| Area | Use | Not this |
|---|---|---|
| Backend | Java 17, Spring Boot (webmvc, actuator, cache), Lombok, Caffeine, Jackson, SLF4J via `@Slf4j` | Guava, Apache Commons, Gson, a second cache/JSON/logging lib |
| Tests | JUnit 5, AssertJ, `gtfs-mini` fixture | Mockito (unless there is truly no real object), Hamcrest, Spring context in unit tests |
| Frontend | Vanilla JS, Leaflet 1.9.4, Turf 6, from CDN | Frameworks, jQuery, npm/build step |

- Adding a dependency: **ask first**, then add a line in `decisions.md`. Try the JDK, Spring, and the existing deps first.
- `gtfs-lib` is only used for its `Stop`/`StopTime` POJOs and will be removed. Don't add new usages.

## 2. Reuse before you write
- **Search first.** Before adding a helper, grep for an existing one (`formatTime`, `parse`, `split(":")`, `fetch(` …).
- **Rule of two:** if you are about to write logic that already exists somewhere, extract it into one shared place and call it from both. Never paste a copy.
- Single homes for shared concerns (create them when you first need them, then migrate callers you touch):
  - GTFS time and durations (parse `HH:MM:SS`, format, minutes↔seconds, overnight wrap) → `com.railreach.util.GtfsTime`
  - Stop ID handling (canonical ID, extract source) → `com.railreach.util.StopIds`
  - Frontend backend URL → one `const API_BASE`; all calls go through it
- New logic goes into a new focused class. Don't grow `GtfsDataService` or `ReachabilityService`.

## 3. No bloat
- Build what the task asks for, nothing more. No speculative parameters, flags, config keys, single-implementation interfaces, abstract bases, factories, or "for later" code.
- **No dead code.** Delete unused methods, fields, imports, config keys and commented-out code. Git keeps the history.
- Keep the diff proportional to the task. Don't reformat or rename lines you didn't otherwise need to change.
- Comments say **why**, never what. No changelog comments ("was 0.5", "OPTIMIZED:", "NEW"). No banner comments over obvious sections.
- No special cases for specific data in production code, e.g. "log extra if the name contains Paris". Use a test or a DEBUG log instead.
- Size limits (soft): methods ≤ 40 lines, nesting ≤ 3 levels, classes ≤ 400 lines. Over the limit means extract.
- Add a private one-line wrapper only when it names a concept that is used more than once.

## 4. Java style
- 4-space indent, braces on the same line, ≤ 120 columns. One-line guards (`if (x == null) return;`, `continue;`) are fine. Otherwise always use braces.
- Imports: explicit, no wildcards, no inline fully-qualified names (`new java.util.concurrent.ConcurrentHashMap<>()`).
- Names carry units: `departureSeconds`, `durationMinutes`, `timeoutMs`, `radiusDeg`. Constants are `private static final UPPER_SNAKE`. No magic numbers: use a named constant or a `railreach.*` config key.
- Lombok:
  - DTOs/models: `@Data @Builder @NoArgsConstructor @AllArgsConstructor`
  - Services/controllers: `@Slf4j @RequiredArgsConstructor` with `private final` deps. Write the constructor by hand only when needed (e.g. `@Lazy`).
- Internal immutable value types (queue items, keys, results inside a service) are `record`s, not hand-written classes.
- Config: `@Value("${railreach.x.y:default}")`, always with a default, documented in `application.yml`.
- Collections: `List.of`/`Map.of`, `stream().toList()` (not `Collectors.toList()`) unless the result is mutated. Return empty collections, never `null`. New lookup methods return `Optional`.
- Errors: throw `IllegalArgumentException`/`IllegalStateException` (or a specific exception), not bare `RuntimeException`. Catch `Exception` only at boundaries (per-feed load loop, SSE worker) and always log it with the exception as the last argument.
- Logging: `{}` placeholders only. Wrap non-trivial formatting in `String.format` inside the argument. INFO for lifecycle and summaries, DEBUG for per-item detail, nothing at INFO inside BFS loops. No `System.out`, no `printStackTrace`.
- Concurrency: shared state in singletons uses concurrent collections. Don't create ad-hoc `Executors.*` in new code; use a Spring-managed executor bean.
- Don't call `@Async`/`@Cacheable` methods from inside the same class (the proxy is bypassed).

## 5. API and layering
- Controllers are thin: bind/validate → call one service → return a response. No business logic.
- New endpoints return typed DTOs (in `dto/`), not `Map<String, Object>`. Admin/stats maps that already exist may stay.
- Public `/api/**` shape changes need the user's approval (see CLAUDE.md).

## 6. Domain conventions
- Times are **int seconds since midnight** (may exceed 24 h per GTFS). Overnight wrap: a diff below −60 min gets +24 h. Do this in one place (`GtfsTime`).
- Stop IDs are canonical `<source>:<rawId>`, e.g. `germany:8011160`. The source comes from the zip name `gtfs_<source>.zip`.

## 7. Tests
- Every behaviour change or bug fix ships with a test. For known bugs, enable the `@Disabled` test first.
- Plain objects on `GtfsTestFixtures.load(tempDir, ...)`, with AssertJ assertions. Test names are behaviour sentences: `oneTransferReachesStationBehindTheBorder`.
- Need new data? Extend `gtfs-mini` and document it in its README. Don't create a parallel fixture.

## 8. Frontend (`frontend/index.html`)
- Stays one file and vanilla. Use `const` by default, `let` only when reassigned, never `var`. Use `async`/`await` with `fetch`. Declare functions with `function name() {}`.
- Reuse existing helpers (`fmtMins`, `showStatus`, `setLoadingState`, …) before writing new ones.
- Styling: add classes in the `<style>` block and use the existing `--` CSS variables. No new inline `style="…"` and no hardcoded colours.
- Escape GTFS/user text before inserting it via `innerHTML`, or use `textContent`.

## 9. Before you call a change done
1. `compile` and `test` pass (CLAUDE.md commands).
2. The diff has no unused code, commented-out code, stray logs, debug leftovers or unrelated reformatting.
3. Nothing you added duplicates existing logic (grep for it).
4. No new dependency without approval.
5. `TODO.md` / `decisions.md` updated if relevant.
