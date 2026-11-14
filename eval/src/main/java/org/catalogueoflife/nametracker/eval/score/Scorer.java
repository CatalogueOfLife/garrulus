package org.catalogueoflife.nametracker.eval.score;

import org.catalogueoflife.nametracker.eval.corpus.GoldAct;
import org.catalogueoflife.nametracker.eval.corpus.GoldContext;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.api.RankedName;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Scores finder results against gold documents, micro-averaged over documents.
 *
 * <p>Names: span matching per {@link MatchMode}, one gold name to at most one mention; or, with documentLevel, the sets of
 * {@code fullName ?: text} against {@code Mention.name}. With span matching, a matched gold name that has a fullName is
 * also name-correct if the normalised {@code Mention.name} equals it ({@link EvalReport#nameCorrect()}). Acts: the pair (type, normalised name) per document. Contexts: the pair
 * (rank, normalised name) per document. A document without a result counts all its gold as missed.
 */
public final class Scorer {
  /** Document meta key holding the curated stratum (BROAD_EN, NON_EN, PENSOFT); it adds a stratum:X category to the name metrics. */
  public static final String STRATUM = "stratum";
  private static final int SAMPLE_CONTEXT = 40;
  private static final int MAX_SAMPLES = 5000;
  private static final Pattern WS = Pattern.compile("\\s+");
  private static final Pattern INITIAL = Pattern.compile("\\p{Lu}\\.(\\s.*)?");

  private Scorer() {}

  public static EvalReport score(List<GoldDocument> docs, Map<String, FinderResult> results, ScoreOptions opts) {
    return score(docs, results, opts, "", "", "A");
  }

  public static EvalReport score(List<GoldDocument> docs, Map<String, FinderResult> results, ScoreOptions opts,
                                 String finderName, String corpus, String mode) {
    return score(docs, results, opts, finderName, corpus, mode, Map.of());
  }

  /**
   * @param predicted by document id, the document the finder ran on when it is not the gold one (mode B: converted from
   *                  the PDF). Predicted mentions, acts and contexts are then looked up in its blocks: excluded block
   *                  types, the TITLE blocks of the main subject, error sample context. Only document-level name scoring
   *                  makes sense for such documents, since their spans cannot be compared with the gold spans.
   */
  public static EvalReport score(List<GoldDocument> docs, Map<String, FinderResult> results, ScoreOptions opts,
                                 String finderName, String corpus, String mode, Map<String, Document> predicted) {
    Acc names = new Acc();
    Map<String, Acc> cats = new TreeMap<>();
    NameAcc nameCorrect = new NameAcc();
    Acc acts = new Acc();
    Acc ctxs = new Acc();
    Acc subj = new Acc();
    List<ErrorSample> fps = new ArrayList<>();
    List<ErrorSample> fns = new ArrayList<>();
    FinderInfo info = null;
    String split = null;

    for (GoldDocument d : docs) {
      FinderResult r = results.get(d.docId());
      if (r != null && info == null) info = r.info();
      if (split == null) split = d.split();
      else if (!split.equals(d.split())) split = "mixed";
      String lang = d.document().language() == null ? "und" : d.document().language();
      String stratum = d.document().meta().get(STRATUM);
      Map<String, Block> blocks = blocks(d.document());
      Document p = predicted.get(d.docId());
      Map<String, Block> predBlocks = p == null ? blocks : blocks(p);

      List<GoldName> gold = d.names().stream().filter(n -> !excluded(blocks, n.blockId(), opts)).toList();
      List<Mention> pred = r == null ? List.of()
          : r.mentions().stream().filter(m -> !excluded(predBlocks, m.blockId(), opts)).toList();

      if (opts.documentLevel()) {
        scoreDocumentLevel(d, gold, pred, lang, stratum, names, cats, fps, fns);
      } else {
        scoreSpans(d, blocks, predBlocks, gold, pred, lang, stratum, opts.mode(), names, cats, nameCorrect, fps, fns);
      }
      scoreActs(d, r, opts, blocks, predBlocks, acts);
      scoreContexts(d, r, opts, blocks, predBlocks, ctxs);
      scoreSubjects(d, r, opts, predBlocks, subj);
    }
    Map<String, Metrics> byCat = new LinkedHashMap<>();
    cats.forEach((k, v) -> byCat.put(k, v.metrics()));
    return new EvalReport(finderName, info, corpus, split == null ? "" : split, mode, docs.size(), names.metrics(), byCat,
        opts.documentLevel() ? null : nameCorrect.overall, opts.documentLevel() ? Map.of() : nameCorrect.byKind, acts.metrics(),
        ctxs.metrics(), subj.metrics(), spread(fps), spread(fns), opts.mode(), opts.documentLevel(), List.of());
  }

  private static Map<String, Block> blocks(Document doc) {
    Map<String, Block> m = new HashMap<>();
    for (Block b : doc.blocks()) m.put(b.id(), b);
    return m;
  }

  private static boolean excluded(Map<String, Block> blocks, String blockId, ScoreOptions opts) {
    Block b = blocks.get(blockId);
    return b != null && opts.excludedBlocks().contains(b.type());
  }

  // ---- names

  private static void scoreSpans(GoldDocument d, Map<String, Block> blocks, Map<String, Block> predBlocks, List<GoldName> gold, List<Mention> pred,
                                 String lang, @Nullable String stratum, MatchMode mode, Acc names, Map<String, Acc> cats,
                                 NameAcc nameCorrect, List<ErrorSample> fps, List<ErrorSample> fns) {
    boolean[] goldUsed = new boolean[gold.size()];
    boolean[] predUsed = new boolean[pred.size()];
    // exact spans first so that overlap mode never steals an exact partner
    for (int pass = 0; pass < (mode == MatchMode.OVERLAP ? 2 : 1); pass++) {
      for (int g = 0; g < gold.size(); g++) {
        if (goldUsed[g]) continue;
        GoldName gn = gold.get(g);
        for (int p = 0; p < pred.size(); p++) {
          if (predUsed[p]) continue;
          Mention m = pred.get(p);
          if (!m.blockId().equals(gn.blockId())) continue;
          boolean hit = pass == 0 ? m.start() == gn.start() && m.end() == gn.end()
              : m.start() < gn.end() && gn.start() < m.end();
          if (hit) {
            goldUsed[g] = true;
            predUsed[p] = true;
            hitCats(cats, gn, blocks, lang, stratum);
            names.tp++;
            if (gn.fullName() != null) nameCorrect.add(goldKind(gn), norm(m.name()).equals(norm(gn.fullName())));
            break;
          }
        }
      }
    }
    for (int g = 0; g < gold.size(); g++) {
      if (goldUsed[g]) continue;
      GoldName gn = gold.get(g);
      names.fn++;
      for (String c : categories(goldKind(gn), blockType(blocks, gn.blockId()), lang, stratum)) cats.computeIfAbsent(c, k -> new Acc()).fn++;
      sample(fns, d.docId(), blocks.get(gn.blockId()), gn.blockId(), gn.start(), gn.end(), gn.text(), goldKind(gn).name());
    }
    for (int p = 0; p < pred.size(); p++) {
      if (predUsed[p]) continue;
      Mention m = pred.get(p);
      names.fp++;
      for (String c : categories(m.kind(), blockType(predBlocks, m.blockId()), lang, stratum)) cats.computeIfAbsent(c, k -> new Acc()).fp++;
      sample(fps, d.docId(), predBlocks.get(m.blockId()), m.blockId(), m.start(), m.end(), m.verbatim(), m.kind().name());
    }
  }

  private static void hitCats(Map<String, Acc> cats, GoldName gn, Map<String, Block> blocks, String lang, @Nullable String stratum) {
    for (String c : categories(goldKind(gn), blockType(blocks, gn.blockId()), lang, stratum)) cats.computeIfAbsent(c, k -> new Acc()).tp++;
  }

  private static void scoreDocumentLevel(GoldDocument d, List<GoldName> gold, List<Mention> pred, String lang, @Nullable String stratum, Acc names,
                                         Map<String, Acc> cats, List<ErrorSample> fps, List<ErrorSample> fns) {
    Map<String, NameKind> goldSet = new LinkedHashMap<>();
    for (GoldName g : gold) goldSet.putIfAbsent(norm(g.fullName() != null ? g.fullName() : g.text()), goldKind(g));
    Map<String, NameKind> predSet = new LinkedHashMap<>();
    for (Mention m : pred) predSet.putIfAbsent(norm(m.name()), m.kind());
    for (var e : goldSet.entrySet()) {
      if (predSet.containsKey(e.getKey())) {
        names.tp++;
        cats.computeIfAbsent("kind:" + e.getValue(), k -> new Acc()).tp++;
        cats.computeIfAbsent("lang:" + lang, k -> new Acc()).tp++;
        if (stratum != null) cats.computeIfAbsent("stratum:" + stratum, k -> new Acc()).tp++;
      } else {
        names.fn++;
        cats.computeIfAbsent("kind:" + e.getValue(), k -> new Acc()).fn++;
        cats.computeIfAbsent("lang:" + lang, k -> new Acc()).fn++;
        if (stratum != null) cats.computeIfAbsent("stratum:" + stratum, k -> new Acc()).fn++;
        add(fns, new ErrorSample(d.docId(), null, -1, -1, e.getKey(), e.getValue().name(), null));
      }
    }
    for (var e : predSet.entrySet()) {
      if (goldSet.containsKey(e.getKey())) continue;
      names.fp++;
      cats.computeIfAbsent("kind:" + e.getValue(), k -> new Acc()).fp++;
      cats.computeIfAbsent("lang:" + lang, k -> new Acc()).fp++;
      if (stratum != null) cats.computeIfAbsent("stratum:" + stratum, k -> new Acc()).fp++;
      add(fps, new ErrorSample(d.docId(), null, -1, -1, e.getKey(), e.getValue().name(), null));
    }
  }

  /** The kind a gold name would have as a finder mention, derived from its printed text. */
  static NameKind goldKind(GoldName g) {
    String t = g.text().trim();
    if (INITIAL.matcher(t).matches()) return NameKind.ABBREVIATED;
    int words = 0;
    for (String w : WS.split(t)) {
      if (!w.isEmpty() && !w.startsWith("(")) words++;
    }
    return words <= 1 ? NameKind.UNINOMIAL : words == 2 ? NameKind.BINOMIAL : NameKind.TRINOMIAL;
  }

  private static BlockType blockType(Map<String, Block> blocks, String id) {
    Block b = blocks.get(id);
    return b == null ? null : b.type();
  }

  private static List<String> categories(NameKind kind, BlockType block, String lang, @Nullable String stratum) {
    List<String> l = new ArrayList<>(4);
    if (stratum != null) l.add("stratum:" + stratum);
    l.add("kind:" + kind);
    if (block != null) l.add("block:" + block);
    l.add("lang:" + lang);
    return l;
  }

  // ---- acts, contexts, subjects

  private static void scoreActs(GoldDocument d, FinderResult r, ScoreOptions opts, Map<String, Block> blocks,
                                Map<String, Block> predBlocks, Acc acc) {
    Set<String> goldKeys = new LinkedHashSet<>();
    for (GoldAct a : d.acts()) {
      if (!excluded(blocks, a.blockId(), opts)) goldKeys.add(a.type() + "|" + norm(a.nameText()));
    }
    // each predicted act offers the printed and the expanded name as its key
    List<Set<String>> preds = new ArrayList<>();
    if (r != null) {
      Map<String, Mention> byId = new HashMap<>();
      for (Mention m : r.mentions()) byId.put(m.id(), m);
      Set<String> seen = new HashSet<>();
      for (var a : r.acts()) {
        Mention m = byId.get(a.mentionId());
        if (m == null || excluded(predBlocks, m.blockId(), opts)) continue;
        if (!seen.add(a.type() + "|" + norm(m.verbatim()))) continue;
        preds.add(new HashSet<>(List.of(a.type() + "|" + norm(m.verbatim()), a.type() + "|" + norm(m.name()))));
      }
    }
    for (String g : goldKeys) {
      if (preds.stream().anyMatch(p -> p.contains(g))) acc.tp++;
      else acc.fn++;
    }
    for (Set<String> p : preds) {
      if (p.stream().noneMatch(goldKeys::contains)) acc.fp++;
    }
  }

  private static void scoreContexts(GoldDocument d, FinderResult r, ScoreOptions opts, Map<String, Block> blocks,
                                    Map<String, Block> predBlocks, Acc acc) {
    Set<String> goldKeys = new LinkedHashSet<>();
    for (GoldContext c : d.contexts()) {
      if (excluded(blocks, c.blockId(), opts)) continue;
      for (RankedName rn : c.ranks()) goldKeys.add(rn.rank() + "|" + norm(rn.name()));
    }
    Set<String> predKeys = new LinkedHashSet<>();
    if (r != null) {
      for (ClassificationContext c : r.contexts()) {
        if (excluded(predBlocks, c.blockId(), opts)) continue;
        for (RankedName rn : c.ranks()) predKeys.add(rn.rank() + "|" + norm(rn.name()));
      }
    }
    tally(goldKeys, predKeys, acc);
  }

  private static void scoreSubjects(GoldDocument d, FinderResult r, ScoreOptions opts, Map<String, Block> blocks, Acc acc) {
    Set<String> goldSet = new LinkedHashSet<>();
    d.mainSubjectNames().forEach(n -> goldSet.add(norm(n)));
    Set<String> predSet = new LinkedHashSet<>();
    if (r != null) {
      Set<String> actMentions = new HashSet<>();
      r.acts().forEach(a -> actMentions.add(a.mentionId()));
      for (Mention m : r.mentions()) {
        if (excluded(blocks, m.blockId(), opts)) continue;
        Block b = blocks.get(m.blockId());
        if ((b != null && b.type() == BlockType.TITLE) || actMentions.contains(m.id())) predSet.add(norm(m.name()));
      }
    }
    tally(goldSet, predSet, acc);
  }

  private static void tally(Set<String> gold, Set<String> pred, Acc acc) {
    for (String g : gold) {
      if (pred.contains(g)) acc.tp++;
      else acc.fn++;
    }
    for (String p : pred) {
      if (!gold.contains(p)) acc.fp++;
    }
  }

  // ---- helpers

  static String norm(String s) {
    return WS.matcher(s).replaceAll(" ").trim();
  }

  private static void sample(List<ErrorSample> into, String docId, Block block, String blockId, int start, int end, String text,
                             String category) {
    String ctx = null;
    if (block != null) {
      String t = block.text();
      int len = CodePoints.length(t);
      int s = Math.max(0, Math.min(start, len));
      int e = Math.max(s, Math.min(end, len));
      ctx = CodePoints.slice(t, Math.max(0, s - SAMPLE_CONTEXT), s) + "«" + CodePoints.slice(t, s, e) + "»"
          + CodePoints.slice(t, e, Math.min(len, e + SAMPLE_CONTEXT));
      ctx = ctx.replaceAll("\\s+", " ");
    }
    add(into, new ErrorSample(docId, blockId, start, end, text, category, ctx));
  }

  /** Interleaves the samples round-robin over the documents, so that the first ones shown come from many documents. */
  static List<ErrorSample> spread(List<ErrorSample> in) {
    Map<String, List<ErrorSample>> byDoc = new LinkedHashMap<>();
    for (ErrorSample s : in) byDoc.computeIfAbsent(s.docId(), k -> new ArrayList<>()).add(s);
    List<ErrorSample> out = new ArrayList<>(in.size());
    for (int i = 0; out.size() < in.size(); i++) {
      for (List<ErrorSample> l : byDoc.values()) {
        if (i < l.size()) out.add(l.get(i));
      }
    }
    return out;
  }

  private static void add(List<ErrorSample> into, ErrorSample s) {
    if (into.size() < MAX_SAMPLES) into.add(s);
  }

  /** Name correctness of the matched spans, overall and per kind:X of the gold name. */
  private static final class NameAcc {
    NameAccuracy overall = new NameAccuracy(0, 0);
    final Map<String, NameAccuracy> byKind = new TreeMap<>();

    void add(NameKind kind, boolean ok) {
      overall = overall.plus(ok);
      byKind.compute("kind:" + kind, (k, v) -> (v == null ? new NameAccuracy(0, 0) : v).plus(ok));
    }
  }

  private static final class Acc {
    int tp;
    int fp;
    int fn;

    Metrics metrics() {
      return new Metrics(tp, fp, fn);
    }
  }
}
