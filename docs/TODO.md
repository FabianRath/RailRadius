# TODO / Known issues

Rules: see CODING_GUIDELINES §9. In short: every item has a permanent ID. Refer to items by ID ("fix B2"). New bugs go in only after the user has agreed.

**Next free IDs:** B24 · F11 · S12

## Bugs (data loading)
- [ ] **B23** Platforms far from their station stay separate stations. `GtfsFeedLoader.mergeSameNameStops` only merges same-name stops within ~0.0015° and ignores `parent_station`, so e.g. `finland:HKI_1` (~360 m from `finland:HKI`, both "Helsinki") and `finland:OLK_3`/`OLK` show up twice. Fix: also merge a stop into its `parent_station` when the feed provides one.

## Bugs (algorithm)

## Bugs (caching / infrastructure)

## Features / UX

## Structure
