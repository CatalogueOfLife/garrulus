package org.catalogueoflife.nametracker.eval.score;

import org.catalogueoflife.nametracker.finder.api.FinderJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Writes {date}-{finder}-{corpus}-{split}-{mode}-{STRICT|OVERLAP}[-doc].md and .json into a directory. */
public final class ReportWriter {
  static final int SAMPLES = 30;

  private ReportWriter() {}

  public static Path write(EvalReport r, Path dir) {
    return write(r, dir, LocalDate.now());
  }

  static Path write(EvalReport r, Path dir, LocalDate date) {
    try {
      Files.createDirectories(dir);
      String base = date + "-" + safe(r.finder()) + "-" + safe(r.corpus()) + "-" + safe(r.split()) + "-" + safe(r.mode())
          + "-" + r.matchMode() + (r.documentLevel() ? "-doc" : "");
      Path md = dir.resolve(base + ".md");
      Files.writeString(md, markdown(r, date), StandardCharsets.UTF_8);
      Files.writeString(dir.resolve(base + ".json"),
          FinderJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(r), StandardCharsets.UTF_8);
      return md;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String safe(String s) {
    return s.replaceAll("[^A-Za-z0-9._-]+", "_");
  }

  static String markdown(EvalReport r, LocalDate date) {
    StringBuilder sb = new StringBuilder();
    sb.append("# ").append(r.finder()).append(" on ").append(r.corpus()).append(" / ").append(r.split())
        .append(" (mode ").append(r.mode()).append(", match ").append(r.matchMode())
        .append(r.documentLevel() ? ", document level" : "").append(")\n\n");
    sb.append("Date: ").append(date).append("\n\n");
    sb.append("Documents: ").append(r.documents()).append("\n\n");
    if (r.info() != null) {
      sb.append("Finder: ").append(r.info().name()).append(' ').append(r.info().version())
          .append(", config `").append(r.info().configHash()).append("`");
      if (r.info().dictionaryKey() != null) sb.append(", dictionary `").append(r.info().dictionaryKey()).append('`');
      sb.append("\n\n");
    }
    if (!r.notes().isEmpty()) {
      for (String n : r.notes()) sb.append("- ").append(n).append('\n');
      sb.append('\n');
    }
    sb.append("| Metric | TP | FP | FN | Precision | Recall | F1 |\n|---|---:|---:|---:|---:|---:|---:|\n");
    row(sb, "names", r.names());
    r.byCategory().forEach((k, v) -> row(sb, "&nbsp;&nbsp;" + k, v));
    row(sb, "acts", r.acts());
    row(sb, "contexts", r.contexts());
    row(sb, "main subject", r.mainSubject());
    if (r.nameCorrect() != null) {
      sb.append("\nName correct: of the matched spans whose gold has a full name, how many mentions carry that name"
          + " (whitespace normalised). It does not change the name P/R/F1 above.\n\n");
      sb.append("| Name correct | Correct | Matched | Rate |\n|---|---:|---:|---:|\n");
      accuracy(sb, "names", r.nameCorrect());
      r.nameCorrectByKind().forEach((k, v) -> accuracy(sb, "&nbsp;&nbsp;" + k, v));
    }
    samples(sb, "False positives", r.falsePositives());
    samples(sb, "False negatives", r.falseNegatives());
    return sb.toString();
  }

  private static void row(StringBuilder sb, String label, Metrics m) {
    sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %.3f | %.3f | %.3f |%n", label, m.tp(), m.fp(), m.fn(),
        m.precision(), m.recall(), m.f1()));
  }

  private static void accuracy(StringBuilder sb, String label, NameAccuracy a) {
    sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %.3f |%n", label, a.correct(), a.matched(), a.rate()));
  }

  private static void samples(StringBuilder sb, String title, List<ErrorSample> list) {
    sb.append("\n## ").append(title).append(" (").append(Math.min(SAMPLES, list.size())).append(" of ").append(list.size())
        .append(" recorded)\n\n");
    for (int i = 0; i < Math.min(SAMPLES, list.size()); i++) {
      ErrorSample s = list.get(i);
      sb.append("- `").append(s.docId()).append("` ");
      if (s.category() != null) sb.append('[').append(s.category()).append("] ");
      if (s.context() != null) sb.append(s.context().replace("|", "\\|"));
      else sb.append(s.text());
      sb.append('\n');
    }
  }
}
