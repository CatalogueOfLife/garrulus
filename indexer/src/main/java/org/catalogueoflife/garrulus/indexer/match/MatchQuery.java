package org.catalogueoflife.garrulus.indexer.match;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One name to match. {@code hints} are CLB classification parameters, keys only from {@link #HINT_KEYS}. The rank is a
 * query parameter but not part of the cache key.
 */
public record MatchQuery(String name, @Nullable String rank, @Nullable String authorship,
    SortedMap<String, String> hints) {

  public static final List<String> HINT_KEYS = List.of("kingdom", "phylum", "class", "order", "family", "genus");

  private static final String AUTHORSHIP = "authorship=";

  public MatchQuery {
    for (String k : hints.keySet()) {
      if (!HINT_KEYS.contains(k)) throw new IllegalArgumentException("Not a match hint: " + k);
    }
    hints = new TreeMap<>(hints);
    rank = rank == null || rank.isBlank() ? null : rank.strip();
    authorship = authorship == null || authorship.isBlank() ? null : authorship.strip();
  }

  /** The hints sorted as {@code k=v} joined by {@code |}, then {@code authorship=<a>}; empty with neither. */
  public String hintKey() {
    StringBuilder sb = new StringBuilder();
    hints.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "|").append(k).append('=').append(v));
    if (authorship != null) sb.append(sb.isEmpty() ? "" : "|").append(AUTHORSHIP).append(authorship);
    return sb.toString();
  }

  /** The inverse of {@link #hintKey()}; {@code authorship=} is always last and takes the rest, which may contain {@code |}. */
  public static MatchQuery fromHintKey(String name, @Nullable String rank, String hintKey) {
    TreeMap<String, String> hints = new TreeMap<>();
    String authorship = null;
    String rest = hintKey;
    while (!rest.isEmpty()) {
      if (rest.startsWith(AUTHORSHIP)) {
        authorship = rest.substring(AUTHORSHIP.length());
        break;
      }
      int bar = rest.indexOf('|');
      String part = bar < 0 ? rest : rest.substring(0, bar);
      rest = bar < 0 ? "" : rest.substring(bar + 1);
      int eq = part.indexOf('=');
      if (eq < 0) throw new IllegalArgumentException("Not a hint key part: " + part);
      hints.put(part.substring(0, eq), part.substring(eq + 1));
    }
    return new MatchQuery(name, rank, authorship, hints);
  }
}
