package com.railradius.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forms of a station name that search matches against. Names stay in the local language ("Köln Hbf"), but
 * people also type them without accents ("koln"), in German spelling ("koeln") or translated ("cologne").
 */
final class StationNameVariants {

    private static final String ALIAS_RESOURCE = "/station-aliases.txt";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** Letters that NFD does not split into a base letter plus accent. */
    private static final Map<String, String> UNSPLIT_LETTERS = Map.of(
            "ß", "ss", "ø", "o", "ł", "l", "đ", "d", "æ", "ae", "œ", "oe", "ı", "i");
    private static final Map<String, String> GERMAN_UMLAUTS = Map.of("ä", "ae", "ö", "oe", "ü", "ue");

    private record CityAlias(String localName, Pattern localWord, List<String> translations) {}

    private final List<CityAlias> cityAliases;

    StationNameVariants() {
        cityAliases = loadCityAliases();
    }

    /** Lowercase, without accents, single spaces. Applied to names and queries alike. */
    static String fold(String text) {
        String folded = MARKS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("");
        return WHITESPACE.matcher(replaceAll(folded, UNSPLIT_LETTERS)).replaceAll(" ").strip();
    }

    /** The folded name first, then its German spelling and its forms with translated city names, without repeats. */
    List<String> variants(String name) {
        String folded = fold(name);
        Set<String> variants = new LinkedHashSet<>();
        variants.add(folded);
        variants.add(fold(replaceAll(name.toLowerCase(Locale.ROOT), GERMAN_UMLAUTS)));
        for (CityAlias alias : cityAliases) {
            if (!folded.contains(alias.localName())) continue;
            Matcher matcher = alias.localWord().matcher(folded);
            if (!matcher.find()) continue;
            for (String translation : alias.translations()) {
                variants.add(matcher.replaceAll(Matcher.quoteReplacement(translation)));
            }
        }
        return List.copyOf(variants);
    }

    private static String replaceAll(String text, Map<String, String> replacements) {
        String result = text;
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            result = result.replace(replacement.getKey(), replacement.getValue());
        }
        return result;
    }

    private static List<CityAlias> loadCityAliases() {
        try (InputStream in = StationNameVariants.class.getResourceAsStream(ALIAS_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource " + ALIAS_RESOURCE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(StationNameVariants::parseCityAlias)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Format: local;translation[;translation...]. A whole-word match keeps "wien" out of "wiener neustadt".
    private static CityAlias parseCityAlias(String line) {
        List<String> names = Arrays.stream(line.split(";")).map(StationNameVariants::fold).toList();
        if (names.size() < 2) throw new IllegalStateException("City alias without translation: " + line);
        String localName = names.getFirst();
        Pattern localWord = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(localName) + "(?![\\p{L}\\p{N}])");
        return new CityAlias(localName, localWord, names.subList(1, names.size()));
    }
}
