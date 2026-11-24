# Glossary

Domain terms as the code and docs use them. Architecture words (module, interface, seam, …) are not listed here.

**Search options**: what a search is: its start station, its max transfers (changes) and the min and max wait at each change. Equal options are the same search, so they share a stored result and a running search. In code: `SearchOptions`. The journey-hours limit is not one of them: it filters a search's result.
_Avoid_: search key, search parameters, query (the frontend's form state).

**Query**: what the search form holds, without the start station: the max transfers, whether the wait at changes is limited, the min and max wait, and the journey-hours limit. With a start station it gives the Search options; an unlimited wait means the whole wait range a search allows, and the min and max wait are kept for when the limit is turned on again.
_Avoid_: search options (a query has no station, but has the wait-limit switch and the journey-hours limit).
