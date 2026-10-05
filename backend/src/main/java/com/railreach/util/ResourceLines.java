package com.railreach.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reads the hand-maintained {@code .txt} config files on the classpath. */
public final class ResourceLines {

    private ResourceLines() {}

    /** The resource's lines without blank lines and # comments. */
    public static List<String> read(String resource) {
        try (InputStream in = ResourceLines.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource " + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
