package com.railreach.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads one CSV file of a GTFS zip row by row. A missing file reads as empty. */
final class GtfsCsvReader implements AutoCloseable {

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private final BufferedReader reader;
    private final String[] headers;

    private GtfsCsvReader(BufferedReader reader, String[] headers) {
        this.reader = reader;
        this.headers = headers;
    }

    static GtfsCsvReader open(ZipFile zipFile, String fileName) throws IOException {
        ZipEntry entry = zipFile.getEntry(fileName);
        if (entry == null) return new GtfsCsvReader(null, new String[0]);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) return new GtfsCsvReader(reader, new String[0]);
        return new GtfsCsvReader(reader, parseLine(stripByteOrderMark(headerLine)));
    }

    // Java's UTF-8 decoder keeps a leading BOM, which would make the first column name unmatchable
    private static String stripByteOrderMark(String line) {
        return !line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK ? line.substring(1) : line;
    }

    /** Index of the named column, or -1 if the file has no such column. */
    int column(String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** The next row's fields, or null at the end of the file. */
    String[] next() throws IOException {
        if (reader == null) return null;
        String line = reader.readLine();
        return line == null ? null : parseLine(line);
    }

    @Override
    public void close() throws IOException {
        if (reader != null) reader.close();
    }

    /** The field at {@code column}, or null if it is missing or blank. */
    static String field(String[] fields, int column) {
        if (column < 0 || column >= fields.length) return null;
        String value = fields[column].trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    static double doubleField(String[] fields, int column) {
        String value = field(fields, column);
        if (value == null) return 0.0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    static int intField(String[] fields, int column) {
        return parseInt(field(fields, column));
    }

    static int parseInt(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }
}
