package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.jspecify.annotations.Nullable;

/**
 * Reads {@code beacon.tab} of the PKP Beacon dataset (CC0): tab separated, a header line, text fields in double quotes
 * (with {@code ""} for a quote, and possibly real line breaks inside), {@code NA} for a missing value. Columns are read
 * by header name. The ISSN cell lists several ISSNs separated by the two characters {@code \n} or by real line breaks.
 * The stream is lazy and closes the input when closed. An I/O failure surfaces as an {@link IOException} (while reading
 * the header) or an {@link UncheckedIOException}; a file that is no Beacon table is an {@link IllegalArgumentException}.
 */
final class BeaconFile {

  /** A field longer than this (an unterminated quote) fails its record instead of swallowing the rest of the file. */
  static final int MAX_FIELD = 64 * 1024;

  private static final List<String> END = List.of();

  private BeaconFile() {}

  static Stream<BeaconRow> read(InputStream in) throws IOException {
    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    List<String> header = record(r);
    if (header == null || header == END || !header.contains("context_name") || !header.contains("issn")) {
      r.close();
      throw new IllegalArgumentException("Not a PKP Beacon table: columns context_name and issn expected");
    }
    Iterator<BeaconRow> it = new Iterator<>() {
      BeaconRow next;

      @Override
      public boolean hasNext() {
        try {
          while (next == null) {
            List<String> f = record(r);
            if (f == END) return false;
            if (f != null && f.size() == header.size()) next = row(header, f); // a broken record is skipped
          }
          return true;
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      @Override
      public BeaconRow next() {
        if (!hasNext()) throw new NoSuchElementException();
        BeaconRow n = next;
        next = null;
        return n;
      }
    };
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL), false)
        .onClose(() -> {
          try {
            r.close();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  private static BeaconRow row(List<String> header, List<String> f) {
    return new BeaconRow(text(f, header, "context_name"), issns(text(f, header, "issn")), text(f, header, "oai_url"),
        text(f, header, "set_spec"), text(f, header, "country"), integer(text(f, header, "total_record_count")),
        integer(text(f, header, "record_count_2025")), instant(text(f, header, "last_oai_response")),
        flag(text(f, header, "unresponsive_endpoint")) || flag(text(f, header, "unresponsive_context")));
  }

  private static @Nullable String text(List<String> f, List<String> header, String column) {
    int i = header.indexOf(column);
    if (i < 0) return null;
    String v = f.get(i).strip();
    return v.isEmpty() || v.equals("NA") ? null : v;
  }

  static List<String> issns(@Nullable String cell) {
    if (cell == null) return List.of();
    Set<String> out = new LinkedHashSet<>();
    for (String part : cell.split("\\\\n|[\\r\\n]+")) {
      String i = Issn.normalise(part);
      if (i != null) out.add(i);
    }
    return List.copyOf(out);
  }

  private static @Nullable Integer integer(@Nullable String s) {
    if (s == null) return null;
    try {
      return Integer.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static @Nullable Instant instant(@Nullable String s) {
    if (s == null) return null;
    try {
      return Instant.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static boolean flag(@Nullable String s) {
    return "1".equals(s) || "true".equalsIgnoreCase(s);
  }

  /**
   * The next record as its fields; {@link #END} at the end of the input; null for a record with a field over
   * {@link #MAX_FIELD} chars, whose rest is skipped up to the next line break.
   */
  private static @Nullable List<String> record(BufferedReader r) throws IOException {
    int c = r.read();
    if (c < 0) return END;
    List<String> fields = new ArrayList<>();
    StringBuilder b = new StringBuilder();
    boolean quoted = false;
    while (true) {
      if (c < 0) {
        fields.add(b.toString());
        return fields;
      }
      if (b.length() > MAX_FIELD) {
        while (c >= 0 && c != '\n') c = r.read();
        return null;
      }
      if (quoted) {
        if (c == '"') {
          r.mark(1);
          int n = r.read();
          if (n == '"') {
            b.append('"');
          } else {
            quoted = false;
            if (n >= 0) r.reset();
          }
        } else {
          b.append((char) c);
        }
      } else if (c == '"' && b.isEmpty()) {
        quoted = true;
      } else if (c == '\t') {
        fields.add(b.toString());
        b.setLength(0);
      } else if (c == '\n') {
        fields.add(b.toString());
        return fields;
      } else if (c != '\r') {
        b.append((char) c);
      }
      c = r.read();
    }
  }
}
