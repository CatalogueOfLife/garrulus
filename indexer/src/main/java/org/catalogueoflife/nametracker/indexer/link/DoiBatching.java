package org.catalogueoflife.nametracker.indexer.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks a service about DOIs in batches without letting a hostile DOI break the batch. DOIs are {@code 10.\d+/\S+}, so a
 * SICI DOI carries {@code ( ) ; : < > #} and others {@code , | "}: the list separators of the batched filters. Only
 * DOIs of plain characters are batched; every other DOI is asked alone. A batch the service refuses with 400 is asked
 * again one by one, and a single DOI it refuses is a miss (WARN), not a failure of the task.
 */
public final class DoiBatching {

  private static final Logger LOG = LoggerFactory.getLogger(DoiBatching.class);
  private static final Pattern PLAIN = Pattern.compile("^10\\.\\d{4,9}/[A-Za-z0-9._/-]+$");

  private DoiBatching() {
  }

  /** True when the DOI can sit in a comma, pipe or OR separated list of a query unescaped. */
  public static boolean batchable(String doi) {
    return PLAIN.matcher(doi).matches();
  }

  /** True when the DOI can stand in a double-quoted query term: no quote, backslash, whitespace or control character. */
  public static boolean quotable(String doi) {
    return doi.codePoints().noneMatch(c -> c == '"' || c == '\\' || Character.isWhitespace(c) || Character.isISOControl(c));
  }

  /** The key to compare DOIs of answers with: stripped and lower case. */
  public static String key(String doi) {
    return doi.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Calls {@code call} with the batchable DOIs in one list (at most the size of {@code dois}) and with each other DOI
   * alone; the results of the calls, in that order. A retryable or any other failure than a 400 propagates.
   */
  public static <R> List<R> each(List<String> dois, Function<List<String>, R> call) {
    List<String> plain = new ArrayList<>();
    List<String> odd = new ArrayList<>();
    for (String d : dois) (batchable(d) ? plain : odd).add(d);
    List<R> out = new ArrayList<>();
    if (!plain.isEmpty()) {
      try {
        out.add(call.apply(plain));
      } catch (HttpProblem p) {
        if (p.status() != 400 || plain.size() == 1) {
          if (p.status() == 400) LOG.warn("The service refused DOI {}: {}", plain.getFirst(), p.getMessage());
          else throw p;
        } else {
          odd.addAll(0, plain);
        }
      }
    }
    for (String d : odd) {
      try {
        out.add(call.apply(List.of(d)));
      } catch (HttpProblem p) {
        if (p.status() != 400) throw p;
        LOG.warn("The service refused DOI {}: {}", d, p.getMessage());
      }
    }
    return out;
  }
}
