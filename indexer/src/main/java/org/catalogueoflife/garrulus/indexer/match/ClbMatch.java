package org.catalogueoflife.garrulus.indexer.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A CLB name usage match, reduced to what the index keeps. {@code taxonPath} is the usage id followed by the
 * classification ids in response order (top-level ancestors last); for a synonym the classification starts with the
 * accepted name. {@code type} is lower-case as CLB sends it; {@code code} is the upper-case NomCode name; {@code group} is the TaxGroup name when CLB sends one.
 */
public record ClbMatch(String type, @Nullable String usageId, @Nullable String usageName, @Nullable String usageRank,
    @Nullable String usageStatus, @Nullable String acceptedId, @Nullable String code, List<String> taxonPath,
    List<Taxon> classification, @Nullable String group) {

  public ClbMatch(String type, @Nullable String usageId, @Nullable String usageName, @Nullable String usageRank,
      @Nullable String usageStatus, @Nullable String acceptedId, @Nullable String code, List<String> taxonPath,
      List<Taxon> classification) {
    this(type, usageId, usageName, usageRank, usageStatus, acceptedId, code, taxonPath, classification, null);
  }

  public record Taxon(String id, String name, String rank) {}

  static ClbMatch parse(JsonNode root) {
    String type = text(root, "type");
    JsonNode u = root.path("usage");
    if (type == null) type = "none";
    if (!u.isObject() || text(u, "id") == null) {
      return new ClbMatch(type, null, null, null, null, null, null, List.of(), List.of(), null);
    }
    String id = text(u, "id");
    String status = text(u, "status");
    List<Taxon> cls = new ArrayList<>();
    for (JsonNode c : u.path("classification")) {
      String cid = text(c, "id");
      if (cid != null) cls.add(new Taxon(cid, text(c, "name"), text(c, "rank")));
    }
    List<String> path = new ArrayList<>();
    path.add(id);
    cls.forEach(t -> path.add(t.id()));
    boolean synonym = status != null && (status.contains("synonym") || status.contains("misapplied"));
    String accepted = synonym ? (cls.isEmpty() ? null : cls.get(0).id()) : id;
    String code = text(u, "code");
    return new ClbMatch(type, id, text(u, "name"), text(u, "rank"), status, accepted,
        code == null ? null : code.toUpperCase(Locale.ROOT), List.copyOf(path), List.copyOf(cls), text(u, "group"));
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isMissingNode() || v.isNull() ? null : v.asString();
  }
}
