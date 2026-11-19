package org.catalogueoflife.nametracker.store.work;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The key two records of the same paper share for a title match: the title cleaned by {@link TitleText}, NFKC,
 * lower-cased, every run of characters other than letters, digits and marks one space. Titles of fewer than four words
 * and generic titles (errata, corrigenda, corrections, editorials, book reviews, obituaries, indexes, contents, in
 * memoriam, also in German, French, Spanish, Portuguese and Russian; see {@link #of}) have no key: they never match.
 */
public final class TitleKey {

  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\p{M}]+");
  private static final int MIN_WORDS = 4;

  /** Titles of up to this many words that start with a generic term have no key; longer ones only in a notice form. */
  private static final int MAX_GENERIC_WORDS = 6;

  /** Erratum, corrigendum, correction: these also take a connector ("correction to …"). */
  private static final List<String> CORRECTIONS = Stream.of(
      "erratum", "errata", "fe de erratas", "fe de errata", "corrigendum", "corrigenda", "correction",
      "corrections", "korrigendum", "berichtigung", "korrektur", "corrección", "correccion", "correção", "correcao",
      "rectificatif", "опечатка", "опечатки", "исправление", "исправления", "поправка").map(TitleKey::normalise)
      .toList();

  /** The words after a correction term that make it a notice: "correction to", "Berichtigung zu", "поправка к". */
  private static final List<String> CONNECTORS = List.of("to", "zu", "zum", "zur", "à", "au", "aux", "al", "к");

  /** Generic title starts, normalised like a key. */
  private static final List<String> GENERIC = Stream.concat(CORRECTIONS.stream(), Stream.of(
      // editorial
      "editorial", "éditorial", "редакционная статья", "от редакции",
      // book review
      "book review", "book reviews", "buchbesprechung", "buchbesprechungen", "rezension", "rezensionen",
      "compte rendu", "comptes rendus", "analyse d ouvrage", "reseña", "reseñas", "resenha", "resenhas", "рецензия",
      "рецензии",
      // obituary, in memoriam
      "obituary", "obituaries", "nachruf", "nécrologie", "necrologie", "obituario", "necrología",
      "necrologia", "obituário", "некролог", "in memoriam", "zum gedenken", "памяти",
      // index, contents
      "index", "indices", "índice", "indice", "указатель", "contents", "table of contents", "inhalt",
      "inhaltsverzeichnis", "sommaire", "table des matières", "contenido", "sumario", "sumário", "conteúdo",
      "содержание").map(TitleKey::normalise)).toList();

  private TitleKey() {
  }

  /**
   * The key of the title, or null when the title is empty, shorter than four words or generic. A title is generic when
   * it starts with a generic term and has at most {@value #MAX_GENERIC_WORDS} words, when the part before its first
   * colon is a generic term ("Book review: …"), or when a correction term is followed by a connector ("Corrigendum to
   * …"). A longer title that merely starts with such a word ("Correction of the type locality of …", "Index to the
   * genera of …") keeps its key.
   */
  public static @Nullable String of(@Nullable String title) {
    String cleaned = TitleText.clean(title);
    String key = normalise(cleaned);
    int words = key == null ? 0 : key.split(" ").length;
    if (key == null || words < MIN_WORDS) {
      return null;
    }
    int colon = cleaned.indexOf(':');
    String head = colon > 0 ? normalise(cleaned.substring(0, colon)) : null;
    for (String g : GENERIC) {
      if (key.equals(g) || g.equals(head) || words <= MAX_GENERIC_WORDS && key.startsWith(g + " ")) {
        return null;
      }
    }
    for (String c : CORRECTIONS) {
      for (String to : CONNECTORS) {
        if (key.startsWith(c + " " + to + " ")) {
          return null;
        }
      }
    }
    return key;
  }

  private static @Nullable String normalise(@Nullable String s) {
    if (s == null) {
      return null;
    }
    String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    n = NON_WORD.matcher(n).replaceAll(" ").strip();
    return n.isEmpty() ? null : n;
  }
}
