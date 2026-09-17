package com.trustledger.reconciliation.casework.csv;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * An RFC 4180 file read into a header and rows. Parsing is delegated to Commons CSV; this class owns
 * only the file-level rules: a header must exist, names are unique, and the file is bounded.
 *
 * <p>{@code raw} is the row re-serialised in canonical CSV, not the original bytes. The original file is
 * preserved whole in evidence storage; the row number is how a reader finds the row in it.
 */
public final class CsvTable {

    /** A file this module refuses as a whole. Nothing from it is kept. */
    public static final class FileRejected extends RuntimeException {
        private final String code;
        public FileRejected(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    /** {@code values} is null when the row has the wrong number of columns. */
    public record Row(int number, Map<String, String> values, String raw) {}

    // ponytail: whole file in memory. Fine under the 25 MB upload cap; stream if the cap is raised.
    public static final int MAX_ROWS = 200_000;

    private final List<String> headers;
    private final List<Row> rows;

    private CsvTable(List<String> headers, List<Row> rows) {
        this.headers = headers;
        this.rows = rows;
    }

    public List<String> headers() { return headers; }
    public List<Row> rows() { return rows; }

    public static CsvTable parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1); // Excel writes a BOM; it is not data
        if (text.isBlank()) throw new FileRejected("EMPTY_FILE", "the file is empty");

        CSVFormat format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true).setTrim(true).get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<String> headers = new ArrayList<>();
            for (String h : parser.getHeaderNames()) {
                String name = h.toLowerCase(Locale.ROOT);
                if (name.isEmpty()) throw new FileRejected("BLANK_HEADER", "a column has no name");
                if (headers.contains(name)) throw new FileRejected("DUPLICATE_HEADER", "column appears twice: " + name);
                headers.add(name);
            }
            List<Row> rows = new ArrayList<>();
            for (CSVRecord rec : parser) {
                if (rows.size() >= MAX_ROWS) {
                    throw new FileRejected("TOO_MANY_ROWS", "more than " + MAX_ROWS + " rows; split the file");
                }
                String raw = CSVFormat.RFC4180.format(rec.toList().toArray());
                if (rec.size() != headers.size()) {
                    // A short or long row would silently shift every later column. Reject the row, keep the file.
                    rows.add(new Row((int) rec.getRecordNumber(), null, raw));
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) values.put(headers.get(i), rec.get(i));
                rows.add(new Row((int) rec.getRecordNumber(), values, raw));
            }
            if (rows.isEmpty()) throw new FileRejected("NO_ROWS", "the file has a header and no rows");
            return new CsvTable(List.copyOf(headers), List.copyOf(rows));
        } catch (IOException | IllegalArgumentException | java.io.UncheckedIOException e) {
            // Commons CSV reports a malformed header or an unterminated quote this way.
            throw new FileRejected("UNREADABLE_CSV", "the file is not valid CSV: " + e.getMessage());
        }
    }
}
