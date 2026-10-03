---
name: run-dev
description: Start the RailReach backend with the small dev dataset (Austria/Hungary/Slovakia) in the background and wait until it is healthy. Use to check backend changes end to end, or when asked to run, start or try the app.
---

# Run the backend with dev data

1. Check whether it is already running: `curl -s localhost:8080/api/health`. If it returns `"status":"UP"`, skip to step 4.
   - If you changed Java code since it started, stop the old process first (see the end of this file).
2. Start it in the background (Bash `run_in_background: true`) from the repo root. Run `mkdir -p` on the scratchpad first, because the redirect fails if the folder doesn't exist.
   ```
   mvn -q -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.jvmArguments=-Xmx2g > <scratchpad>/dev-run.log 2>&1
   ```
   - Maven runs the app with `backend/` as its working directory, so the relative `data/...` paths resolve.
   - The first start parses the zips and writes `backend/data/gtfs-dev-cache.json`. Either way, startup takes about 5–10 s.
3. Wait for health with a second background Bash command that exits on success *or* failure, so you get exactly one notification:
   ```
   for i in $(seq 1 60); do curl -s localhost:8080/api/health | grep -q '"UP"' && { echo UP; break; }; grep -qE "APPLICATION FAILED|BUILD FAILURE|OutOfMemory" <log> && { echo FAILED; break; }; sleep 3; done
   ```
   - On FAILED or a timeout, read the last 40 lines of the log with `tail`, not the whole file.
4. Smoke checks (`scripts/api.sh`):
   - `scripts/api.sh stats`: station and edge counts
   - `scripts/api.sh search wien 3`: copy a station id from the result
   - `scripts/api.sh reach <id> 1`: prints only the `done` event with the total count
5. Report what you checked and the numbers you saw.

Stop the server when done, unless the user wants it kept running:
- Stop the background task.
- If port 8080 stays busy, find the PID with `netstat -ano | grep :8080` and run `taskkill //PID <pid> //F`.

Delete `backend/data/gtfs-dev-cache.json` to force a re-parse after changing loading/dedup code.
