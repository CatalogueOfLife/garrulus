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
 * memoriam, also in German, French, Spanish, Portuguese and Russian) have no key: they never match.
 */
public final class TitleKey {

  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\p{M}]+");
  private static final int MIN_WORDS = 4;

  /** Generic title starts, normalised like a key; a title whose key starts with one of them as whole words has none. */
  private static final List<String> GENERIC = Stream.of(
      // erratum, corrigendum, correction
      "erratum", "errata", "fe de erratas", "fe de errata", "corrigendum", "corrigenda", "correction",
      "corrections", "korrigendum", "berichtigung", "korrektur", "corrección", "correccion", "correção", "correcao",
      "rectificatif", "опечатка", "опечатки", "исправление", "исправления", "поправка",
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
      "содержание").map(TitleKey::normalise).toList();

  private TitleKey() {
  }

  /** The key of the title, or null when the title is empty, shorter than four words or generic. */
  public static @Nullable String of(@Nullable String title) {
    String key = normalise(TitleText.clean(title));
    if (key == null || key.split(" ").length < MIN_WORDS) {
      return null;
    }
    for (String g : GENERIC) {
      if (key.equals(g) || key.startsWith(g + " ")) {
        return null;
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
