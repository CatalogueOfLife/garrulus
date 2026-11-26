package org.catalogueoflife.garrulus.finder.dict;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Common words of a few languages (top 20,000 lower-case words each), used to flag genera that are also ordinary words,
 * e.g. "Major". The lists are CC-BY-SA 4.0 data files, see NOTICE next to them.
 */
public final class CommonWords {
  public static final Set<String> DEFAULT_LANGS = Set.of("en", "de", "fr", "es", "pt");
  private final Set<String> words;

  private CommonWords(Set<String> words) {
    this.words = words;
  }

  public static CommonWords load(Set<String> langs) {
    Set<String> words = new HashSet<>();
    for (String lang : langs) {
      String res = "common-words-" + lang + ".txt";
      try (InputStream in = CommonWords.class.getResourceAsStream(res)) {
        if (in == null) throw new IllegalArgumentException("No common word list for language " + lang);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
          if (!line.isBlank()) words.add(line.strip());
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return new CommonWords(words);
  }

  public static CommonWords of(Collection<String> words) {
    return new CommonWords(new HashSet<>(words));
  }

  /** @param word any case, it is lower cased here */
  public boolean contains(String word) {
    return words.contains(word.toLowerCase(Locale.ROOT));
  }

  public int size() {
    return words.size();
  }
}
