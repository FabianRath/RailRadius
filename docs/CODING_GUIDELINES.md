# Coding Guidelines

Binding for every change. Existing code does not always follow them yet. New and changed lines must; don't mass-rewrite untouched code (propose an issue for it instead, §9).

## 1. Dependencies: closed list
| Area | Use | Not this |
|---|---|---|
| Backend | Java 25, Spring Boot (webmvc, actuator), Lombok, Caffeine, Jackson, SLF4J via `@Slf4j` | Guava, Apache Commons, Gson, a second cache/JSON/logging lib |
| Tests | JUnit 5, AssertJ, `gtfs-mini` fixture; frontend: Node's built-in `node:test` and `node:assert` | Mockito (unless there is truly no real object), Hamcrest, Spring context in unit tests |
| Frontend | Vanilla JS (native ES modules), MapLibre GL JS 6, copied into `frontend/vendor/`; OpenFreeMap tiles | Frameworks, jQuery, Leaflet, npm/build step, scripts/styles/fonts from a CDN, map tiles that need a key or forbid commercial use |

- Adding a dependency: **ask first**, then add a line in `decisions.md`. Try the JDK, Spring, and the existing deps first.

## 2. Reuse before you write
- **Search first.** Before adding a helper, grep for an existing one (`formatTime`, `parse`, `split(":")`, `fetch(` …).
- **Rule of two:** if you are about to write logic that already exists somewhere, extract it into one shared place and call it from both. Never paste a copy.
- Single homes for shared concerns (create them when you first need them, then migrate callers you touch):
  - GTFS time and durations (parse `HH:MM:SS`, format, minutes↔seconds, overnight wrap) → `com.railradius.util.GtfsTime`
  - Stop ID handling (canonical ID, extract source) → `com.railradius.util.StopIds`
  - Frontend backend URL → `API_BASE` in `js/config.js`; all calls go through `js/api.js`
- New logic goes into a new focused class. Don't grow `TransitGraph` or `ReachabilityService` into catch-alls.

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
- Names carry units: `departureSeconds`, `durationMinutes`, `timeoutMs`, `radiusDeg`. Constants are `private static final UPPER_SNAKE`. No magic numbers: use a named constant or a `railradius.*` config key.
- Lombok:
  - DTOs/models: `@Data @Builder @NoArgsConstructor @AllArgsConstructor`
  - Services/controllers: `@Slf4j @RequiredArgsConstructor` with `private final` deps. Write the constructor by hand only when needed (e.g. `@Lazy`).
- Internal immutable value types (queue items, keys, results inside a service) are `record`s, not hand-written classes.
- Config: `@Value("${railradius.x.y:default}")`, always with a default, documented in `application.yml`.
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

## 8. Frontend (`frontend/`)
- Vanilla, no build step. `index.html` holds markup only, `css/tokens.css` the design tokens and fonts, `css/app.css` all other styles, `js/` one ES module per concern (`api`, `map`, `search`, `query`, `query-form`, `reachability`, `browser-engine`, `engine-worker`, `engine-rules`, `map-labels`, `station-details`, `journey`, `debug`, `dev-auth`, `admin-panel`, `feeds`, `feed-alerts`, `about`, `theme`, `theme-rules`, `country-bounds`, `deep-link`, `intro-hint`, `server-status`, `icons`, `util`, `config`; `main.js` wires them up). New logic goes into the module it belongs to, or a new one.
- Icons: no emojis. Use `icon(name)` from `js/icons.js` (in markup: `<span data-icon="name"></span>`). A missing icon is copied from Lucide into that file.
- Only `js/map.js` talks to MapLibre. Other modules call its functions and never touch the map object.
- Use `const` by default, `let` only when reassigned, never `var`. Use `async`/`await` with `fetch`. Declare functions with `function name() {}`.
- Reuse existing helpers (`fmtMins`, `escapeHtml`, `showStatus`, `cssVar`, …) before writing new ones.
- Styling: add classes to `css/app.css` and use the `--` CSS variables. No inline `style="…"` and no hardcoded colours, also not in JS: map colours are CSS variables read with `cssVar()`. Colour literals live only in `css/tokens.css`; this must print nothing (the hex part also matches id selectors that look like hex, such as `#add`, so check any hit by eye):
  `` grep -rnE "(^|[:(, '\"\`])#[0-9a-fA-F]{3,8}\b|(rgba?|hsla?)\(\s*[0-9.]" frontend/css frontend/js --include=*.css --include=*.js | grep -v "css/tokens.css" ``
- The page only works over HTTP (ES modules): open it via the backend at `http://localhost:8080/`, not as a file.
- Escape GTFS/user text before inserting it via `innerHTML`, or use `textContent`.
- Rules go into DOM-free modules (no `document`, `window`, `location` at module level or in the tested functions), so `node --test` can import them; a module touching the DOM is only the adapter that reads and writes the page. Tests sit in `frontend/test/<module>.test.js`.

## 9. Bugs and GitHub Issues
The backlog is the repo's GitHub Issues (`gh issue list`, `gh issue view <n>`). There is no backlog file.
- **Found a possible bug? Ask first.** Stop and describe it to the user: what you saw, where (`file:line`), how to reproduce it, and why you think it's a bug. Open an issue only after the user agrees, in the form and with the labels they confirm. Don't fix it on the side either.
- **Security problems never go into a public issue.** Describe them to the user only; they decide whether to file a private advisory (`SECURITY.md`).
- **Labels:** `bug`, `feature` (feature/UX), `structure` (structure/cleanup/refactor). Add `needs decision` when the user has to decide before work starts. Items that block the public launch go in the `Launch` milestone.
- **Refer to items by issue number** (`#12`, "caused by #7"), never by position. Issues moved from the old `TODO.md` keep their old ID at the start of the title (`B54: …`); older commits and docs use those IDs (B, F, S plus a number).
- **Finishing an item:** name the issue in the commit subject and close it from the body, e.g. subject `Fix BOM header parsing (#12)`, body line `Fixes #12`. GitHub closes it when the commit reaches `main`. Reference the issue in the related test's `@Disabled` reason too, if there is one.
- Writing an issue: say what is wrong or wanted, where (`file:line`), how to reproduce, and what "done" means. Keep long research (tables, links) in the issue, not in the docs.

## 10. Before you call a change done
1. `compile` and `test` pass, and the frontend tests if `frontend/` changed (CLAUDE.md commands).
2. The diff has no unused code, commented-out code, stray logs, debug leftovers or unrelated reformatting.
3. Nothing you added duplicates existing logic (grep for it).
4. No new dependency without approval.
5. The commit closes its issue (`Fixes #n`), user-approved new items are opened as issues, and `decisions.md` is updated if relevant.
6. `ARCHITECTURE.md` still matches the code for everything the diff touches (triggers listed in CLAUDE.md → Workflow). Grep it for every class, field, endpoint, config key and constant you renamed, removed or changed.
