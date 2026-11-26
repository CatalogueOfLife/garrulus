package org.catalogueoflife.garrulus.finder.pipeline;

import org.gbif.nameparser.api.Rank;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Multilingual words naming a taxonomic rank ("Family", "Familie", "Famille", ...). Case-insensitive. */
public final class RankWords {
  private RankWords() {}

  private static final Map<String, Rank> WORDS = new HashMap<>();

  private static void add(Rank rank, String... words) {
    for (String w : words) WORDS.put(w.toLowerCase(Locale.ROOT), rank);
  }

  static {
    add(Rank.KINGDOM, "Kingdom", "Regnum", "Reino", "Règne", "Reich");
    add(Rank.PHYLUM, "Phylum", "Division", "Divisio", "Filo", "Embranchement", "Stamm");
    add(Rank.CLASS, "Class", "Classis", "Clase", "Classe", "Klasse");
    add(Rank.ORDER, "Order", "Ordo", "Orden", "Ordre", "Ordem", "Ordnung");
    add(Rank.SUPERFAMILY, "Superfamily", "Superfamilia", "Superfamille", "Überfamilie");
    add(Rank.FAMILY, "Family", "Familia", "Famille", "Família", "Familie");
    add(Rank.SUBFAMILY, "Subfamily", "Subfamilia", "Sous-famille", "Subfamília", "Unterfamilie");
    add(Rank.TRIBE, "Tribe", "Tribus", "Tribu");
    add(Rank.GENUS, "Genus", "Género", "Gênero", "Genre", "Gattung");
  }

  public static Optional<Rank> rank(String word) {
    if (word == null) return Optional.empty();
    return Optional.ofNullable(WORDS.get(word.toLowerCase(Locale.ROOT)));
  }
}
