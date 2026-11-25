---
name: run-dev
description: Start the RailRadius backend with the small dev dataset (Austria/Hungary/Slovakia, or the offline gtfs-mini feeds where those are missing, e.g. in a cloud session) in the background and wait until it is healthy. Use to check backend changes end to end, or when asked to run, start or try the app.
---

# Run the backend with dev data

1. Check whether it is already running: `curl -s localhost:8080/api/health`. If it returns `"status":"UP"`, skip to step 5.
   - If you changed Java code since it started, stop the old process first (see the end of this file).
2. Pick the profile: `ls backend/data/gtfs-dev/*.zip`. With zips, use `dev`. Without (fresh checkout, or a cloud session whose proxy blocks the feed hosts, #21), use `mini`: run `bash scripts/mini-data.sh` first, which zips the gtfs-mini test feeds (made-up stations) into `backend/data/gtfs-mini/`.
   - On a fresh Windows checkout you may download the real dev feeds instead: start with `dev` (it starts empty), then after step 4 run `scripts/api.sh feeds-download-outdated` and `scripts/api.sh reload-gtfs`.
3. Start it in the background (Bash `run_in_background: true`) from the repo root, with `<profile>` from step 2. Run `mkdir -p` on the scratchpad first, because the redirect fails if the folder doesn't exist.
   ```
   mvn -q -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=<profile> "-Dspring-boot.run.jvmArguments=-Xmx2g -XX:+UseCompactObjectHeaders" > <scratchpad>/dev-run.log 2>&1
   ```
   - Maven runs the app with `backend/` as its working directory, so the relative `data/...` paths resolve.
   - The first start parses the zips and writes `backend/data/gtfs-<profile>-store.bin`. Either way, startup takes about 5–10 s.
4. Wait for health with a second background Bash command that exits on success *or* failure, so you get exactly one notification:
   ```
   for i in $(seq 1 60); do curl -s localhost:8080/api/health | grep -q '"UP"' && { echo UP; break; }; grep -qE "APPLICATION FAILED|BUILD FAILURE|OutOfMemory" <log> && { echo FAILED; break; }; sleep 3; done
   ```
   - On FAILED or a timeout, read the last 40 lines of the log with `tail`, not the whole file.
5. Smoke checks (`scripts/api.sh`):
   - `scripts/api.sh stats`: station and edge counts
   - `scripts/api.sh search wien 3`: copy a station id from the result (`mini`: only `omega:O2` Wiener Neustadt Hbf; `alpha:A1` reaches more)
   - `scripts/api.sh reach <id> 1`: prints only the `done` event with the total count
6. Report what you checked and the numbers you saw.

Stop the server when done, unless the user wants it kept running:
- Stop the background task.
- If port 8080 stays busy: on Windows (Git Bash) find the PID with `netstat -ano | grep :8080` and run `taskkill //PID <pid> //F`; on Linux run `fuser -k 8080/tcp`.

Delete `backend/data/gtfs-<profile>-store.bin` to force a re-parse after changing loading/dedup code.
