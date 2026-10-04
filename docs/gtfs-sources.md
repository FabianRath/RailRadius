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
