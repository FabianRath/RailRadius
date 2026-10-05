package com.railreach.service;

import com.railreach.util.ResourceLines;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The category passengers see on a train ("ICE", "IC"). Feeds that keep it in a trips.txt column of their own have
 * that column in {@code train-category-columns.txt}. Feeds that only give a bare train number and keep the category
 * in nothing but the route type have rules in {@code train-categories.txt}.
 */
final class TrainCategories {

    private static final String RULES_RESOURCE = "/train-categories.txt";
    private static final int MIN_RULE_FIELDS = 4;
    private static final String COLUMNS_RESOURCE = "/train-category-columns.txt";
    private static final int COLUMN_FIELDS = 2;

    /** Empty {@code routeShortNames} matches every route. */
    private record Rule(String source, String agencyName, int routeType, String category,
                        Set<String> routeShortNames) {

        boolean matches(String feedSource, String agency, int type, String routeShortName) {
            return source.equals(feedSource) && agencyName.equals(agency) && routeType == type
                    && (routeShortNames.isEmpty()
                        || (routeShortName != null && routeShortNames.contains(routeShortName)));
        }
    }

    private final List<Rule> rules;
    // source -> trips.txt column with the category
    private final Map<String, String> categoryColumns;

    TrainCategories() {
        rules = ResourceLines.read(RULES_RESOURCE).stream().map(TrainCategories::parseRule).toList();
        categoryColumns = ResourceLines.read(COLUMNS_RESOURCE).stream()
                .map(TrainCategories::parseColumn)
                .collect(Collectors.toUnmodifiableMap(List::getFirst, List::getLast));
    }

    /** The trips.txt column that holds the source's train categories, if its feed has one. */
    Optional<String> columnOf(String source) {
        return Optional.ofNullable(categoryColumns.get(source));
    }

    /** The category of the first matching rule. */
    Optional<String> of(String source, String agencyName, int routeType, String routeShortName) {
        return rules.stream()
                .filter(rule -> rule.matches(source, agencyName, routeType, routeShortName))
                .map(Rule::category)
                .findFirst();
    }

    // Format: source;column
    private static List<String> parseColumn(String line) {
        List<String> fields = Arrays.stream(line.split(";")).map(String::strip).toList();
        if (fields.size() != COLUMN_FIELDS) throw new IllegalStateException("Invalid category column: " + line);
        return fields;
    }

    // Format: source;agency_name;route_type;category[;route_short_name...]
    private static Rule parseRule(String line) {
        List<String> fields = Arrays.stream(line.split(";")).map(String::strip).toList();
        if (fields.size() < MIN_RULE_FIELDS) throw new IllegalStateException("Incomplete train category: " + line);
        return new Rule(fields.get(0), fields.get(1), Integer.parseInt(fields.get(2)), fields.get(3),
                Set.copyOf(fields.subList(MIN_RULE_FIELDS, fields.size())));
    }
}
