#!/usr/bin/env bash
# Abuse probe (S39): the hostile requests from the 2026-10-17 security review, each of which must get the expected
# answer. Run it against the full data before launch, after each deploy and after security changes. Run from anywhere.
# Usage: scripts/probe.sh            the app itself (RAILRADIUS_URL, default http://localhost:8080)
#        scripts/probe.sh --public   the public host behind the proxy, e.g. RAILRADIUS_URL=https://<host>
# Prints PASS/FAIL per check and exits 1 if any failed. It sends no admin token and only GETs, so it changes nothing.
# Admin paths: the app answers 403 (no token configured), 401 (wrong or missing token) or 404/400 (a path no handler
# matches), and 429 once the failed-login throttle (B46) blocks the client: every admin call without a valid token
# counts, so with a token configured a direct run blocks admin logins from this IP for 15 min (through the SSH tunnel
# that is the admin's own login). The proxy answers 404 or 400 before the app sees them (HOSTING.md, Admin surface).
# Searches: invalid parameters are rejected before the rate limit charges anything, and no check sends a valid one.
set -euo pipefail
BASE="${RAILRADIUS_URL:-http://localhost:8080}"
PUBLIC=false
[[ "${1:-}" == "--public" ]] && PUBLIC=true
if $PUBLIC; then ADMIN_DENIED="400|404"; WRONG_TOKEN="404"; else ADMIN_DENIED="400|401|403|404|429"; WRONG_TOKEN="401|403|429"; fi
failed=0

result() { # <ok?> <label>
  if $1; then echo "PASS  $2"; else echo "FAIL  $2"; failed=$((failed + 1)); fi
}

# <expected codes, e.g. 400|404> <path> [curl args]
check() {
  local expected=$1 path=$2 got; shift 2
  got=$(curl -s -o /dev/null -w '%{http_code}' --path-as-is -m 10 "$@" "$BASE$path" || true)
  local ok=false; [[ "|$expected|" == *"|$got|"* ]] && ok=true
  result $ok "$got (want $expected)  ${path:0:100} $*"
}

# A stream answers 200 and sends a bad request's rejection as an error event
sse_error() {
  local body ok=false
  body=$(curl -s --path-as-is -m 10 "$BASE/api/reachability/stream?$1" || true)
  grep -q '^event:error' <<< "$body" && ! grep -q '^event:stations' <<< "$body" && ok=true
  result $ok "event:error  /api/reachability/stream?$1"
}

# <path> <header regex>...
headers() {
  local path=$1 head; shift
  head=$(curl -s -D - -o /dev/null -m 10 "$BASE$path" | tr -d '\r' || true)
  local want
  for want in "$@"; do
    local ok=false; grep -qiE "^$want" <<< "$head" && ok=true
    result $ok "header $want  $path"
  done
}

station=$(curl -s -m 10 "$BASE/api/stations/search?q=wien&limit=1" | sed -nE 's/^\[\{"id":"([^"]+)".*/\1/p')
[[ -n "$station" ]] || { echo "No station found for 'wien' at $BASE: is the data loaded?"; exit 1; }
s="stationId=$station"
long_q=$(printf 'a%.0s' $(seq 101))

check 200 /api/health
check 200 /actuator/health

# Station search (B44): limit 1-50, query up to 100 characters
check 200 "/api/stations/search?q=wien&limit=50"
check 400 "/api/stations/search?q=wien&limit=-1"
check 400 "/api/stations/search?q=wien&limit=0"
check 400 "/api/stations/search?q=wien&limit=51"
check 400 "/api/stations/search?q=&limit=100000"
check 400 "/api/stations/search?q=wien&limit=abc"
check 400 "/api/stations/search?limit=5"
check 400 "/api/stations/search?q=$long_q"
check "400|414|431" "/api/stations/search?q=$(printf 'a%.0s' $(seq 10000))"

# Unknown stations (B42): rejected, never searched or cached
check 404 /api/stations/nope
sse_error "stationId=nope&maxTransfers=1"
check 400 "/api/reachability/journey?stationId=nope&maxTransfers=1&targetId=nope"

# Search bounds (B41): changes 0-5, 0 <= min wait <= max wait <= 360, max journey 0.5-24 h
for params in maxTransfers=-1 maxTransfers=6 maxTransfers=100 \
    "maxTransfers=1&minTransferMinutes=-1" "maxTransfers=1&minTransferMinutes=60&maxTransferMinutes=30" \
    "maxTransfers=1&maxTransferMinutes=361" "maxTransfers=1&minTransferMinutes=-500&maxTransferMinutes=100000" \
    "maxTransfers=1&maxJourneyHours=NaN" "maxTransfers=1&maxJourneyHours=Infinity" \
    "maxTransfers=1&maxJourneyHours=0" "maxTransfers=1&maxJourneyHours=25" "maxTransfers=1&maxJourneyHours=-1"; do
  sse_error "$s&$params"
done
check 400 "/api/reachability/stream?$s&maxTransfers=abc"
check 400 "/api/reachability/stream?$s&maxTransfers=2147483648"
check 400 "/api/reachability/stream?maxTransfers=1"
check 400 "/api/reachability/journey?$s&maxTransfers=6&targetId=nope"
check 400 "/api/reachability/journey?$s&maxTransfers=1&maxTransferMinutes=361&targetId=nope"

# Admin surface (S35, B46) and actuator (S30), as an attacker without the token sees them
for path in /api/admin/debug/status /api/admin/stats /api//admin/debug/status //api/admin/debug/status \
    /api/x/../admin/debug/status /api/admin/./debug/status /api/%61dmin/debug/status /api%2Fadmin/debug/status \
    /api/admin%2Fdebug%2Fstatus /API/admin/debug/status /api/ADMIN/debug/status "/api;x/admin/debug/status" \
    "/api/admin;x/debug/status" /api/admin/debug/status/; do
  check "$ADMIN_DENIED" "$path"
done
check "$WRONG_TOKEN" /api/admin/debug/status -H "X-Admin-Token: probe-wrong-token"
check 404 /actuator/env
check 404 /actuator/heapdump
check 404 /backend_test.html

# Security headers (B47); HSTS comes from the proxy only (S38)
for path in / /api/health; do
  headers "$path" "Content-Security-Policy: default-src 'self'" "X-Content-Type-Options: nosniff" \
    "X-Frame-Options: DENY" "Referrer-Policy: no-referrer"
done
if $PUBLIC; then headers / "Strict-Transport-Security: max-age="; fi

echo "$failed failed"
[[ $failed -eq 0 ]]
