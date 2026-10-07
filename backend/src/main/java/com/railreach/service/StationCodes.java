package com.railreach.service;

import com.railreach.util.ResourceLines;
import com.railreach.util.StopIds;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The UIC station code that some feeds keep in their stop IDs, each in a format of its own. The formats are in
 * {@code station-codes.txt}.
 */
final class StationCodes {

    private static final String RULES_RESOURCE = "/station-codes.txt";
    private static final int RULE_FIELDS = 3;

    private record Rule(String source, Pattern stopId, String code) {}

    private final List<Rule> rules;

    StationCodes() {
        rules = ResourceLines.read(RULES_RESOURCE).stream().map(StationCodes::parseRule).toList();
    }

    /** The code in a canonical stop ID, by the first rule of its source that matches. */
    Optional<String> of(String stopId) {
        String source = StopIds.source(stopId);
        String rawId = stopId.substring(source.length() + 1);
        for (Rule rule : rules) {
            if (!rule.source().equals(source)) continue;
            Matcher matcher = rule.stopId().matcher(rawId);
            if (matcher.matches()) return Optional.of(matcher.replaceFirst(rule.code()));
        }
        return Optional.empty();
    }

    // Format: source;stop ID regex;code. The regex is anchored, so replaceFirst rewrites the whole ID
    private static Rule parseRule(String line) {
        String[] fields = line.split(";");
        if (fields.length != RULE_FIELDS) throw new IllegalStateException("Invalid station code rule: " + line);
        return new Rule(fields[0].strip(), Pattern.compile("^(?:" + fields[1].strip() + ")$"), fields[2].strip());
    }
}
