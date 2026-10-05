package com.railreach.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static com.railreach.service.GtfsCsvReader.field;

/** Reads the rail routes and rail trips of one feed from routes.txt, agency.txt and trips.txt. */
@Slf4j
final class RailTripReader {

    private static final int GTFS_ROUTE_TYPE_RAIL = 2;
    // Extended GTFS rail types 100–106: railway, high speed, long distance, inter-regional, car transport,
    // sleeper, regional. Trams (0), subways (1), buses (3, 700+) and urban railways (400) are left out.
    private static final int EXTENDED_RAIL_TYPE_MIN = 100;
    private static final int EXTENDED_RAIL_TYPE_MAX = 106;
    // S-Bahn: thousands of stations, mostly in Germany, are served by nothing else (B6)
    private static final int SUBURBAN_RAILWAY_TYPE = 109;
    // The German feed pads train numbers to six digits ("002310"); operators show them without the zeros
    private static final Pattern LEADING_ZEROS = Pattern.compile("^0+(?=\\d+$)");

    record RailRoute(String name, String operator) {}

    record RailTrip(String routeId, TripInfo info) {}

    private RailTripReader() {}

    /** route ID -> route, for rail routes only. */
    static Map<String, RailRoute> loadRailRoutes(ZipFile zipFile) throws IOException {
        Map<String, String> agencyNames = loadAgencyNames(zipFile);
        // agency_id may be left out when a feed has a single agency
        String singleAgencyName = agencyNames.size() == 1 ? agencyNames.values().iterator().next() : null;
        Map<String, RailRoute> routes = new HashMap<>();
        Map<Integer, Integer> routeTypeCounts = new HashMap<>();
        int totalRoutes = 0;
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "routes.txt")) {
            int idCol = csv.column("route_id");
            int agencyCol = csv.column("agency_id");
            int shortNameCol = csv.column("route_short_name");
            int longNameCol = csv.column("route_long_name");
            int typeCol = csv.column("route_type");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                totalRoutes++;
                String routeType = field(row, typeCol);
                if (routeType != null) {
                    int type = GtfsCsvReader.parseInt(routeType);
                    routeTypeCounts.merge(type, 1, Integer::sum);
                    if (!isRail(type)) continue;
                }
                String id = field(row, idCol);
                if (id == null) continue;
                String shortName = field(row, shortNameCol);
                String name = shortName != null ? shortName : field(row, longNameCol);
                String agencyId = field(row, agencyCol);
                String operator = agencyId != null ? agencyNames.get(agencyId) : singleAgencyName;
                routes.put(id, new RailRoute(name != null ? name : "Unknown", operator));
            }
        }
        log.info("  Routes: {} total, {} rail routes selected", totalRoutes, routes.size());
        log.info("  Route type distribution: {}", routeTypeCounts);
        return routes;
    }

    /** trip ID -> trip, for trips on the given rail routes only. */
    static Map<String, RailTrip> loadRailTrips(ZipFile zipFile, Map<String, RailRoute> railRoutes) throws IOException {
        Map<String, RailTrip> trips = new HashMap<>();
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "trips.txt")) {
            int routeCol = csv.column("route_id");
            int tripCol = csv.column("trip_id");
            int shortNameCol = csv.column("trip_short_name");
            int headsignCol = csv.column("trip_headsign");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String tripId = field(row, tripCol);
                String routeId = field(row, routeCol);
                RailRoute route = routeId != null ? railRoutes.get(routeId) : null;
                if (tripId == null || route == null) continue;
                TripInfo info = new TripInfo(trainNumber(field(row, shortNameCol)), field(row, headsignCol),
                        route.operator());
                trips.put(tripId, new RailTrip(routeId, info));
            }
        }
        return trips;
    }

    private static Map<String, String> loadAgencyNames(ZipFile zipFile) throws IOException {
        Map<String, String> names = new HashMap<>();
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "agency.txt")) {
            int idCol = csv.column("agency_id");
            int nameCol = csv.column("agency_name");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String name = field(row, nameCol);
                // agency_id is optional in a feed with a single agency
                if (name != null) names.put(Objects.requireNonNullElse(field(row, idCol), ""), name);
            }
        }
        return names;
    }

    private static String trainNumber(String tripShortName) {
        return tripShortName != null ? LEADING_ZEROS.matcher(tripShortName).replaceFirst("") : null;
    }

    private static boolean isRail(int routeType) {
        return routeType == GTFS_ROUTE_TYPE_RAIL || routeType == SUBURBAN_RAILWAY_TYPE
                || (routeType >= EXTENDED_RAIL_TYPE_MIN && routeType <= EXTENDED_RAIL_TYPE_MAX);
    }
}
