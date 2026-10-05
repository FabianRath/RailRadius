# GTFS sources

Where the zips in `backend/data/gtfs/` come from. Name each download `gtfs_<country>.zip`.

The download URL of every feed with a direct download is in `backend/src/main/resources/gtfs-feeds.txt`, which the update center (admin panel → Feeds) downloads from; the table names that feed. Only sources without a direct download (portal, login, fee, web page) keep their link here.

## Primary

| Country | Source |
|---|---|
| Germany | https://gtfs.de/en/feeds/ |

## Per country

| Status | Country | Feed or source | Notes |
|---|---|---|---|
| X | Austria | https://data.mobilitaetsverbuende.at/de/data-sets | |
| L | Belgium | https://data.belgianmobility.io/en/data.html | |
| Fee required | Croatia | https://www.promet-info.hr/en/datasets_details?id=95df8225-6741-acc6-f7f2-2f9b7d0902f6 | |
| L | Denmark | `denmark` | |
| ?? | Estonia | https://data.public-transport.earth/gtfs/ee | |
| L | Finland | `finland` | VR + HSL commuter rail (Fintraffic). Needs header `Accept-Encoding: gzip` (else HTTP 406); send `Digitraffic-User: <app name>`. busmaps' `fintraffic` feed has no rail routes; their `trains-fin` is request-only. |
| L | Germany | `germany` | DELFI, the source of our `gtfs_germany.zip` (route IDs like `de:vgn:…`). Mirror refreshed 2026-09-29, 470 MB. Has train numbers (`trip_short_name`, zero-padded: `002310`) and headsigns, but no train category: DB Fernverkehr routes are named by line only (`33`), and only `route_type` tells ICE (101) from IC/EC (102). Transitous derives the category from that, see below. |
| X | Ireland | https://data.public-transport.earth/gtfs/ie | |
| L | Lithuania | `lithuania` | LTG Link rail. `data.public-transport.earth/gtfs/lt` has bus, ferry and trolleybus only. |
| | Luxembourg | `luxembourg` | |
| | Netherlands | `netherlands` | |
| | Norway | `norway` | |
| | Sweden | `sweden` | |
| | Switzerland | `switzerland` | |
| | UK | https://busmaps.com/en/uk/Datafeeds/great-britain | |
| | Poland | `poland` | |
| | Latvia | `latvia` | |
| | Estonia | `estonia` | |
| | Slovakia | `slovakia` | |
| | Hungary | `hungary` | |
| | Slovenia | `slovenia` | |
| | Croatia | `croatia` | |
| | Serbia | `serbia` | |
| | Montenegro | `montenegro` | |
| | Romania | `romania` | |
| | Bulgaria | `bulgaria` | |
| | Greece | `greece` | |
| | France | `france` | |
| | Spain | `spain` | Renfe high-speed, long and medium distance. FEVE, FGC and Euskotren have their own feeds. |
| | Spain | `spain-cercanias` | Renfe Cercanías (commuter). Saved as `gtfs_spain-cercanias.zip`. The NAP link (`nap.transportes.gob.es/api/Fichero/download/1130`) needs a login (HTTP 401). |
| | Portugal | `portugal` | CP. Enhanced variant with platforms and shapes: https://github.com/joaodcp/cp-gtfs-enhanced |
| | Italy | `italy` | Trenitalia, community conversion of the official NeTEx (CC BY 4.0), updated weekly. |
| | Italy | `italy-italo` | Italo high-speed (also has ItaloBus routes, which the rail filter drops). Saved as `gtfs_italy-italo.zip`. |
| | Czechia | `czechia` | Whole national rail timetable, converted from the official CZPTT data by volunteers (used by Transitous). |
| | Bosnia and Herzegovina | `bosnia` | ŽFBiH (Federation only). No feed for ŽRS (Republika Srpska). |
| | North Macedonia | `northmacedonia` | Macedonian Railways, volunteer rehost. |
| | Moldova | `moldova` | CFM, volunteer rehost. |
| | Ukraine | `ukraine` | Ukrzaliznytsia. |
| | Turkey | `turkey` | TCDD (MIT). |
| No GTFS found | Northern Ireland | | Translink publishes rail only as CIF. The Dublin–Belfast Enterprise may be in the Irish Rail feed (unchecked). |
| No GTFS found | Kosovo, Albania, Belarus | | |

Not needed: Liechtenstein and Monaco (served by ÖBB / SNCF, so covered by the Austria and France feeds, unchecked); Andorra, San Marino, Malta, Iceland, Cyprus and Vatican City have no regular passenger rail.

## Also worth knowing

- https://jbb.ghsq.de/gtfs/ (host of the Lithuania feed) also has fresh rail feeds for Bulgaria (`bg-bdz`), Greece (`gr-hellenic-train`, `gr-railway-gov`), Romania (`ro-railway`), Serbia (`rs-srbijavoz`), Montenegro (`me-zpcg`), Slovakia (`sk-zssk`) and Slovenia (`si-nap`). Possible replacements for the `s3.transitpdf.com` links above if those go stale.
- The same host has European Sleeper (Brussels/Amsterdam–Berlin–Prague night train): https://jbb.ghsq.de/gtfs/eu-es.gtfs.zip
- **German long-distance with train categories (checked 2026-10-05):** gtfs.de `de_fv` free feed, https://download.gtfs.de/germany/fv_free/latest.zip (424 KB, CC BY 4.0, data from DELFI e.V., updated weekly). Routes carry category + line (`ICE 33`, `IC 33`, `EC 85`, `ECE`, `EN`, `RJ`), but `trips.txt` has only `route_id, service_id, trip_id`: no train numbers or headsigns. Valid for only 30 days (2026-10-03 to 2026-11-02 in the checked download). Regional (`de_rv`) and full (`de_full`) feeds exist in the same form. Paid plans (from €39/month) add headsigns, transfers.txt and full timetable validity; their page doesn't say whether train numbers are included. So no free German feed has both category and train number.
- Transitous post-processes DELFI with `scripts/de-DELFI.lua` (https://github.com/public-transport/transitous/blob/main/scripts/de-DELFI.lua): route type 101 → `ICE <number>`, 102 → `IC <number>` (`ECE` for a fixed list of route short names), leading zeros stripped.
- The Transitous feed catalogue (https://github.com/public-transport/transitous/tree/main/feeds, one JSON per country) is the best place to look for new or changed URLs.
