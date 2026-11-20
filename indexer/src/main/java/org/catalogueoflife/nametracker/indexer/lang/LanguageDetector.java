package org.catalogueoflife.nametracker.indexer.lang;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Offline language detection with Lingua over 40 languages. The models are built on first use, once. Low-accuracy
 * mode (the default) uses a fraction of the memory of high-accuracy mode and is adequate for titles of three or more
 * words; {@code nametracker.language.high-accuracy} switches it. Names are not removed from the text: they are a small
 * share of a title or abstract.
 */
@Component
public class LanguageDetector {

  /** {@code lang} is an ISO 639-1 code, null when nothing was detected. */
  public record Detection(@Nullable String lang, float confidence) {}

  private static final Language[] LANGUAGES = {
      Language.ENGLISH, Language.CHINESE, Language.JAPANESE, Language.KOREAN, Language.RUSSIAN, Language.UKRAINIAN,
      Language.BULGARIAN, Language.SERBIAN, Language.SPANISH, Language.PORTUGUESE, Language.FRENCH, Language.GERMAN,
      Language.ITALIAN, Language.DUTCH, Language.POLISH, Language.CZECH, Language.SLOVAK, Language.HUNGARIAN,
      Language.ROMANIAN, Language.CROATIAN, Language.SLOVENE, Language.TURKISH, Language.PERSIAN, Language.ARABIC,
      Language.HEBREW, Language.GREEK, Language.THAI, Language.VIETNAMESE, Language.INDONESIAN, Language.MALAY,
      Language.HINDI, Language.BENGALI, Language.TAMIL, Language.SWEDISH, Language.BOKMAL, Language.DANISH,
      Language.FINNISH, Language.ESTONIAN, Language.LITHUANIAN, Language.CATALAN};

  private static final Pattern MARKUP = Pattern.compile("<[^>]*>");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final int MIN_SCRIPT_LETTERS = 8;

  private final LanguageProperties props;
  private volatile com.github.pemistahl.lingua.api.LanguageDetector lingua;

  public LanguageDetector(LanguageProperties props) {
    this.props = props;
  }

  private com.github.pemistahl.lingua.api.LanguageDetector lingua() {
    var l = lingua;
    if (l == null) {
      synchronized (this) {
        l = lingua;
        if (l == null) {
          LanguageDetectorBuilder b = LanguageDetectorBuilder.fromLanguages(LANGUAGES);
          if (!props.highAccuracy()) b = b.withLowAccuracyMode();
          lingua = l = b.build();
        }
      }
    }
    return l;
  }

  /** The most likely language of the text as it is, without any length threshold; null language when blank. */
  public Detection detect(@Nullable String text) {
    String t = clean(text);
    if (t.isEmpty()) return new Detection(null, 0f);
    Map<Language, Double> values = lingua().computeLanguageConfidenceValues(t);
    return values.entrySet().stream().findFirst()
        .filter(e -> e.getValue() > 0)
        .map(e -> new Detection(code(e.getKey()), e.getValue().floatValue()))
        .orElse(new Detection(null, 0f));
  }

  /** {@link #detect} of an abstract or body, null when it has fewer than {@code min-text-letters} letters. */
  public @Nullable Detection detectText(@Nullable String text) {
    String t = clean(text);
    if (letters(t) < props.minTextLetters()) return null;
    return detect(t);
  }

  /**
   * The language of a title and abstract: parallel texts are removed from the abstract first (exact paragraph
   * matches); the title decides when it is long enough and confident, else the abstract does; else null.
   */
  public @Nullable Detection titleAndAbstract(@Nullable String title, @Nullable String abstractText,
      List<String> parallelTexts) {
    String t = clean(title);
    if (titleQualifies(t)) {
      Detection d = detect(t);
      if (d.lang() != null && d.confidence() >= props.minConfidence()) return d;
    }
    Detection d = detectText(withoutParallels(abstractText, parallelTexts));
    if (d != null && d.lang() != null && d.confidence() >= props.minConfidence()) return d;
    return null;
  }

  private boolean titleQualifies(String t) {
    if (t.isEmpty()) return false;
    if (scriptLetters(t) >= MIN_SCRIPT_LETTERS) return true;
    return SPACES.split(t).length >= props.minTitleWords();
  }

  private static String withoutParallels(@Nullable String abstractText, List<String> parallels) {
    if (abstractText == null || abstractText.isBlank()) return "";
    if (parallels.isEmpty()) return abstractText;
    java.util.Set<String> drop = new java.util.HashSet<>();
    for (String p : parallels) {
      String n = normalise(p);
      if (!n.isEmpty()) drop.add(n);
      for (String para : p.split("\\R+")) {
        n = normalise(para);
        if (!n.isEmpty()) drop.add(n);
      }
    }
    List<String> kept = new ArrayList<>();
    for (String para : abstractText.split("\\R+")) {
      if (!drop.contains(normalise(para))) kept.add(para);
    }
    return String.join("\n", kept);
  }

  private static String normalise(String s) {
    return SPACES.matcher(MARKUP.matcher(s).replaceAll(" ")).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
  }

  private static String clean(@Nullable String s) {
    if (s == null) return "";
    return SPACES.matcher(MARKUP.matcher(s).replaceAll(" ")).replaceAll(" ").strip();
  }

  private static int letters(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      if (Character.isLetter(cp)) n++;
      i += Character.charCount(cp);
    }
    return n;
  }

  /** Letters of a script written without word spaces: Han, Hiragana, Katakana, Hangul, Thai. */
  private static int scriptLetters(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      if (Character.isLetter(cp)) {
        Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
        if (sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
            || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL
            || sc == Character.UnicodeScript.THAI) n++;
      }
      i += Character.charCount(cp);
    }
    return n;
  }

  private static String code(Language l) {
    return l.getIsoCode639_1().toString().toLowerCase(Locale.ROOT);
  }
}
