# GTFS sources

Where the zips in `backend/data/gtfs/` come from. Name each download `gtfs_<country>.zip`.

## Primary

| Country | Source |
|---|---|
| Germany | https://gtfs.de/en/feeds/ |

## Per country

| Status | Country | Source | Notes |
|---|---|---|---|
| X | Austria | https://data.mobilitaetsverbuende.at/de/data-sets | |
| L | Belgium | https://data.belgianmobility.io/en/data.html | |
| Fee required | Croatia | https://www.promet-info.hr/en/datasets_details?id=95df8225-6741-acc6-f7f2-2f9b7d0902f6 | |
| L | Denmark | https://data.public-transport.earth/gtfs/dk | |
| ?? | Estonia | https://data.public-transport.earth/gtfs/ee | |
| L | Finland | https://rata.digitraffic.fi/api/v1/trains/gtfs-passenger.zip | VR + HSL commuter rail (Fintraffic). Needs header `Accept-Encoding: gzip` (else HTTP 406); send `Digitraffic-User: <app name>`. busmaps' `fintraffic` feed has no rail routes; their `trains-fin` is request-only. |
| L | Germany | https://data.public-transport.earth/gtfs/de | |
| X | Ireland | https://data.public-transport.earth/gtfs/ie | |
| L | Lithuania | https://jbb.ghsq.de/gtfs/lt-ltglink.gtfs.zip | LTG Link rail. `data.public-transport.earth/gtfs/lt` has bus, ferry and trolleybus only. |
| | Luxembourg | https://data.public-transport.earth/gtfs/lu | |
| | Netherlands | https://data.public-transport.earth/gtfs/nl | |
| | Norway | https://data.public-transport.earth/gtfs/no | |
| | Sweden | https://data.public-transport.earth/gtfs/se | |
| | Switzerland | https://data.public-transport.earth/gtfs/ch | |
| | UK | https://busmaps.com/en/uk/Datafeeds/great-britain | |
| | Poland | https://mkuran.pl/gtfs/polish_trains.zip | |
| | Latvia | https://vivi.lv/uploads/GTFS.zip | |
| | Estonia | https://s3.transitpdf.com/files/sourcedata/maanteeamet.zip | |
| | Slovakia | https://s3.transitpdf.com/files/sourcedata/zsr.zip | |
| | Hungary | https://s3.transitpdf.com/files/sourcedata/mav-menetrend.zip | |
| | Slovenia | https://s3.transitpdf.com/files/sourcedata/si-nap-gtfs.zip | |
| | Croatia | https://s3.transitpdf.com/files/sourcedata/putnickog-prijevoza.zip | |
| | Serbia | https://s3.transitpdf.com/files/sourcedata/srbijavoz.zip | |
| | Montenegro | https://s3.transitpdf.com/files/sourcedata/crne-gore.zip | |
| | Romania | https://s3.transitpdf.com/files/sourcedata/ro-railway-gtfs.zip | |
| | Bulgaria | https://s3.transitpdf.com/files/sourcedata/bg-bdz-gtfs.zip | |
| | Greece | https://s3.transitpdf.com/files/sourcedata/trainose.zip | |
| | France | https://www.data.gouv.fr/api/1/datasets/r/84f7501c-de7b-46d9-8e0c-14c30d9fa58b | |
| | Spain | https://ssl.renfe.com/gtransit/Fichero_AV_LD/google_transit.zip | Renfe high-speed, long and medium distance. FEVE, FGC and Euskotren have their own feeds. |
| | Spain | https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip | Renfe Cercanías (commuter). Saved as `gtfs_spain-cercanias.zip`. The NAP link (`nap.transportes.gob.es/api/Fichero/download/1130`) needs a login (HTTP 401). |
| | Portugal | https://publico.cp.pt/gtfs/gtfs.zip | CP. Enhanced variant with platforms and shapes: https://github.com/joaodcp/cp-gtfs-enhanced |
| | Italy | https://github.com/deryclem/trenitalia-gtfs/raw/main/gtfs-trenitalia.zip | Trenitalia, community conversion of the official NeTEx (CC BY 4.0), updated weekly. |
| | Italy | https://github.com/deryclem/italo-gtfs/raw/main/gtfs-italo.zip | Italo high-speed (also has ItaloBus routes, which the rail filter drops). Saved as `gtfs_italy-italo.zip`. |
| | Czechia | https://motis.obehy.cz/get-feeds/cz-czptt-gtfs.zip | Whole national rail timetable, converted from the official CZPTT data by volunteers (used by Transitous). |
| | Bosnia and Herzegovina | https://owncloud.cesnet.cz/index.php/s/yWJhi9wjUIc3IC2/download | ŽFBiH (Federation only). No feed for ŽRS (Republika Srpska). |
| | North Macedonia | https://hoermalmeister.github.io/gtfs-rehost/mzi/mzi.zip | Macedonian Railways, volunteer rehost. |
| | Moldova | https://hoermalmeister.github.io/gtfs-rehost/cfm/cfm.zip | CFM, volunteer rehost. |
| | Ukraine | https://jbb.ghsq.de/gtfs/ua-ukrzaliznytsya.gtfs.zip | Ukrzaliznytsia. |
| | Turkey | https://github.com/yasinkaraaslan/tcdd-gtfs/raw/main/output/tcdd.zip | TCDD (MIT). |
| No GTFS found | Northern Ireland | | Translink publishes rail only as CIF. The Dublin–Belfast Enterprise may be in the Irish Rail feed (unchecked). |
| No GTFS found | Kosovo, Albania, Belarus | | |

Not needed: Liechtenstein and Monaco (served by ÖBB / SNCF, so covered by the Austria and France feeds, unchecked); Andorra, San Marino, Malta, Iceland, Cyprus and Vatican City have no regular passenger rail.

## Also worth knowing

- https://jbb.ghsq.de/gtfs/ (host of the Lithuania feed) also has fresh rail feeds for Bulgaria (`bg-bdz`), Greece (`gr-hellenic-train`, `gr-railway-gov`), Romania (`ro-railway`), Serbia (`rs-srbijavoz`), Montenegro (`me-zpcg`), Slovakia (`sk-zssk`) and Slovenia (`si-nap`). Possible replacements for the `s3.transitpdf.com` links above if those go stale.
- The same host has European Sleeper (Brussels/Amsterdam–Berlin–Prague night train): https://jbb.ghsq.de/gtfs/eu-es.gtfs.zip
- The Transitous feed catalogue (https://github.com/public-transport/transitous/tree/main/feeds, one JSON per country) is the best place to look for new or changed URLs.
