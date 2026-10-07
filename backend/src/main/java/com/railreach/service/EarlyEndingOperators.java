package com.railreach.service;

import com.railreach.dto.OperatorEnd;
import com.railreach.service.RailTripReader.RailRoute;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipFile;

import static com.railreach.service.GtfsCsvReader.field;

/**
 * The main rail operators of a feed whose trains stop before the feed's last day. A calendar can run months longer
 * than its main operator's trains, e.g. when only the regional operators planned past a timetable change (B34).
 */
final class EarlyEndingOperators {

    // Below this share of a feed's rail trips an operator is a side line (a museum railway, an airport shuttle)
    private static final double MAIN_OPERATOR_MIN_TRIP_SHARE = 0.05;
    private static final String UNKNOWN_OPERATOR = "Unknown";

    /** One operator's services while trips.txt is read. */
    private static final class OperatorTrips {
        private final Set<String> serviceIds = new HashSet<>();
        private int tripCount;
    }

    private EarlyEndingOperators() {}

    /** Earliest end first; empty if every main operator runs until {@code feedLastDay}. */
    static List<OperatorEnd> read(ZipFile zipFile, String source, Map<String, ServiceDays> calendar,
                                  LocalDate feedLastDay) throws IOException {
        Map<String, OperatorTrips> tripsByOperator = readTripsByOperator(zipFile, source);
        int railTripCount = tripsByOperator.values().stream().mapToInt(trips -> trips.tripCount).sum();
        return tripsByOperator.entrySet().stream()
                .filter(entry -> entry.getValue().tripCount >= railTripCount * MAIN_OPERATOR_MIN_TRIP_SHARE)
                .flatMap(entry -> lastDay(entry.getValue(), calendar)
                        .filter(lastDay -> lastDay.isBefore(feedLastDay))
                        .map(lastDay -> new OperatorEnd(entry.getKey(), lastDay))
                        .stream())
                .sorted(Comparator.comparing(OperatorEnd::getLastServiceDate).thenComparing(OperatorEnd::getOperator))
                .toList();
    }

    private static Map<String, OperatorTrips> readTripsByOperator(ZipFile zipFile, String source)
            throws IOException {
        Map<String, RailRoute> routes = RailTripReader.loadRailRoutes(zipFile, source, new TrainCategories());
        Map<String, OperatorTrips> tripsByOperator = new HashMap<>();
        try (GtfsCsvReader csv = GtfsCsvReader.open(zipFile, "trips.txt")) {
            int routeCol = csv.column("route_id");
            int serviceCol = csv.column("service_id");
            for (String[] row = csv.next(); row != null; row = csv.next()) {
                String routeId = field(row, routeCol);
                RailRoute route = routeId != null ? routes.get(routeId) : null;
                if (route == null) continue;
                String operator = route.operator() != null ? route.operator() : UNKNOWN_OPERATOR;
                OperatorTrips trips = tripsByOperator.computeIfAbsent(operator, k -> new OperatorTrips());
                String serviceId = field(row, serviceCol);
                if (serviceId != null) trips.serviceIds.add(serviceId);
                trips.tripCount++;
            }
        }
        return tripsByOperator;
    }

    /** Empty if none of the operator's services runs on any day. */
    private static Optional<LocalDate> lastDay(OperatorTrips trips, Map<String, ServiceDays> calendar) {
        return trips.serviceIds.stream()
                .map(calendar::get)
                .filter(days -> days != null && !days.isEmpty())
                .map(days -> LocalDate.ofEpochDay(days.lastEpochDay()))
                .max(Comparator.naturalOrder());
    }
}
