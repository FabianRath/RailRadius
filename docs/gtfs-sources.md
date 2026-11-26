# GTFS sources

Where the zips in `backend/data/gtfs/` come from. Name each download `gtfs_<country>.zip`. The About dialog's Sources tab credits every feed (`frontend/index.html`): keep it in sync when a feed or its licence changes.

The download URL of every feed with a direct download is in `backend/config/gtfs-feeds.txt`, which the update center (admin panel → Feeds) downloads from; the table names that feed. A feed can list fallback URLs there, which are tried in order when one fails or is rejected. That file is kept out of git so the download URLs aren't published: this page names publishers, portals and licences, never a download URL or file path.

## Primary

| Country | Source |
|---|---|
| Germany | https://gtfs.de/en/feeds/ |

## Per country

Feeds in backticks are in `gtfs-feeds.txt`. Rail routes, dates and operators were checked on 2026-10-05 (see "Survey" below), the sources again on 2026-10-06. Status `–`: no direct download, so the feed isn't loaded.

| Status | Country | Feed or source | Licence | Notes |
|---|---|---|---|---|
| L | Austria | `austria` | CC BY 4.0 | ÖBB's own feed (ÖBB, Montafonerbahn, CAT). The file name carries the timetable year, so the URL changes every December. Private railways (WESTbahn, GKB, Stern & Hafferl, Salzburger Lokalbahn, Zillertalbahn, NÖVOG) are only in the Verkehrsverbünde feeds at https://data.mobilitaetsverbuende.at/de/data-sets, which need a free login. |
| L | Belgium | `belgien` | CC BY 4.0 | NMBS-SNCB via the Belgian Mobility Open Data Portal (https://data.belgianmobility.io, anonymous tier: 100 requests/day, 10/minute). Terms (https://data.belgianmobility.io/en/terms.html, effective 2026-01-01): CC BY 4.0 with each operator as licensor; credit "Source: NMBS-SNCB – Open Data – <date of dataset update>", or for modified data "Contains data originally published by NMBS-SNCB, modified by <user>". Replaced SNCB's HAFAS download (2026-11-24), whose licence banned redistributing the raw data. Saved as `gtfs_belgien.zip`. Fallbacks: iRail and a second mirror (same data, from the old source). |
| – | Bosnia and Herzegovina | | **unclear** | ŽFBiH and ŽRS (Republika Srpska), volunteer rehost by Petr Novák. Dropped on 2026-11-25 (#6): it ended on 2025-12-13 and was never updated (still the same file on 2026-10-06), and it had no licence. No other source found (Transitous, Mobility Database, Transitland, transitpdf searched). Delete `gtfs_bosnia.zip` from the GTFS directory, or the loader keeps loading it. |
| L | Bulgaria | `bulgaria` | **unclear** (fallback: CC BY 4.0) | BDŽ (jbb also has the CFR and TCDD trains into Bulgaria). The fallback has BDŽ alone, 30 days ahead, and is a third of jbb's size, so the size check only accepts it when no zip is there. |
| L | Croatia | `croatia` | CC0 ² | HŽPP's own download. HŽPP's older download link and the transitpdf copy stop at 2025-12-14. The promet-info.hr national feed needs a fee. |
| L | Czechia | `czechia` | CC0 | Whole national rail timetable, converted from the official CZPTT data by volunteers (used by Transitous). |
| L | Denmark | `denmark` | CC BY 4.0 | Rejseplanen. |
| L | Estonia | `estonia` | CC0 | Elron, the only rail operator (Elron's own feed, 1 MB). Fallback: the national unified feed (49 MB, same rail trips plus all buses; also on the Mobility Database mirror). The old peatus.ee download now returns an HTML page. Downloaded by hand on 2026-10-07 (valid until 2026-12-31): the update center rejects a zip under half the size of the current one, so it would not replace the 20 MB unified zip with Elron's. |
| L | Finland | `finland` | CC BY 4.0 | VR + HSL commuter rail (Fintraffic). Needs header `Accept-Encoding: gzip` (else HTTP 406); send `Digitraffic-User: <app name>`. The fallback drops the heritage railways. busmaps' `fintraffic` feed has no rail routes; their `trains-fin` is request-only. |
| L | France | `france` | ODbL | SNCF Voyageurs: TGV INOUI, OUIGO, Intercités and every region's TER in one feed. The previous URL now serves a feed without rail routes. |
| L | Germany | `germany` | CC BY 4.0 | DELFI, the source of our `gtfs_germany.zip` (route IDs like `de:vgn:…`). Mirror refreshed 2026-09-29, 470 MB. Has train numbers (`trip_short_name`, zero-padded: `002310`) and headsigns, but no train category: DB Fernverkehr routes are named by line only (`33`), and only `route_type` tells ICE (101) from IC/EC (102). Transitous derives the category from that, see below. |
| L | Great Britain | `great-britain` | NRE terms ¹ | All National Rail operators (aubin.app, used by Transitous). 970 MB because it includes every bus; it groups each operator's trains into one route. |
| L | Greece | `greece` | **unclear** | Hellenic Train (jbb, also has ferries). Fallback, also jbb: Hellenic Train alone, 30 days ahead, under half the size (size check rejects it while a zip is there). The transitpdf copy is from 2019. |
| L | Hungary | `hungary` | MÁV terms ¹ | MÁV, GYSEV and the Budapest Children's Railway, MÁV's official feed (6.5 MB, updated daily). Needs HTTP Basic auth with a free account from https://www.mavcsoport.hu/gtfs-igenybejelento (granted 2026-10-07): set `railradius.feeds.auth.hungary` locally (see ARCHITECTURE.md → config). Its TLS root (Microsec e-Szigno) is not in the JDK, so it is in `feed-roots.pem`. Calendar ends with the timetable year (2026-12-12), although `feed_info` says 2027-04-07. The transitpdf copy ended on 2025-11-23 and its host is gone. gtfs.menetbrand.com and gtfs.kti.hu have only Hungarian buses; BKK's feed has only the Budapest HÉV. |
| L | Ireland | `ireland` | CC BY 4.0 | Iarnród Éireann, official (Transport for Ireland). Includes the Dublin–Belfast Enterprise. |
| L | Italy | `italy` | CC BY 4.0 | Trenitalia including all regional trains, community conversion of the official NeTEx (CC BY 4.0), updated weekly. The regional Trenitalia feeds (Piemonte, Liguria, Toscana, Sardegna) add nothing. Other Italian operators: see Survey. |
| L | Italy | `italy-cotral` | **unclear** | Cotral's railways Roma–Viterbo and Roma–Lido. Saved as `gtfs_italy-cotral.zip`. Its "Viterbo" is a separate station from Trenitalia's Viterbo Porta Fiorentina. |
| L | Italy | `italy-eav` | IODL 2.0 | EAV (Circumvesuviana, Cumana, Circumflegrea around Naples; also has buses, which the rail filter drops). Licence: Italian Open Data Licence 2.0 (attribution), stated on https://www.eavsrl.it/open-data/. Its "Napoli P. Garibaldi" is a separate station from Napoli Centrale. |
| L | Italy | `italy-italo` | CC BY 4.0 | Italo high-speed (also has ItaloBus routes, which the rail filter drops). Saved as `gtfs_italy-italo.zip`. |
| L | Italy | `italy-trenord` | CC BY 4.0 | Trenord (Lombardy, incl. Malpensa Express and the lines to Chiasso and Tirano), official (Regione Lombardia). Saved as `gtfs_italy-trenord.zip`. |
| L | Kosovo | `kosovo` | **unclear** | Trainkos, volunteer rehost. |
| L | Latvia | `latvia` | CC0 | Vivi. Its server answers HTTP 403 to Java's default User-Agent, so `gtfs-feeds.txt` sends its own. |
| L | Lithuania | `lithuania` | CC BY 4.0 | LTG Link rail, from the national feed of LTSA (Lithuanian Transport Safety Administration, https://www.visimarsrutai.lt/gtfs/; dataset on data.gov.lt). 72 MB, mostly buses, which the rail filter drops; 73 rail routes on 2026-10-25. Replaced jbb's LTG Link feed, a scrape of ÖBB's journey planner with no licence (#6). The public-transport.earth feed for Lithuania has bus, ferry and trolleybus only. |
| L | Luxembourg | `luxembourg` | CC BY 4.0 | CFL. |
| L | Moldova | `moldova` | **unclear** | CFM, volunteer rehost. |
| L | Montenegro | `montenegro` | ODbL | ŽPCG. |
| L | Netherlands | `netherlands` | CC0 | NS, Arriva, Keolis and others (OVapi). |
| L | North Macedonia | `northmacedonia` | CC0 | MŽT, community feed from the public timetable (github.com/deryclem/gtfs-north-macedonia; fallback: the Mobility Database mirror). Stop names in Cyrillic, Latin ones in `translations.txt`. The previous volunteer rehost deleted its feed on 2026-11-20. |
| L | Northern Ireland | `northern-ireland` | **unclear** (URL: CC BY 4.0) | Translink NI Railways. The zip on disk, `gtfs_northern-ireland.zip` (GTFS.pro copy from transitpdf, 2026-07-27, downloaded on 2026-10-05), has NI Railways trains until 2027-01-31 but can't be refreshed: transitpdf is gone, and Translink publishes rail timetables only as TransXChange (https://www.opendatani.gov.uk/dataset/nir20160126v2, monthly, OGL). `gtfs-feeds.txt` now lists Transport for Ireland's Northern Ireland feed (NTA, CC BY 4.0, https://data.gov.ie/dataset/nta-gtfs; same publisher as `ireland`), but it isn't loaded yet (#1). Checked 2026-11-26: 33 MB, updated daily, mostly Translink buses; 6 rail routes and 1,134 rail trips (NI Railways and the Enterprise), but its NI Railways trains ended on 2026-12-13 and its Enterprise on 2026-12-01. Loaded next to `ireland`, the border and Enterprise stations merge (Belfast Grand Central, Lisburn, Lurgan, Portadown, Newry, Dundalk, Drogheda, Connolly), Adelaide and Antrim come as one stop per platform with no parent station, merged into one station since #77, and Belfast is named "Grand Central Station", found by "Belfast" since #78. The Irish Rail feed has the Dublin–Belfast Enterprise. |
| L | Norway | `norway` | NLOD 2.0 | Entur national feed (Vy, SJ, Go-Ahead, Flytoget). |
| L | Poland | `poland` | CC BY 4.0 | All rail operators incl. PKP Intercity (mkuran.pl). Fallback: a mirror of the same feed. The same mirror has a PKP Intercity-only feed. |
| L | Portugal | `portugal` | NAP terms ¹ | CP. Enhanced variant with platforms and shapes: https://github.com/joaodcp/cp-gtfs-enhanced. |
| L | Portugal | `portugal-fertagus` | CC BY 4.0 | Fertagus (Lisbon–Setúbal); CC BY 4.0 per its Portuguese NAP record 185. Java accepts the server's certificate (Python doesn't). |
| L | Romania | `romania` | OGL-ROU 1.0 | CFR Călători and the private operators. |
| L | Serbia | `serbia` | **unclear** | Srbijavoz. Fallback: the generator's own GitLab package (same file as jbb). |
| L | Slovakia | `slovakia` | **unclear** | ZSSK, Leo Express, RegioJet. jbb's Slovak feed is an HTML page, not a feed. |
| L | Slovenia | `slovenia` | CC BY-SA 4.0 | SŽ. The national access point needs a login. |
| L | Spain | `spain` | CC BY 4.0 | Renfe high-speed, long and medium distance. |
| L | Spain | `spain-cercanias` | CC BY 4.0 | Renfe Cercanías (commuter). Saved as `gtfs_spain-cercanias.zip`. The NAP download needs a login (HTTP 401). |
| L | Spain | `spain-euskotren` | CC BY 4.0 | Euskotren (Basque Country). From the Mobility Database mirror, because the NAP original needs a login. |
| L | Spain | `spain-fgc` | CC BY 4.0 | FGC (Catalan railways around Barcelona and to Montserrat). |
| L | Spain | `spain-ouigo` | Spanish NAP licence ¹ | OUIGO España high speed. From the Mobility Database mirror. Station names are in capitals without accents. |
| L | Sweden | `sweden` | CC0 | Samtrafiken national feed, all operators. |
| L | Switzerland | `switzerland` | opentransportdata.swiss terms | opentransportdata.swiss, all operators. |
| L | Turkey | `turkey` | MIT ¹ | TCDD. |
| L | Ukraine | `ukraine` | **unclear** | Ukrzaliznytsia. |
| No GTFS found | Albania, Belarus | | | Transitous has only city buses for Albania. |

Not needed: Liechtenstein and Monaco (served by ÖBB / SNCF, so covered by the Austria and France feeds, unchecked); Andorra, San Marino, Malta, Iceland, Cyprus and Vatican City have no regular passenger rail.

**Cross-border operators** (not in any national feed; the source name is the operator)

| Status | Operator | Feed | Licence | Notes |
|---|---|---|---|---|
| L | Eurostar | `eurostar` | Etalab 2.0 | Official, includes ex-Thalys (Paris/Brussels–London, Amsterdam, Cologne). |
| L | European Sleeper | `european-sleeper` | ODbL | Night trains Brussels/Amsterdam–Berlin–Prague, also Paris and Milan. Its stop "Aachen Hauptbahnhof" doesn't merge with "Aachen Hbf" (name similarity 0.33). |
| L | Trenitalia France | `trenitalia-france` | Etalab 2.0 | Paris–Lyon–Turin–Milan. |

**Licences** (checked 2026-10-13, S27). Taken from the publisher's own page where it could be read, otherwise from the Transitous source list (https://transitous.org/sources/) or the Mobility Database catalogue. CC BY, CC BY-SA, ODbL, NLOD, Etalab and OGL-ROU all allow public and commercial use with attribution, so the site needs a page naming every source.

- ¹ Not a standard open licence:
  - Great Britain: the timetable comes from National Rail (NRE developer terms: publishing, including commercial use, is allowed with the credit "Powered by National Rail Enquiries"). Aubin's compilation states no licence of its own.
  - Hungary: MÁV gives the feed free to registered developers for building apps, but names no licence (Transitous lists CC0).
  - Portugal (CP): its Portuguese NAP record 176 says "No licence – No contract" (free, no terms).
  - Spain (OUIGO): Spanish NAP licence (LDA). Commercial use is allowed, but it requires the credit "Powered by MITRAMS" with a link, and shares raw data alike. OUIGO's own licence is unknown.
  - Turkey: the generator is MIT, but the timetable is taken from TCDD's website, and TCDD has published no licence for it.
- ² Only from Transitous; no licence page found at the publisher.
- **unclear**: no licence found anywhere. These are the volunteer rehosts (Bosnia, now dropped; Kosovo, Moldova), the jbb feeds that Transitous lists without a licence (Bulgaria, Greece, Ukraine), Serbia's GitLab feed, Cotral, Slovakia (data.slovensko.sk only renders in a browser; an older Mobility Database entry said CC0) and Northern Ireland's zip on disk (a busmaps.com derivative whose licence page is gone; its replacement in `gtfs-feeds.txt` is CC BY 4.0).

## Survey: rail feeds not loaded yet (2026-10-05)

Found by scanning every European static feed in the Transitous catalogue, the Mobility Database catalogue and Transitland Atlas (~2,200 feeds) for routes the loader keeps as rail (route type 2, 100–106, 109). Each feed below was reachable, is a zip with rail routes and runs on 2026-10-05, unless noted; it is named by its publisher or host, not by URL. "MDB mirror" means the Mobility Database's own copy. It also works where the original needs a login (Spanish NAP), and can serve as a fallback for any feed it lists.

**Countries we don't have**

| Country | Feed | Runs until | Notes |
|---|---|---|---|
| Georgia | jbb | 2027-06-11 | Not connected to the European network. |
| Armenia | jbb | 2027-06-13 | Not connected to the European network. |

**Cross-border operators missing from the national feeds**

| Operator | Feed | Runs until | Notes |
|---|---|---|---|
| Optima Express | community feed on GitHub | 2026-11-13 | Car train Villach–Edirne. |
| PKP Intercity to Germany | community feed on GitHub Pages | 2026-11-02 | 4 routes, rolling 30-day feed. |
| FlixTrain | FlixBus's own feed | | Inside the 29 MB FlixBus feed (3 rail routes). |
| Renfe France | data.gouv.fr | – | Empty calendar, unusable. |

**Operators missing in countries we have**

| Country | Operator | Feed | Runs until | Notes |
|---|---|---|---|---|
| Italy | ARST (Sardinian narrow gauge) | ARST's own feed | 2026-12-31 | 4 rail routes in a bus feed. |
| Italy | Trentino Trasporti (Trento–Malè) | Trentino Trasporti open data | 2027-06-25 | 1 rail route in a bus feed. |
| Italy | Ferrovia Circumetnea | MDB mirror | 2025-02-28 | Expired. |
| Spain | SFM (Mallorca) | TIB (Mallorca transport) | 2036-12-31 | 3 rail routes in a bus feed. |
| Spain | FEVE | MDB mirror | 2026-05-13 | Expired; no current source found. |
| Spain | Iryo | – | – | No GTFS published. |
| Poland | Narrow-gauge heritage lines | Polish community mirror | 2026-12-31 | 21 lines. |

Feeds that only duplicate what we load (German Verkehrsverbund feeds, regional Trenitalia feeds, Swedish/Norwegian per-operator feeds, the Polish operator feeds) are left out.

## Also worth knowing

- transitpdf's file hosts stopped resolving on 2026-10-05 and 2026-10-06; transitpdf.com no longer offers the files. jbb (host of several of our feeds) has rail feeds for Bulgaria, Greece, Romania, Serbia, Montenegro and Slovenia, which are the fallbacks in `gtfs-feeds.txt`.
- **German long-distance with train categories (checked 2026-10-05):** gtfs.de's free long-distance feed (424 KB, CC BY 4.0, data from DELFI e.V., updated weekly). Routes carry category + line (`ICE 33`, `IC 33`, `EC 85`, `ECE`, `EN`, `RJ`), but `trips.txt` has only `route_id, service_id, trip_id`: no train numbers or headsigns. Valid for only 30 days (2026-10-03 to 2026-11-02 in the checked download). Regional and full feeds exist in the same form. Paid plans (from €39/month) add headsigns, transfers.txt and full timetable validity; their page doesn't say whether train numbers are included. So no free German feed has both category and train number.
- Transitous post-processes DELFI with `scripts/de-DELFI.lua` (https://github.com/public-transport/transitous/blob/main/scripts/de-DELFI.lua): route type 101 → `ICE <number>`, 102 → `IC <number>` (`ECE` for a fixed list of route short names), leading zeros stripped.
- The Transitous feed catalogue (https://github.com/public-transport/transitous/tree/main/feeds, one JSON per country) is the best place to look for new or changed URLs.
