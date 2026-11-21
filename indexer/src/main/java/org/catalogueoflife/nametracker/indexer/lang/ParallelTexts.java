package org.catalogueoflife.nametracker.indexer.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.catalogueoflife.nametracker.store.lang.LanguageCodes;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Chooses which versions of a title or abstract a layer delivered are stored in {@code work_text}: a version equal to
 * the work's own text is dropped; one without a language gets the detector's confident language, or is dropped; at
 * most one is kept per field, language and kind (the first). The kind of each version is kept as the layer gave it
 * (ORIGINAL for the source's original-language title, PARALLEL for a translation the publisher supplies). A PARALLEL
 * version in the language of the work's own text of that field is no translation (an OAI record's second
 * {@code dc:description} is often a rights statement or a note) and is dropped; the own text's language is the one
 * given, else the detector's confident one, else unknown (and nothing is dropped for it).
 */
@Component
public class ParallelTexts {

  private static final Pattern MARKUP = Pattern.compile("<[^>]*>");
  private static final Pattern NOISE = Pattern.compile("[^\\p{L}\\p{N}]+");

  private final LanguageDetector detector;

  public ParallelTexts(LanguageDetector detector) {
    this.detector = detector;
  }

  public List<TextIn> of(@Nullable String primaryTitle, @Nullable String primaryAbstract, List<TextIn> versions) {
    return of(primaryTitle, null, primaryAbstract, null, versions);
  }

  /** As {@link #of(String, String, List)}, with the languages of the work's own title and abstract where known. */
  public List<TextIn> of(@Nullable String primaryTitle, @Nullable String titleLang, @Nullable String primaryAbstract,
      @Nullable String abstractLang, List<TextIn> versions) {
    List<TextIn> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Map<String, Optional<String>> own = new HashMap<>(); // the own text's language per field, worked out once
    for (TextIn v : versions) {
      if (v.text() == null || v.text().isBlank()) continue;
      boolean title = "TITLE".equals(v.field());
      if (normalise(v.text()).equals(normalise(title ? primaryTitle : primaryAbstract))) continue;
      String lang = LanguageCodes.normalise(v.lang());
      if (lang == null) {
        LanguageDetector.Detection d = title ? detector.detectTitle(v.text()) : detector.detectText(v.text());
        lang = d == null ? null : d.lang();
      }
      if (lang == null) continue;
      if ("PARALLEL".equals(v.kind()) && own.computeIfAbsent(v.field(), f -> Optional.ofNullable(title
          ? ownLanguage(primaryTitle, titleLang, true) : ownLanguage(primaryAbstract, abstractLang, false)))
          .filter(lang::equals).isPresent()) {
        continue; // the work's own language: no translation
      }
      if (seen.add(v.field() + "|" + lang + "|" + v.kind())) out.add(new TextIn(v.field(), lang, v.kind(), v.text(), v.source()));
    }
    return out;
  }

  private @Nullable String ownLanguage(@Nullable String text, @Nullable String given, boolean title) {
    String lang = LanguageCodes.normalise(given);
    if (lang != null || text == null || text.isBlank()) return lang;
    LanguageDetector.Detection d = title ? detector.detectTitle(text) : detector.detectText(text);
    return d == null ? null : d.lang();
  }

  /** The language of a title when the detector is confident about it, else null. */
  public @Nullable String titleLanguage(@Nullable String title) {
    LanguageDetector.Detection d = detector.detectTitle(title);
    return d == null ? null : d.lang();
  }

  private static String normalise(@Nullable String s) {
    if (s == null) return "";
    return NOISE.matcher(MARKUP.matcher(s).replaceAll(" ")).replaceAll("").toLowerCase(Locale.ROOT);
  }
}
