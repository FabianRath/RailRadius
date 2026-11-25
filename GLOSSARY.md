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

**Path**: one way a search found to a station: how many transfers it takes, how long it takes, the rides it is made of and its running days. A station gets one path per number of transfers, and only if it is faster than every path there with fewer transfers. A path the journey-hours limit hides is still part of the result, because another limit would show it.
_Avoid_: route (a GTFS route is a line), connection, journey (the legs shown for one path).

**Running days**: the dates, from today on, on which a whole path can be travelled.
_Avoid_: service days (those belong to one trip, not to a path), calendar.

**Same result**: two searches with equal search options give the same result when they find the same paths at every station: the same transfers, duration and running days. The rides may differ, as long as each one leaves and arrives at the same stations and times: a different but equally good train is the same result.
_Avoid_: identical output, equivalent result.
