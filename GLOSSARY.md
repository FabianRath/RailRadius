# Glossary

Domain terms as the code and docs use them. Architecture words (module, interface, seam, …) are not listed here.

**Search options**: what a search is: its start station, its max transfers (changes) and the min and max wait at each change. Equal options are the same search, so they share a stored result and a running search. In code: `SearchOptions`. The journey-hours limit is not one of them: it filters a search's result.
_Avoid_: search key, search parameters, query (the frontend's form state).
