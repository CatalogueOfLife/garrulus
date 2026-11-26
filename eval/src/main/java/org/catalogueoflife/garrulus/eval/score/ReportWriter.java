package org.catalogueoflife.garrulus.eval.score;

import org.catalogueoflife.garrulus.finder.api.FinderJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes {date}-{finder}-{corpus}-{split}-{mode}-{STRICT|OVERLAP}[-doc].md and .json into a directory. The JSON keeps at
 * most {@link #JSON_SAMPLES} false positive and false negative samples each (the first ones, spread over the documents
 * by the scorer), so that a full set of baseline reports stays a few MB; a note in both files says so. The markdown is
 * written from all recorded samples, as before.
 */
public final class ReportWriter {
  static final int SAMPLES = 30;
  /** Error samples per list in the JSON. */
  static final int JSON_SAMPLES = 500;

  private ReportWriter() {}

  public static Path write(EvalReport r, Path dir) {
    return write(r, dir, LocalDate.now());
  }

  static Path write(EvalReport r, Path dir, LocalDate date) {
    try {
      Files.createDirectories(dir);
      String base = date + "-" + safe(r.finder()) + "-" + safe(r.corpus()) + "-" + safe(r.split()) + "-" + safe(r.mode())
          + "-" + r.matchMode() + (r.documentLevel() ? "-doc" : "");
      EvalReport full = r;
      EvalReport json = r;
      int fp = r.falsePositives().size();
      int fn = r.falseNegatives().size();
      if (fp > JSON_SAMPLES || fn > JSON_SAMPLES) {
        List<String> notes = new ArrayList<>(r.notes());
        notes.add("The JSON keeps the first " + JSON_SAMPLES + " error samples of each kind: " + Math.min(fp, JSON_SAMPLES) + " of " + fp
            + " recorded false positives, " + Math.min(fn, JSON_SAMPLES) + " of " + fn + " false negatives. All counts are complete.");
        full = r.withNotes(notes);
        json = full.withSamples(cap(r.falsePositives()), cap(r.falseNegatives()));
      }
      Path md = dir.resolve(base + ".md");
      Files.writeString(md, markdown(full, date), StandardCharsets.UTF_8);
      Files.writeString(dir.resolve(base + ".json"),
          FinderJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(json), StandardCharsets.UTF_8);
      return md;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<ErrorSample> cap(List<ErrorSample> l) {
    return l.size() <= JSON_SAMPLES ? l : l.subList(0, JSON_SAMPLES);
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
    sb.append("OTU mentions (BOLD BINs and UNITE SHs, not scored as names): ").append(r.otuMentions());
    if (!r.otuByScheme().isEmpty()) {
      sb.append(" (");
      boolean first = true;
      for (var e : r.otuByScheme().entrySet()) {
        sb.append(first ? "" : ", ").append(e.getKey()).append(' ').append(e.getValue());
        first = false;
      }
      sb.append(')');
    }
    sb.append("\n\n");
    sb.append("| Metric | TP | FP | FN | Precision | Recall | F1 |\n|---|---:|---:|---:|---:|---:|---:|\n");
    row(sb, "names", r.names());
    r.byCategory().forEach((k, v) -> row(sb, "&nbsp;&nbsp;" + k, v));
    row(sb, "acts", r.acts());
    row(sb, "contexts", r.contexts());
    row(sb, "main subject", r.mainSubject());
    if (r.authorship() != null) row(sb, "authorship", r.authorship());
    if (r.authorship() != null) {
      sb.append("\nAuthorship: on the matched spans of curated documents, the gold authorship against the mention's,"
          + " whitespace ignored (a wrong one counts as FP and FN).\n");
    }
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
