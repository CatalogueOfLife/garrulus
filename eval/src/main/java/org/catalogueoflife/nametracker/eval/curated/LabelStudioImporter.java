package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.corpus.GoldAct;
import org.catalogueoflife.nametracker.eval.corpus.GoldContext;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.gbif.nameparser.api.Rank;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Label Studio JSON export into gold documents. Per task the latest annotation with was_cancelled=false counts; a task
 * without one is not annotated yet and skipped. Name regions become {@link GoldName}s, Act regions linked to a Name region by an
 * {@code act_of} relation become {@link GoldAct}s (type from the region's act_type choice), Context regions become
 * {@link GoldContext}s, and {@code main_subject=yes} on a Name adds its text to the main subject names. A task with
 * {@code doc=bad_text} is dropped. Documents whose licence forbids storing the text come back without text
 * ({@code textRetained=false}), ready to be written.
 *
 * <p>The ranks of a Context region come from its optional per-region text area "ranks" ("FAMILY: Opheliidae; ORDER:
 * Phyllodocida"), otherwise from the region text itself when it is a rank heading such as "Family Opheliidae". Anything else
 * fails with a message naming the document, so no context is silently guessed.
 */
public final class LabelStudioImporter {
  public static final String ACT_OF = "act_of";
  private static final System.Logger LOG = System.getLogger(LabelStudioImporter.class.getName());

  private LabelStudioImporter() {}

  public static List<GoldDocument> read(Path exportJson) {
    JsonNode tasks;
    try {
      tasks = FinderJson.mapper().readTree(Files.readString(exportJson));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!tasks.isArray()) throw new IllegalArgumentException(exportJson + " is not a Label Studio export: expected a JSON array of tasks");
    List<GoldDocument> out = new ArrayList<>();
    boolean[] utf16 = new boolean[1];
    for (JsonNode task : tasks) {
      JsonNode ann = latest(task.path("annotations"));
      if (ann == null) continue;
      GoldDocument g = convert(task.path("data"), ann.path("result"), utf16);
      if (g != null) out.add(g);
    }
    if (utf16[0]) {
      LOG.log(System.Logger.Level.INFO, "Label Studio offsets in {0} matched only as UTF-16 units and were converted to code points", exportJson);
    }
    return out;
  }

  private static JsonNode latest(JsonNode annotations) {
    JsonNode best = null;
    for (JsonNode a : annotations) {
      if (a.path("was_cancelled").asBoolean(false)) continue;
      if (best == null || key(a).compareTo(key(best)) > 0) best = a;
    }
    return best;
  }

  /** Orders by update time, then id; the id is zero padded so that 10 sorts after 9. */
  private static String key(JsonNode a) {
    return a.path("updated_at").asString(a.path("created_at").asString("")) + "|" + String.format("%012d", a.path("id").asLong(0));
  }

  private static GoldDocument convert(JsonNode data, JsonNode results, boolean[] utf16) {
    String docId = data.path("docId").asString("");
    Document doc = FinderJson.mapper().convertValue(data.path("document"), Document.class);
    FlatText flat = FlatText.of(doc);
    if (!flat.text().equals(data.path("text").asString(""))) {
      throw new IllegalStateException("Task " + docId + ": data.text does not match the text of data.document");
    }

    Map<String, JsonNode> regions = new LinkedHashMap<>();
    Map<String, String> actTypes = new LinkedHashMap<>();
    Set<String> mainSubject = new LinkedHashSet<>();
    Map<String, String> ranks = new LinkedHashMap<>();
    List<String[]> relations = new ArrayList<>();
    boolean bad = false;
    for (JsonNode r : results) {
      String type = r.path("type").asString("");
      String from = r.path("from_name").asString("");
      switch (type) {
        case "labels" -> regions.put(r.path("id").asString(), r);
        case "choices" -> {
          List<String> choices = strings(r.path("value").path("choices"));
          if (from.equals("doc")) bad |= choices.contains("bad_text");
          else if (from.equals("act_type") && !choices.isEmpty()) actTypes.put(r.path("id").asString(), choices.get(0));
          else if (from.equals("main_subject") && choices.contains("yes")) mainSubject.add(r.path("id").asString());
        }
        case "textarea" -> {
          if (from.equals("ranks")) ranks.put(r.path("id").asString(), String.join(";", strings(r.path("value").path("text"))));
        }
        case "relation" -> {
          List<String> labels = strings(r.path("labels"));
          relations.add(new String[] {r.path("from_id").asString(), r.path("to_id").asString(), labels.isEmpty() ? "" : labels.get(0)});
        }
        default -> { }
      }
    }
    if (bad) return null;

    List<GoldName> names = new ArrayList<>();
    List<GoldAct> acts = new ArrayList<>();
    Map<String, List<RankedName>> contexts = new LinkedHashMap<>();
    Set<String> subjects = new LinkedHashSet<>();
    Map<String, GoldName> nameById = new LinkedHashMap<>();
    for (var e : regions.entrySet()) {
      if (label(e.getValue()).equals("Name")) {
        GoldName n = span(docId, flat, utf16, e.getValue(), (b, s, en, t) -> new GoldName(b, s, en, t, null, null));
        names.add(n);
        nameById.put(e.getKey(), n);
        if (mainSubject.contains(e.getKey())) subjects.add(n.text().replaceAll("\\s+", " ").trim());
      }
    }
    for (var e : regions.entrySet()) {
      String id = e.getKey();
      String label = label(e.getValue());
      if (label.equals("Act")) {
        String choice = actTypes.get(id);
        if (choice == null) throw fail(docId, "the Act region '" + text(e.getValue()) + "' has no act_type choice");
        ActType type = actType(choice);
        if (type == null) throw fail(docId, "unknown act_type '" + choice + "'");
        GoldName found = null;
        for (String[] rel : relations) {
          if (!rel[2].equals(ACT_OF)) continue;
          String other = rel[0].equals(id) ? rel[1] : rel[1].equals(id) ? rel[0] : null;
          if (other != null && nameById.containsKey(other)) {
            if (found != null && found != nameById.get(other)) {
              throw fail(docId, "the Act region '" + text(e.getValue()) + "' (id " + id + ") has more than one act_of relation to a Name");
            }
            found = nameById.get(other);
          }
        }
        if (found == null) throw fail(docId, "the Act region '" + text(e.getValue()) + "' is not linked to a Name by act_of");
        GoldName target = found;
        acts.add(span(docId, flat, utf16, e.getValue(), (b, s, en, t) -> new GoldAct(b, s, en, type, target.text())));
      } else if (label.equals("Context")) {
        GoldContext c = span(docId, flat, utf16, e.getValue(), (b, s, en, t) -> new GoldContext(b, rankedNames(docId, t, ranks.get(id))));
        contexts.merge(c.blockId(), c.ranks(), (a, b) -> {
          List<RankedName> all = new ArrayList<>(a);
          all.addAll(b);
          return all;
        });
      } else if (!label.equals("Name")) {
        throw fail(docId, "unknown label '" + label + "'");
      }
    }
    List<GoldContext> ctx = contexts.entrySet().stream().map(e -> new GoldContext(e.getKey(), e.getValue())).toList();
    String license = data.path("license").asString("unknown");
    GoldDocument g = new GoldDocument(docId, "curated", data.path("split").asString("test"), license, true,
        GoldDocument.textHash(doc), doc, names, acts, ctx, subjects);
    return data.path("textRetained").asBoolean(true) ? g : g.withoutText();
  }

  private interface Make<T> {
    T make(String blockId, int start, int end, String text);
  }

  /**
   * Maps a region to block offsets. Label Studio offsets are read as code points first; if value.text then differs from the
   * text at that range they are tried as UTF-16 units (what a JavaScript string index is) and converted. The interpretation
   * whose text equals value.text wins; if neither does, the import fails. Without value.text nothing can be verified and code
   * points are used.
   */
  private static <T> T span(String docId, FlatText flat, boolean[] utf16, JsonNode region, Make<T> make) {
    JsonNode v = region.path("value");
    String regionId = region.path("id").asString("?");
    String text = flat.text();
    int total = CodePoints.length(text);
    int start = v.path("start").asInt();
    int end = v.path("end").asInt();
    String given = v.path("text").isMissingNode() || v.path("text").isNull() ? null : v.path("text").asString();
    boolean cpOk = start >= 0 && start <= end && end <= total;
    if (cpOk && (given == null || CodePoints.slice(text, start, end).equals(given))) {
      return place(docId, flat, regionId, start, end, make);
    }
    if (given != null && start >= 0 && start <= end && end <= text.length()) {
      int cs = CodePoints.toCodePoint(text, start);
      int ce = CodePoints.toCodePoint(text, end);
      if (CodePoints.slice(text, cs, ce).equals(given)) {
        utf16[0] = true;
        return place(docId, flat, regionId, cs, ce, make);
      }
    }
    throw fail(docId, "region " + regionId + " has text '" + (given == null ? "" : given.replace('\n', ' ')) + "' at " + start + "-" + end
        + ", which matches the document neither as code point nor as UTF-16 offsets");
  }

  private static <T> T place(String docId, FlatText flat, String regionId, int start, int end, Make<T> make) {
    FlatText.Position a;
    FlatText.Position b;
    try {
      a = flat.toBlock(start);
      b = flat.toBlock(end);
    } catch (IllegalArgumentException e) {
      throw fail(docId, "region " + regionId + " at " + start + "-" + end + " is outside the text");
    }
    if (!a.blockId().equals(b.blockId())) {
      throw fail(docId, "region " + regionId + " spans more than one block; tag each block separately");
    }
    return make.make(a.blockId(), a.offset(), b.offset(), CodePoints.slice(flat.text(), start, end));
  }

  private static String label(JsonNode region) {
    List<String> l = strings(region.path("value").path("labels"));
    return l.isEmpty() ? "" : l.get(0);
  }

  private static String text(JsonNode region) {
    return region.path("value").path("text").asString("");
  }

  private static List<String> strings(JsonNode arr) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : arr) out.add(n.asString(""));
    return out;
  }

  private static IllegalStateException fail(String docId, String msg) {
    return new IllegalStateException("Task " + docId + ": " + msg);
  }

  private static String norm(String s) {
    return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
  }

  /** Accepts the enum name (SP_NOV) and the printed marker ("sp. nov."). */
  static ActType actType(String choice) {
    String n = norm(choice);
    for (ActType t : ActType.values()) {
      if (norm(t.name()).equals(n)) return t;
    }
    return null;
  }

  private static List<RankedName> rankedNames(String docId, String regionText, String explicit) {
    List<RankedName> out = new ArrayList<>();
    if (explicit != null && !explicit.isBlank()) {
      for (String part : explicit.split("[;\n]")) {
        if (part.isBlank()) continue;
        int i = part.indexOf(':');
        if (i < 0) i = part.indexOf('=');
        if (i < 0) throw fail(docId, "bad ranks entry '" + part.strip() + "', expected RANK: Name");
        out.add(new RankedName(rank(docId, part.substring(0, i)), part.substring(i + 1).strip()));
      }
      return out;
    }
    String t = regionText.strip();
    int sp = t.indexOf(' ');
    Rank r = sp > 0 ? tryRank(t.substring(0, sp)) : null;
    if (r == null) {
      throw fail(docId, "the Context region '" + t + "' needs ranks: add a ranks text such as 'FAMILY: Opheliidae; ORDER: Phyllodocida'");
    }
    out.add(new RankedName(r, t.substring(sp + 1).strip()));
    return out;
  }

  private static Rank tryRank(String s) {
    try {
      return Rank.valueOf(s.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Rank rank(String docId, String s) {
    Rank r = tryRank(s);
    if (r == null) throw fail(docId, "unknown rank '" + s.strip() + "'");
    return r;
  }
}
