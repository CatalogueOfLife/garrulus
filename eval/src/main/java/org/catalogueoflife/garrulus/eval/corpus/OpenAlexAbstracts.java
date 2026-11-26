package org.catalogueoflife.garrulus.eval.corpus;

import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.doc.AbstractConverter;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an OpenAlex work (title plus abstract_inverted_index) into a {@link Document}. The curated selection and the
 * {@link OpenAlexAbstractRefetcher} both use this one function, so a re-fetched text is exactly the stored one while OpenAlex
 * serves the same title and abstract.
 */
public final class OpenAlexAbstracts {
  /** The OpenAlex fields this class reads. */
  public static final String SELECT = "id,doi,title,language,abstract_inverted_index,primary_location,publication_year";
  public static final String ID_PREFIX = "openalex:";

  private OpenAlexAbstracts() {}

  /** "openalex:W123" for https://openalex.org/W123. */
  public static String docId(JsonNode work) {
    String id = work.path("id").asString("");
    return ID_PREFIX + id.substring(id.lastIndexOf('/') + 1);
  }

  /** The bare OpenAlex id (W123) of a docId made by {@link #docId}. */
  public static String openAlexId(String docId) {
    if (!docId.startsWith(ID_PREFIX)) throw new IllegalArgumentException("Not an OpenAlex docId: " + docId);
    return docId.substring(ID_PREFIX.length());
  }

  /** A TITLE block and one ABSTRACT block, language from the work, the given meta; null if the work has no abstract text. */
  public static @Nullable Document document(JsonNode work, Map<String, String> meta) {
    JsonNode inv = work.path("abstract_inverted_index");
    if (inv.isMissingNode() || inv.isNull()) return null;
    Map<String, List<Integer>> index = FinderJson.mapper().convertValue(inv, new TypeReference<Map<String, List<Integer>>>() {});
    String abs = AbstractConverter.fromInvertedIndex(index);
    if (abs.isBlank()) return null;
    String title = work.path("title").isNull() ? null : work.path("title").asString(null);
    Document d = AbstractConverter.fromParts(docId(work), title, abs, null);
    if (d.blocks().stream().noneMatch(b -> b.type() == org.catalogueoflife.garrulus.finder.api.BlockType.ABSTRACT)) return null;
    String lang = work.path("language").isNull() ? null : work.path("language").asString(null);
    return new Document(d.id(), lang, d.blocks(), meta);
  }

  /** CC-BY and CC0 keep their label, any other licence is returned lower case, a missing one is "unknown". */
  public static String license(JsonNode work) {
    String l = work.path("primary_location").path("license").asString("").trim().toLowerCase(Locale.ROOT);
    String label = PensoftFetcher.licenseLabel(l);
    return label != null ? label : l.isEmpty() ? "unknown" : l;
  }

  public static boolean textMayBeStored(String license) {
    return license.equals("CC-BY") || license.equals("CC0");
  }
}
