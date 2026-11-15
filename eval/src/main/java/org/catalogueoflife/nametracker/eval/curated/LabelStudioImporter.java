package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.corpus.GoldAct;
import org.catalogueoflife.nametracker.eval.corpus.GoldContext;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.catalogueoflife.nametracker.finder.pipeline.ActPatterns;
import org.catalogueoflife.nametracker.finder.pipeline.SuffixRules;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Label Studio JSON export into gold documents. Per task the latest annotation with was_cancelled=false counts; a task
 * without one is not annotated yet and skipped. Name regions become {@link GoldName}s, Act regions linked to a Name region by an
 * {@code act_of} relation become {@link GoldAct}s (type from the region's act_type choice), Context regions become
 * {@link GoldContext}s, and {@code main_subject=yes} on a Name adds its text to the main subject names. An Authorship region
 * becomes the {@link GoldName#authorship()} of the Name region that ends right before it in the same block, with nothing but
 * spaces and commas in between; an Authorship without such a Name, or a second one on the same Name, fails. A task with
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

  /** A region a lenient read left out, with the reason. */
  public record Removed(String docId, String regionId, String label, String regionText, String reason) {}

  /** One value the importer filled in because the annotation left it out. {@code gap} is only set for act_of. */
  public record Inferred(String docId, String regionId, String kind, String regionText, String value, String gap) {}

  /**
   * Inference settings. {@link #STRICT} infers nothing. Otherwise a missing act_type, act_of link or Context ranks are filled
   * in where unambiguous, and each inferred value is added to {@link #inferred()}. The dictionary is optional.
   */
  public static final class Options {
    public static final Options STRICT = new Options(false, null, false);
    private final boolean infer;
    private final ColDictionary dictionary;
    private final boolean lenient;
    private final List<Inferred> inferred = new ArrayList<>();
    private final List<Removed> removed = new ArrayList<>();

    private Options(boolean infer, ColDictionary dictionary, boolean lenient) {
      this.infer = infer;
      this.dictionary = dictionary;
      this.lenient = lenient;
    }

    public static Options inferring(ColDictionary dictionary) {
      return new Options(true, dictionary, false);
    }

    /**
     * Like {@link #inferring}, but an Act that cannot be resolved (no type, no unambiguous Name) and an Authorship that follows
     * no Name are left out of the result instead of failing; they are listed in {@link #removed()}. For wiring tests only.
     */
    public static Options lenient(ColDictionary dictionary) {
      return new Options(true, dictionary, true);
    }

    /** The regions a lenient read left out. */
    public List<Removed> removed() {
      return removed;
    }

    public List<Inferred> inferred() {
      return inferred;
    }
  }

  /** Strict: nothing is inferred, a missing act_type, act_of link or Context ranks fails. */
  public static List<GoldDocument> read(Path exportJson) {
    return read(exportJson, Options.STRICT);
  }

  public static List<GoldDocument> read(Path exportJson, Options opts) {
    JsonNode tasks;
    try {
      tasks = FinderJson.mapper().readTree(Files.readString(exportJson));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!tasks.isArray()) throw new IllegalArgumentException(exportJson + " is not a Label Studio export: expected a JSON array of tasks");
    List<GoldDocument> out = new ArrayList<>();
    boolean[] utf16 = new boolean[1];
    List<String> errors = new ArrayList<>();
    for (JsonNode task : tasks) {
      JsonNode ann = latest(task.path("annotations"));
      if (ann == null) continue;
      try {
        GoldDocument g = convert(task.path("data"), ann.path("result"), utf16, opts);
        if (g != null) out.add(g);
      } catch (IllegalStateException e) {
        errors.add(e.getMessage());
      }
    }
    if (errors.size() == 1) throw new IllegalStateException(errors.get(0));
    if (!errors.isEmpty()) {
      throw new IllegalStateException(errors.size() + " tasks failed:\n" + String.join("\n", errors));
    }
    if (utf16[0]) {
      LOG.log(System.Logger.Level.INFO, "Label Studio offsets in {0} matched only as UTF-16 units and were converted to code points", exportJson);
    }
    return out;
  }

  static JsonNode latest(JsonNode annotations) {
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

  private static GoldDocument convert(JsonNode data, JsonNode results, boolean[] utf16, Options opts) {
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
    Map<String, GoldName> authorships = new LinkedHashMap<>();
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
        try {
        String choice = actTypes.get(id);
        ActType type;
        if (choice == null) {
          type = opts.infer ? inferActType(text(e.getValue())) : null;
          if (type == null) throw fail(docId, "the Act region '" + text(e.getValue()) + "' has no act_type choice");
          opts.inferred.add(new Inferred(docId, id, "act_type", text(e.getValue()), type.name(), ""));
        } else {
          type = actType(choice);
          if (type == null) throw fail(docId, "unknown act_type '" + choice + "'");
        }
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
        GoldName span = span(docId, flat, utf16, e.getValue(), (b, s, en, t) -> new GoldName(b, s, en, t, null, null));
        if (found == null && opts.infer) {
          found = nearestName(doc, span, names, regions, flat, utf16, docId);
          if (found != null) {
            opts.inferred.add(new Inferred(docId, id, "act_of", text(e.getValue()), found.text(),
                CodePoints.slice(blockText(doc, span.blockId()), found.end(), span.start())));
          }
        }
        if (found == null) throw fail(docId, "the Act region '" + text(e.getValue()) + "' is not linked to a Name by act_of");
        GoldName target = found;
        ActType fType = type;
        acts.add(new GoldAct(span.blockId(), span.start(), span.end(), fType, target.text()));
        } catch (IllegalStateException ex) {
          if (!opts.lenient) throw ex;
          opts.removed.add(new Removed(docId, id, "Act", text(e.getValue()), ex.getMessage()));
        }
      } else if (label.equals("Context")) {
        GoldContext c = span(docId, flat, utf16, e.getValue(), (b, s, en, t) -> new GoldContext(b, rankedNames(docId, id, t, ranks.get(id), opts)));
        contexts.merge(c.blockId(), c.ranks(), (a, b) -> {
          List<RankedName> all = new ArrayList<>(a);
          all.addAll(b);
          return all;
        });
      } else if (label.equals("Authorship")) {
        authorships.put(id, span(docId, flat, utf16, e.getValue(), (b, s, en, t) -> new GoldName(b, s, en, t, null, null)));
      } else if (!label.equals("Name")) {
        throw fail(docId, "unknown label '" + label + "'");
      }
    }
    attachAuthorships(docId, doc, names, authorships, opts);
    List<GoldContext> ctx = contexts.entrySet().stream().map(e -> new GoldContext(e.getKey(), e.getValue())).toList();
    String license = data.path("license").asString("unknown");
    GoldDocument g = new GoldDocument(docId, "curated", data.path("split").asString("test"), license, true,
        GoldDocument.textHash(doc), doc, names, acts, ctx, subjects);
    return data.path("textRetained").asBoolean(true) ? g : g.withoutText();
  }

  private static final java.util.regex.Pattern AUTHORSHIP_GAP = java.util.regex.Pattern.compile("[\\s,]*");

  /** Sets each authorship (held as a span) on the name that ends closest before it in its block, across spaces and commas only. */
  private static void attachAuthorships(String docId, Document doc, List<GoldName> names, Map<String, GoldName> authorships, Options opts) {
    Map<String, String> texts = new LinkedHashMap<>();
    for (Block b : doc.blocks()) texts.put(b.id(), b.text());
    for (var ae : authorships.entrySet()) {
      GoldName a = ae.getValue();
      try {
      int best = -1;
      for (int i = 0; i < names.size(); i++) {
        GoldName n = names.get(i);
        if (!n.blockId().equals(a.blockId()) || n.end() > a.start()) continue;
        if (best >= 0 && names.get(best).end() >= n.end()) continue;
        if (AUTHORSHIP_GAP.matcher(CodePoints.slice(texts.get(a.blockId()), n.end(), a.start())).matches()) best = i;
      }
      if (best < 0) {
        throw fail(docId, "the Authorship region '" + a.text() + "' does not follow a Name region (only spaces or a comma may lie between)");
      }
      GoldName n = names.get(best);
      if (n.authorship() != null) {
        throw fail(docId, "the Name '" + n.text() + "' has more than one Authorship region ('" + n.authorship() + "', '" + a.text() + "')");
      }
      names.set(best, n.withAuthorship(a.text()));
      } catch (IllegalStateException ex) {
        if (!opts.lenient) throw ex;
        opts.removed.add(new Removed(docId, ae.getKey(), "Authorship", a.text(), ex.getMessage()));
      }
    }
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

  /** Maximum number of code points between the end of a Name and the start of its Act for an inferred link. */
  static final int MAX_LINK_GAP = 80;

  private static String blockText(Document doc, String blockId) {
    for (Block b : doc.blocks()) if (b.id().equals(blockId)) return b.text();
    return "";
  }

  /**
   * The Name region that ends closest before the act in the same block, if no other Name or Act region starts in between and
   * at most {@link #MAX_LINK_GAP} code points lie between its end and the act's start.
   */
  private static GoldName nearestName(Document doc, GoldName act, List<GoldName> names, Map<String, JsonNode> regions, FlatText flat,
                                      boolean[] utf16, String docId) {
    GoldName best = null;
    for (GoldName n : names) {
      if (!n.blockId().equals(act.blockId()) || n.end() > act.start()) continue;
      if (best == null || n.end() > best.end()) best = n;
    }
    if (best == null || act.start() - best.end() > MAX_LINK_GAP) return null;
    // another Name or Act region between them (a name that starts after best's end, or another act) blocks the link
    for (var r : regions.values()) {
      String l = label(r);
      if (!l.equals("Name") && !l.equals("Act")) continue;
      GoldName o = span(docId, flat, utf16, r, (b, s, en, t) -> new GoldName(b, s, en, t, null, null));
      if (o.start() == act.start() && o.end() == act.end() && o.blockId().equals(act.blockId())) continue;
      if (o.blockId().equals(act.blockId()) && o.start() >= best.end() && o.start() < act.start() && !(o.start() == best.start() && o.end() == best.end())) {
        return null;
      }
    }
    return best;
  }

  private static final Pattern HIGHER_ABBREV = Pattern.compile(
      "(?:cl|subcl|infracl|supercl|ord|subord|infraord|superord|phyl|subphyl|infraphyl|superphyl|k|regn|subk|infrak|parvk|superk|div)"
          + "\\.?\\s*(?:n|nov)\\.?");

  /** From the marker text: ActPatterns when it yields exactly one type, else a small table; null when still unknown. */
  static ActType inferActType(String regionText) {
    String t = regionText.strip();
    Set<ActType> types = new LinkedHashSet<>();
    for (ActPatterns.ActMatch m : ActPatterns.find(t)) types.add(m.type());
    if (types.size() == 1) return types.iterator().next();
    if (!types.isEmpty()) return null;
    String lower = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    if (lower.matches("nom\\.? ?rest\\.?")) return ActType.STAT_REV;
    if (lower.equals("replacement name")) return ActType.NOM_NOV;
    if (HIGHER_ABBREV.matcher(lower).matches()) return ActType.HIGHER_NOV;
    return null;
  }

  private static final Pattern RANK_PAIR = Pattern.compile("([\\p{L}]+)\\s*:\\s*(.+)");

  private static List<RankedName> rankedNames(String docId, String regionId, String regionText, String explicit, Options opts) {
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
    if (r != null) {
      out.add(new RankedName(r, t.substring(sp + 1).strip()));
      return out;
    }
    if (opts.infer) {
      List<RankedName> inferred = inferRanks(t, opts.dictionary);
      if (!inferred.isEmpty()) {
        opts.inferred.add(new Inferred(docId, regionId, "ranks", t,
            inferred.stream().map(n -> n.rank() + ": " + n.name()).collect(java.util.stream.Collectors.joining("; ")), ""));
        return inferred;
      }
    }
    throw fail(docId, "the Context region '" + t + "' needs ranks: add a ranks text such as 'FAMILY: Opheliidae; ORDER: Phyllodocida'");
  }

  /** "rank: Name" pairs when every part is one, else the names split on ":" or "," with a dictionary or suffix rank each. */
  static List<RankedName> inferRanks(String text, ColDictionary dict) {
    List<RankedName> pairs = new ArrayList<>();
    boolean all = true;
    for (String part : text.split("[;,]")) {
      if (part.isBlank()) continue;
      Matcher m = RANK_PAIR.matcher(part.strip());
      Rank r = m.matches() ? tryRank(m.group(1)) : null;
      if (r == null) {
        all = false;
        break;
      }
      pairs.add(new RankedName(r, m.group(2).strip()));
    }
    if (all && !pairs.isEmpty()) return pairs;
    List<RankedName> out = new ArrayList<>();
    for (String name : text.split("[:,]")) {
      String n = name.strip();
      if (n.isEmpty()) continue;
      Rank rk = null;
      if (dict != null) rk = dict.isGenus(n) ? Rank.GENUS : dict.uninomialRank(n).orElse(null);
      if (rk == null) rk = SuffixRules.rankFor(n, null).orElse(Rank.UNRANKED);
      out.add(new RankedName(rk, n));
    }
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
