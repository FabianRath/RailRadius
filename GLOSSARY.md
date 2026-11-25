# Glossary

Domain terms as the code and docs use them. Architecture words (module, interface, seam, …) are not listed here.

**Search options**: what a search is: its start station, its max transfers (changes) and the min and max wait at each change. Equal options are the same search, so they share a stored result and a running search. In code: `SearchOptions`. The journey-hours limit is not one of them: it filters a search's result.
_Avoid_: search key, search parameters, query (the frontend's form state).

**Query**: what the search form holds, without the start station: the max transfers, whether the wait at changes is limited, the min and max wait, and the journey-hours limit. With a start station it gives the Search options; an unlimited wait means the whole wait range a search allows, and the min and max wait are kept for when the limit is turned on again.
_Avoid_: search options (a query has no station, but has the wait-limit switch and the journey-hours limit).

**Admission**: what a search goes through before and while it runs. It is charged to the client's rate limit unless its result is stored, joins an equal search running now, waits in the queue for a permit, and runs within the deadline. Admission ends in a result or a refusal. The warmer's searches skip the charge and the queue: they run only when a permit is free right now. In code: `SearchAdmission`.
_Avoid_: gate (`SearchGate` is only the permits and the queue), throttle.

**Refusal**: the client-safe "no" to a search, with its reason: invalid (400), too many searches (429), busy (503) or too slow (503). It goes to the client as the stream's error event or as the HTTP status, with a message meant for the user. In code: `SearchRefusal`.
_Avoid_: rejection, error (an error is a server fault, and its details are never shown to the client).

**Served graph**: the one snapshot of the merged rail network the app answers from: its stations, their translated names, the timetable store, the trip infos, the feed timespans and the station search index. A load replaces it whole, in one step, so a reader sees the old one or the new one, never a mix. A search finishes on the one it started on, and its result keeps it. In code: `ServedGraph`, held by `TransitGraph`.
_Avoid_: graph (alone), network (the merged network a load is still building is `RailNetwork`).

**Admin access**: how the app tells the admin from everyone else. The admin proves itself with the token in the `X-Admin-Token` header, or with the cookie of an admin session started by dev login. Only the header opens the admin API, because a cookie would also be sent by a forged cross-site request; either one exempts a search from the rate limit. A wrong token counts against the client wherever it is sent, and a client with too many is turned away from the admin API and loses its header's exemption. In code: `AdminAccess`.
_Avoid_: admin token (the token is only one of the two proofs), admin identity.

**Path**: one way a search reaches a station: its rides in order, with the transfers between them, the minutes from the first departure to the last arrival, the stations passed and the running days. A station can have several paths, one per number of transfers, each kept only if it is better than those with fewer. In code: `CompactResult.PathSummary`, and as a journey `JourneyPath`.
_Avoid_: route (a GTFS route is a line), connection (one leg between two stops).

**Running days**: the exact days, from today on, on which a whole path can be travelled: every ride runs on them and each transfer is caught. Shown with a label such as "Runs daily". In code: `ServiceDays`, worked out by `JourneyRunningDays`.
_Avoid_: service days (a trip's own days, before the journey's are worked out), calendar.

**Same result**: what a change to the search or its data must keep. For every start and number of transfers, each station has the same paths with the same transfers, minutes, stations, rides boarded and left at the same stations and times, and the same running days. Which of two trains with the same stops and times a ride names may differ. Checked by the "same result" mode of the search oracle; the strict mode also wants the same trains. In code: `SearchSnapshot.RideFormat.STATIONS_AND_TIMES`.
_Avoid_: identical results, byte-identical (that is the strict mode).
