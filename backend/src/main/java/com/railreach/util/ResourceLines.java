package com.railreach.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads the hand-maintained {@code .txt} config files on the classpath, and the bench station list. */
public final class ResourceLines {

    private ResourceLines() {}

    /** The resource's lines without blank lines and # comments. */
    public static List<String> read(String resource) {
        try (InputStream in = ResourceLines.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource " + resource);
            return withoutComments(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Like {@link #read(String)}, for a file. */
    public static List<String> read(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            return withoutComments(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> withoutComments(BufferedReader reader) {
        return reader.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}
