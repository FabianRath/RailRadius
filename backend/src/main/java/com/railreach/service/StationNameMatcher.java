package com.railreach.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Compares the names of two nearby stations of different feeds, which spell the same station differently: in
 * Cyrillic or Latin ("Пловдив" / "PLOVDIV"), with or without words like "station" ("Split željeznički kolodvor"),
 * abbreviated ("BOLOGNA C.LE") or translated ("Padua" / "PADOVA"). Names are compared as sets of words; two words
 * match when they are equal or differ by a typo-sized edit.
 */
final class StationNameMatcher {

    private static final Pattern WORD_SEPARATORS = Pattern.compile("[\\s\\p{Punct}]+");
    // Feeds mark their stops abroad with the country: "Apach(fr)", "Croix Wasquehal (FR)"
    private static final Pattern COUNTRY_SUFFIX = Pattern.compile("\\s*\\(\\p{Alpha}{2}\\)\\s*$");
    // Italian feeds abbreviate "Centrale"; the dot would otherwise split it into two words
    private static final Pattern CENTRALE_ABBREVIATION = Pattern.compile("(?<![a-z])c\\.le(?![a-z])");
    /**
     * Words that say "station", and link words; they don't tell two stations of one place apart. "tsentralna" is
     * from the Bulgarian "Централна гара" (central station), the name of each city's main station.
     */
    private static final Set<String> GENERIC_WORDS = Set.of(
            "station", "stazione", "gare", "gara", "bahnhof", "bf", "railway", "kolodvor", "zeleznicki", "estacion",
            "estacao", "dworzec", "przystanek", "zastavka", "zast", "nadrazi", "tsentralna", "di", "de", "del",
            "della", "du");
    private static final Map<String, String> SYNONYMS = Map.of(
            "hbf", "hauptbahnhof", "aeroporto", "airport", "aeroport", "airport", "aeropuerto", "airport",
            "flughafen", "airport", "lotnisko", "airport");
    // German compounds like "Ostbf" (Ostbahnhof)
    private static final String BAHNHOF_ABBREVIATION = "bf";
    private static final String BAHNHOF = "bahnhof";
    // Danish and German spellings without ø/ä/ö/ü ("Koebenhavn")
    private static final Map<String, String> VOWEL_DIGRAPHS = Map.of("oe", "o", "ae", "a", "ue", "u");
    // Bulgarian streamlined system, plus the letters of other Cyrillic alphabets. й and ё are already и and е,
    // because folding strips their marks.
    private static final Map<Character, String> CYRILLIC = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"), Map.entry('д', "d"),
            Map.entry('е', "e"), Map.entry('ж', "zh"), Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"), Map.entry('п', "p"),
            Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"), Map.entry('ф', "f"),
            Map.entry('х', "h"), Map.entry('ц', "ts"), Map.entry('ч', "ch"), Map.entry('ш', "sh"),
            Map.entry('щ', "sht"), Map.entry('ъ', "a"), Map.entry('ь', "y"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"), Map.entry('ы', "y"), Map.entry('э', "e"), Map.entry('є', "ye"), Map.entry('і', "i"),
            Map.entry('ђ', "dj"), Map.entry('ј', "j"), Map.entry('љ', "lj"), Map.entry('њ', "nj"),
            Map.entry('ћ', "c"), Map.entry('џ', "dz"));
    // Shorter words must match exactly: "Bari" and "Bra" are different places
    private static final int MIN_LENGTH_FOR_ONE_EDIT = 5;
    private static final int MIN_LENGTH_FOR_TWO_EDITS = 8;

    private final StationNameVariants nameVariants = new StationNameVariants();

    /**
     * Jaccard similarity of the two names' word sets (0 to 1), with matching words counted as shared. The best
     * value over the names' translated forms.
     */
    double similarity(String name1, String name2) {
        double best = 0.0;
        for (Set<String> words1 : wordSets(name1)) {
            for (Set<String> words2 : wordSets(name2)) {
                if (words1.isEmpty() || words2.isEmpty()) continue;
                int matched = sharedWords(words1, words2);
                best = Math.max(best, (double) matched / (words1.size() + words2.size() - matched));
            }
        }
        return best;
    }

    /** Whether every word of one name matches a word of the other ("Split" in "Split željeznički kolodvor"). */
    boolean oneContainsTheOther(String name1, String name2) {
        for (Set<String> words1 : wordSets(name1)) {
            for (Set<String> words2 : wordSets(name2)) {
                if (words1.isEmpty() || words2.isEmpty()) continue;
                Set<String> fewer = words1.size() <= words2.size() ? words1 : words2;
                Set<String> more = fewer == words1 ? words2 : words1;
                if (wordsWithMatch(fewer, more) == fewer.size()) return true;
            }
        }
        return false;
    }

    /** Whether every word of each name matches a word of the other, ignoring a country in brackets at the end. */
    boolean sameName(String name1, String name2) {
        if (name1 == null || name2 == null) return false;
        for (Set<String> words1 : wordSets(COUNTRY_SUFFIX.matcher(name1).replaceFirst(""))) {
            for (Set<String> words2 : wordSets(COUNTRY_SUFFIX.matcher(name2).replaceFirst(""))) {
                if (words1.isEmpty() || words2.isEmpty()) continue;
                boolean allMatch1 = wordsWithMatch(words1, words2) == words1.size();
                if (allMatch1 && wordsWithMatch(words2, words1) == words2.size()) return true;
            }
        }
        return false;
    }

    private List<Set<String>> wordSets(String name) {
        if (name == null) return List.of();
        return nameVariants.variants(name).stream().map(StationNameMatcher::words).toList();
    }

    private static Set<String> words(String foldedName) {
        String name = CENTRALE_ABBREVIATION.matcher(transliterate(foldedName)).replaceAll("centrale");
        return Arrays.stream(WORD_SEPARATORS.split(name))
                .map(StationNameMatcher::normalizeWord)
                .filter(word -> !word.isEmpty() && !GENERIC_WORDS.contains(word))
                .collect(Collectors.toSet());
    }

    private static String normalizeWord(String word) {
        String normalized = SYNONYMS.getOrDefault(word, word);
        if (normalized.endsWith(BAHNHOF_ABBREVIATION) && normalized.length() > BAHNHOF_ABBREVIATION.length()) {
            normalized = normalized.substring(0, normalized.length() - BAHNHOF_ABBREVIATION.length()) + BAHNHOF;
        }
        for (Map.Entry<String, String> digraph : VOWEL_DIGRAPHS.entrySet()) {
            normalized = normalized.replace(digraph.getKey(), digraph.getValue());
        }
        return normalized;
    }

    private static String transliterate(String text) {
        StringBuilder latin = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            latin.append(CYRILLIC.getOrDefault(c, String.valueOf(c)));
        }
        return latin.toString();
    }

    // Several words of one name can match the same word of the other, so count from both sides
    private static int sharedWords(Set<String> words1, Set<String> words2) {
        return Math.min(wordsWithMatch(words1, words2), wordsWithMatch(words2, words1));
    }

    private static int wordsWithMatch(Set<String> words, Set<String> others) {
        return (int) words.stream().filter(word -> others.stream().anyMatch(other -> wordsMatch(word, other))).count();
    }

    // Typos and transliterations differ inside a word; a different first letter is a different word
    // ("Errenteria" is not "Renteria")
    private static boolean wordsMatch(String word1, String word2) {
        if (word1.equals(word2)) return true;
        if (word1.charAt(0) != word2.charAt(0)) return false;
        int shorter = Math.min(word1.length(), word2.length());
        int maxEdits = shorter >= MIN_LENGTH_FOR_TWO_EDITS ? 2 : shorter >= MIN_LENGTH_FOR_ONE_EDIT ? 1 : 0;
        return maxEdits > 0 && editDistance(word1, word2) <= maxEdits;
    }

    /** Levenshtein distance. */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
