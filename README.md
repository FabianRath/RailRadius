# RailRadius

**Where can I get by train from here, with at most N changes?**

Pick a start station and a maximum number of transfers. RailRadius then shows every European rail station you can reach, coloured by how many changes it takes. Click a station to see the journey leg by leg.

It covers national rail in about 30 European countries, from Portugal to Finland and from Ireland to Turkey, plus cross-border operators such as Eurostar and European Sleeper. Stations that appear in several countries' feeds are merged, so a search can cross borders.

## How it works

1. **Load:** GTFS timetable feeds (one zip per country or operator) are parsed. Only rail routes are kept.
2. **Merge:** the feeds are combined into one network, and the same station from different feeds (e.g. a border station) is merged into one.
3. **Search:** a round-based search from the start station finds every station reachable with 0, 1, … N transfers, with the fastest journey to each.
4. **Show:** results stream to the browser via Server-Sent Events, and a MapLibre map draws them as they arrive.

The parsed network is stored in a compact binary file, so the full European dataset starts in about 3 seconds.

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Design decisions and their reasons: [docs/decisions.md](docs/decisions.md).

## Tech stack

- **Backend:** Java 25, Spring Boot 4, Caffeine, hand-written GTFS parser. Tests use JUnit 5 and AssertJ.
- **Frontend:** vanilla JavaScript (ES modules, no build step), [MapLibre GL JS](https://maplibre.org/) and [OpenFreeMap](https://openfreemap.org/) tiles. The backend serves it.

## Running it locally

You need **JDK 25**. Maven comes with the repo as a wrapper (`mvnw` / `mvnw.cmd`).

The timetable data is not in the repo (the zips are several GB). A fresh checkout starts empty and downloads the feeds itself.

### Small dev dataset (Austria, Hungary, Slovakia)

From `backend/`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

Then load the data:

1. Open <http://localhost:8080/>.
2. Click **Dev login** (bottom left of the map) and enter the token `dev`.
3. Open the **Admin panel** → **Feeds** → **Download outdated**.
4. Go to **Tools** → **Rebuild from zips**.

The same with the shell helper (Git Bash or any POSIX shell): `scripts/api.sh feeds-download-outdated`, then `scripts/api.sh reload-gtfs`.

Hungary's feed needs a free account from MÁV, so it is skipped without credentials. [docs/gtfs-sources.md](docs/gtfs-sources.md) explains how to set them.

### Full dataset

```bash
./mvnw package -DskipTests
java -Xmx3g -XX:+UseCompactObjectHeaders -jar target/railradius-backend-0.0.1-SNAPSHOT.jar
```

The full dataset needs about 3 GB of heap to build the network the first time. Serving it afterwards runs in much less: [docs/HOSTING.md](docs/HOSTING.md) has the memory measurements and a production setup.

### Tests

```bash
mvn -f backend/pom.xml test
```

The tests run on `gtfs-mini`, a small hand-written set of feeds in `backend/src/test/resources/gtfs-mini/`.

## Data sources and licences

The timetables come from national rail operators, transport ministries and open-data volunteers. Every feed, its publisher and its licence is listed in [docs/gtfs-sources.md](docs/gtfs-sources.md) and credited in the app's **About → Sources** tab.

The feeds keep their own licences (mostly CC BY 4.0, CC0 or ODbL). The code licence below does not apply to them.

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, tiles by [OpenFreeMap](https://openfreemap.org/).

## Contributing

Bugs, ideas and planned work are tracked in [GitHub Issues](https://github.com/FabianRath/RailRadius/issues). Please report security problems privately, as described in [SECURITY.md](SECURITY.md).

Code conventions: [docs/CODING_GUIDELINES.md](docs/CODING_GUIDELINES.md).

## Licence

The RailRadius code is licensed under the [GNU Affero General Public License v3.0](LICENSE). If you run a modified version as a public service, you must publish your changes under the same licence.

Bundled third-party code keeps its own licence: MapLibre GL JS and the fonts in `frontend/vendor/`, and the Lucide icons in the header of `frontend/js/icons.js`.
