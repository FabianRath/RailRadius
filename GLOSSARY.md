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
