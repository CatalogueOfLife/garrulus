package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.eval.corpus.CorpusManifest;
import org.catalogueoflife.nametracker.eval.corpus.CorpusRebuilder;
import org.catalogueoflife.nametracker.eval.corpus.CorpusStore;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.curated.CuratedManifest;
import org.catalogueoflife.nametracker.eval.curated.CuratedSelector;
import org.catalogueoflife.nametracker.eval.curated.LabelStudioExporter;
import org.catalogueoflife.nametracker.eval.curated.LabelStudioImporter;
import org.catalogueoflife.nametracker.eval.curated.PreTagger;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.eval.corpus.OpenAlexClient;
import org.catalogueoflife.nametracker.eval.corpus.PensoftFetcher;
import org.catalogueoflife.nametracker.eval.score.EvalReport;
import org.catalogueoflife.nametracker.eval.score.MatchMode;
import org.catalogueoflife.nametracker.eval.score.Metrics;
import org.catalogueoflife.nametracker.eval.score.ReportWriter;
import org.catalogueoflife.nametracker.eval.score.ScoreOptions;
import org.catalogueoflife.nametracker.eval.score.Scorer;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/** The evaluation command line. More subcommands arrive with later tasks. */
@Command(name = "eval", mixinStandardHelpOptions = true, subcommands = {EvalCli.FetchPensoft.class, EvalCli.RebuildPensoft.class, EvalCli.Run.class, EvalCli.CuratedSelect.class,
    EvalCli.LsExport.class, EvalCli.LsImport.class, EvalCli.BuildDictionary.class, EvalCli.FetchPdf.class, EvalCli.Tune.class},
    description = "Name finder evaluation")
public class EvalCli implements Runnable {
  public static void main(String[] args) {
    System.exit(commandLine().execute(args));
  }

  static CommandLine commandLine() {
    CommandLine cl = new CommandLine(new EvalCli());
    cl.setExecutionExceptionHandler((ex, c, pr) -> {
      System.err.println("Error: " + ex.getMessage());
      return 1;
    });
    return cl;
  }

  @Override
  public void run() {
    new CommandLine(this).usage(System.out);
  }

  @Command(name = "fetch-pensoft", mixinStandardHelpOptions = true,
      description = "Samples open access Pensoft articles via OpenAlex, downloads their TaxPub XML and builds the silver corpus")
  public static class FetchPensoft implements Callable<Integer> {
    @Option(names = "--n", required = true, description = "number of documents") int n;
    @Option(names = "--dev", required = true, description = "how many of them go to the dev split") int dev;
    @Option(names = "--seed", required = true, description = "seed of the deterministic shuffle") long seed;
    @Option(names = "--out", required = true, description = "output directory") Path out;

    @Override
    public Integer call() throws Exception {
      if (dev < 0 || dev > n) throw new IllegalArgumentException("--dev must be between 0 and --n");
      HttpClient http = PensoftFetcher.defaultClient();
      PensoftFetcher fetcher = new PensoftFetcher(http, new OpenAlexClient(http, System.getenv("OPENALEX_API_KEY")));
      List<CorpusManifest.Row> candidates = fetcher.candidates(seed);
      System.err.println(candidates.size() + " candidate documents with a CC-BY or CC0 licence");
      List<CorpusManifest.Row> rows = new ArrayList<>();
      List<GoldDocument> docs = new ArrayList<>();
      int skipped = 0;
      for (CorpusManifest.Row cand : candidates) {
        if (docs.size() >= n) break;
        // the split depends on the number of successes so far, so skipped documents do not unbalance it
        CorpusManifest.Row row = cand.withSplit(docs.size() < dev ? "dev" : "test");
        try {
          docs.add(fetcher.fetch(row));
          rows.add(row);
          if (docs.size() % 25 == 0) System.err.println(docs.size() + "/" + n + " fetched");
        } catch (RuntimeException e) {
          skipped++;
          System.err.println("skipping " + row.doi() + ": " + e.getMessage());
        }
      }
      Files.createDirectories(out);
      new CorpusManifest(rows).write(out.resolve("manifest.tsv"));
      CorpusStore.write(out.resolve("gold.jsonl.gz"), docs);
      System.err.println("Wrote " + docs.size() + " documents to " + out + ", skipped " + skipped
          + (docs.size() < n ? "; only " + docs.size() + " of " + n + " could be built, candidates ran out" : ""));
      return 0;
    }
  }

  @Command(name = "rebuild-pensoft", mixinStandardHelpOptions = true,
      description = "Re-fetches the TaxPub XML of every manifest row and re-extracts the gold with the current code, without resampling")
  public static class RebuildPensoft implements Callable<Integer> {
    @Option(names = "--corpus", required = true, description = "corpus directory holding manifest.tsv and gold.jsonl.gz") Path corpus;

    @Override
    public Integer call() throws Exception {
      CorpusManifest manifest = CorpusManifest.read(corpus.resolve("manifest.tsv"));
      Path gold = corpus.resolve("gold.jsonl.gz");
      List<GoldDocument> old = Files.exists(gold) ? CorpusStore.read(gold, null) : List.of();
      HttpClient http = PensoftFetcher.defaultClient();
      PensoftFetcher fetcher = new PensoftFetcher(http, new OpenAlexClient(http, System.getenv("OPENALEX_API_KEY")));
      System.err.println("Rebuilding " + manifest.rows().size() + " documents of " + corpus);
      CorpusRebuilder.Result r = CorpusRebuilder.rebuild(manifest, old, fetcher::fetch, System.err::println);
      CorpusStore.write(gold, r.documents());
      CorpusRebuilder.Stats s = r.stats();
      System.out.println("documents: " + s.documents() + " in manifest, " + s.rebuilt() + " rebuilt, " + s.keptOld()
          + " kept from the previous corpus" + (s.failed().isEmpty() ? "" : " " + s.failed()));
      System.out.println("documents changed: " + s.documentsChanged() + " (text changed in " + s.textChanged() + ")");
      System.out.println("names: " + s.oldNames() + " -> " + s.newNames() + "; changed " + s.namesChanged() + ", added "
          + s.namesAdded() + ", removed " + s.namesRemoved() + " (name texts per block, compared as multisets)");
      System.out.println("acts: " + s.oldActs() + " -> " + s.newActs());
      System.out.println("Wrote " + gold);
      return 0;
    }
  }

  @Command(name = "build-dictionary", mixinStandardHelpOptions = true,
      description = "Builds the COL XR word dictionary (dictionary-<releaseKey>.bin) by streaming NameUsage.tsv from the XR zip")
  public static class BuildDictionary implements Callable<Integer> {
    @Option(names = "--xr", required = true, description = "XR zip: an http(s) URL (streamed) or a local path") String xr;
    @Option(names = "--out", required = true, description = "output directory") Path out;

    @Override
    public Integer call() throws Exception {
      Files.createDirectories(out);
      long t0 = System.nanoTime();
      CommonWords common =
          CommonWords.load(CommonWords.DEFAULT_LANGS);
      ColDictionary d;
      if (xr.startsWith("http://") || xr.startsWith("https://")) {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(xr))
            .header("User-Agent", "Garrulus-eval/0.1 (+https://www.catalogueoflife.org)").GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + xr);
        try (InputStream in = resp.body()) {
          d = ColDictionaryBuilder.fromXrZip(in, common);
        }
      } else {
        d = ColDictionaryBuilder.fromXrZip(Path.of(xr), common);
      }
      double buildSec = (System.nanoTime() - t0) / 1e9;
      Path file = out.resolve("dictionary-" + d.releaseKey() + ".bin");
      ColDictionaryIO.write(d, file);
      long t1 = System.nanoTime();
      ColDictionary back = ColDictionaryIO.read(file);
      double loadSec = (System.nanoTime() - t1) / 1e9;
      System.out.println(back.stats());
      System.out.printf(Locale.ROOT, "file %s, %.1f MB; built in %.0f s, loaded in %.2f s%n",
          file, Files.size(file) / 1048576.0, buildSec, loadSec);
      return 0;
    }
  }

  /** The documents of one split of a corpus; curated abstracts stored without text are re-fetched from OpenAlex. */
  static List<GoldDocument> readSplit(Path corpus, @org.jspecify.annotations.Nullable String split) throws IOException {
    HttpClient http = PensoftFetcher.defaultClient();
    List<GoldDocument> docs = CorpusStore.read(corpus.resolve("gold.jsonl.gz"),
        new org.catalogueoflife.nametracker.eval.corpus.OpenAlexAbstractRefetcher(new OpenAlexClient(http, System.getenv("OPENALEX_API_KEY")))).stream()
        .filter(d -> split == null || split.equals(d.split())).toList();
    if (docs.isEmpty()) throw new IllegalArgumentException("No documents" + (split == null ? "" : " in split " + split) + " in " + corpus);
    return docs;
  }

  static void checkSplit(String split) {
    if (!split.equals("dev") && !split.equals("test")) throw new IllegalArgumentException("--split must be dev or test");
  }

  /** GROBID at GROBID_URL, default http://localhost:8070 (docker compose --profile grobid up -d grobid). */
  static org.catalogueoflife.nametracker.eval.modeb.GrobidClient grobid() {
    String url = System.getenv("GROBID_URL");
    return new org.catalogueoflife.nametracker.eval.modeb.GrobidClient(
        URI.create(url == null || url.isBlank() ? org.catalogueoflife.nametracker.eval.modeb.GrobidClient.DEFAULT_URL : url),
        HttpClient.newHttpClient());
  }

  /**
   * Mode B for the Pensoft corpus: PDFs from the PMC open data copy if there is one, else from pensoft.net. With
   * cacheOnly, a document without a cached PDF is skipped instead of downloaded.
   */
  static org.catalogueoflife.nametracker.eval.modeb.ModeBRunner modeB(java.util.function.Predicate<String> isKnownWord, int parallel,
                                                                     boolean cacheOnly) {
    HttpClient http = org.catalogueoflife.nametracker.eval.modeb.PdfDownloader.defaultClient();
    org.catalogueoflife.nametracker.eval.modeb.ModeBRunner.PdfSource source = cacheOnly
        ? g -> { throw new IOException("no PDF in the cache (fill it with fetch-pdf)"); }
        : org.catalogueoflife.nametracker.eval.modeb.ModeBRunner.PdfSource.pmcThenPensoft(
            new org.catalogueoflife.nametracker.eval.modeb.PdfDownloader(http),
            new org.catalogueoflife.nametracker.eval.modeb.PmcPdfLocator(http), System.err::println);
    return new org.catalogueoflife.nametracker.eval.modeb.ModeBRunner(source, grobid(), isKnownWord, parallel, System.err::println);
  }

  @Command(name = "fetch-pdf", mixinStandardHelpOptions = true,
      description = "Downloads the PDFs of a corpus (PMC open data copy first, else pensoft.net) and converts them with GROBID into the cache that run --mode B reads, so that all finders see the same documents")
  public static class FetchPdf implements Callable<Integer> {
    @Option(names = "--corpus", required = true, description = "corpus directory holding gold.jsonl.gz") Path corpus;
    @Option(names = "--split", description = "dev or test, default both") String split;
    @Option(names = "--pdf-cache", defaultValue = "eval/work/pdf", description = "PDF and TEI cache, default ${DEFAULT-VALUE}") Path cache;
    @Option(names = "--parallel", defaultValue = "4", description = "documents in flight at once (download starts stay at 1/s), default ${DEFAULT-VALUE}") int parallel;

    @Override
    public Integer call() throws Exception {
      if (split != null) checkSplit(split);
      List<GoldDocument> docs = readSplit(corpus, split);
      Map<String, String> failed = modeB(w -> false, parallel, false).prefetch(docs, cache);
      System.out.println((docs.size() - failed.size()) + " of " + docs.size() + " documents have TEI in " + cache
          + (failed.isEmpty() ? "" : "; failed: " + failed.size()));
      failed.forEach((id, why) -> System.out.println("  " + id + ": " + why));
      return 0;
    }
  }

  @Command(name = "tune", mixinStandardHelpOptions = true,
      description = "Grid search over the pipeline judge (threshold and weights) on a dev split; prints the best settings and the config overrides to put into pipeline-defaults.json")
  public static class Tune implements Callable<Integer> {
    /**
     * The default grid, besides the threshold: the largest weights that fire on stripped text (italic, gnfinderOdds,
     * ambiguousWord; taxonMarkup never fires there) and the weights behind the main false positive buckets
     * (dictHit.NONE, partialSupport). The ranges reach past the values the search settled on, except where the brief
     * fixes them (threshold up to 0.8).
     */
    static final String DEFAULT_GRID = "italic=1,2,3,4,5,6,7;gnfinderOdds=2,4,6,8,10,12,16;ambiguousWord=-1,-2.5,-4,-6;"
        + "dictHit.NONE=0,-1,-2,-3,-4,-5;partialSupport=-0.5,-2,-4,-6,-8";

    @Option(names = "--corpus", required = true, description = "corpus directory holding gold.jsonl.gz") Path corpus;
    @Option(names = "--split", defaultValue = "dev", description = "the split to tune on; never test, default ${DEFAULT-VALUE}") String split;
    @Option(names = "--modes", split = ",", defaultValue = "A", description = "A, B or A,B: the objective is the mean names F1 over the modes (A: STRICT spans, B: per document), default ${DEFAULT-VALUE}") List<String> modes;
    @Option(names = "--thresholds", split = ",", defaultValue = "0.3,0.4,0.5,0.6,0.7,0.8", description = "judge thresholds, default ${DEFAULT-VALUE}") double[] thresholds;
    @Option(names = "--grid", defaultValue = DEFAULT_GRID, description = "weights and their values, name=v1,v2;name=..., default ${DEFAULT-VALUE}") String grid;
    @Option(names = "--top", defaultValue = "10", description = "settings to print, default ${DEFAULT-VALUE}") int top;
    @Option(names = "--dictionary", description = "COL dictionary file, default $FINDER_DICTIONARY") Path dictionary;
    @Option(names = "--pdf-cache", defaultValue = "eval/work/pdf", description = "mode B: PDF and TEI cache, default ${DEFAULT-VALUE}") Path pdfCache;
    @Option(names = "--parallel", defaultValue = "4", description = "mode B: documents downloaded and converted at once, default ${DEFAULT-VALUE}") int parallel;
    @Option(names = "--cache-only", description = "mode B: use only PDFs already in the cache, skip the others") boolean cacheOnly;

    @Override
    public Integer call() throws Exception {
      if (!split.equals("dev")) throw new IllegalArgumentException("Tuning runs on the dev split only; reports quote test");
      Path dictFile = FinderFactory.dictionaryFile(dictionary, System::getenv);
      ColDictionary dict = org.catalogueoflife.nametracker.finder.pipeline.PipelineFactory.readDictionary(dictFile);
      var finder = org.catalogueoflife.nametracker.finder.pipeline.PipelineFactory.create(dict,
          org.catalogueoflife.nametracker.finder.pipeline.PipelineFactory.gnfinder(System.getenv(FinderFactory.GNFINDER_ENV)),
          org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig.defaults());
      var base = finder.config();
      List<GoldDocument> docs = readSplit(corpus, split);
      String corpusName = corpus.toAbsolutePath().normalize().getFileName().toString();
      java.util.Set<org.catalogueoflife.nametracker.finder.api.BlockType> excluded = corpusName.equals("pensoft")
          ? ScoreOptions.pensoft(MatchMode.STRICT).excludedBlocks() : ScoreOptions.curated(MatchMode.STRICT).excludedBlocks();

      List<org.catalogueoflife.nametracker.eval.tune.JudgeTuner.Target> targets = new ArrayList<>();
      for (String mode : modes) {
        String m = mode.strip().toUpperCase(Locale.ROOT);
        long t0 = System.nanoTime();
        Map<String, org.catalogueoflife.nametracker.finder.pipeline.PipelineNameFinder.Prepared> prepared = new LinkedHashMap<>();
        List<GoldDocument> gold;
        boolean docLevel;
        if (m.equals("A")) {
          for (GoldDocument d : docs) prepared.put(d.docId(), finder.prepare(d.document(), FinderOptions.defaults()));
          gold = docs;
          docLevel = false;
        } else if (m.equals("B")) {
          var converted = modeB(dict::isKnownWord, parallel, cacheOnly).convert(docs, pdfCache);
          converted.documents().forEach((id, d) -> prepared.put(id, finder.prepare(d, FinderOptions.defaults())));
          gold = converted.scored(docs);
          docLevel = true;
          if (!converted.failures().isEmpty()) System.err.println("mode B: " + converted.failures().size() + " documents skipped");
        } else {
          throw new IllegalArgumentException("--modes takes A and/or B, not " + mode);
        }
        System.err.printf(Locale.ROOT, "mode %s: %d documents prepared in %.0f s%n", m, gold.size(), (System.nanoTime() - t0) / 1e9);
        targets.add(new org.catalogueoflife.nametracker.eval.tune.JudgeTuner.Target(m, gold, prepared,
            new ScoreOptions(MatchMode.STRICT, excluded, docLevel)));
      }

      LinkedHashMap<String, double[]> values = parseGrid(grid);
      var tuner = new org.catalogueoflife.nametracker.eval.tune.JudgeTuner(base, List.copyOf(values.keySet()), targets);
      var points = org.catalogueoflife.nametracker.eval.tune.JudgeTuner.grid(base, thresholds, values);
      long t1 = System.nanoTime();
      var ranked = tuner.search(points);
      System.out.printf(Locale.ROOT, "%d settings searched in %.1f s; objective: mean names F1 over modes %s on %s/%s%n",
          points.size(), (System.nanoTime() - t1) / 1e9, String.join(",", modes), corpusName, split);
      System.out.println("Tuned: judgeThreshold, " + String.join(", ", values.keySet()) + "; all other weights as in pipeline-defaults.json");
      printOutcome("base", tuner.evaluate(tuner.baseSetting()), targets, values);
      for (int i = 0; i < Math.min(top, ranked.size()); i++) printOutcome("#" + (i + 1), ranked.get(i), targets, values);

      // the real pipeline and scorer on the best setting, to confirm the fast counts
      var best = ranked.get(0);
      for (var t : targets) {
        Map<String, FinderResult> results = new LinkedHashMap<>();
        var judge = new org.catalogueoflife.nametracker.finder.pipeline.ThresholdJudge(best.setting().weights(), base.judgeBias(),
            best.setting().threshold());
        t.prepared().forEach((id, p) -> results.put(id, finder.finish(p, judge)));
        Map<String, org.catalogueoflife.nametracker.finder.api.Document> predicted = new LinkedHashMap<>();
        t.prepared().forEach((id, p) -> predicted.put(id, p.document()));
        Metrics real = Scorer.score(t.gold(), results, t.opts(), "pipeline", corpusName, t.label(), predicted).names();
        System.out.printf(Locale.ROOT, "check mode %s with the real scorer: P=%.3f R=%.3f F1=%.3f (tp %d, fp %d, fn %d)%n",
            t.label(), real.precision(), real.recall(), real.f1(), real.tp(), real.fp(), real.fn());
      }
      System.out.println("Best config overrides:");
      System.out.println(best.setting().overridesJson(base));
      return 0;
    }

    static LinkedHashMap<String, double[]> parseGrid(String spec) {
      LinkedHashMap<String, double[]> out = new LinkedHashMap<>();
      for (String part : spec.split(";")) {
        if (part.isBlank()) continue;
        int eq = part.indexOf('=');
        if (eq < 0) throw new IllegalArgumentException("--grid entries look like name=v1,v2: " + part);
        String[] vs = part.substring(eq + 1).split(",");
        double[] d = new double[vs.length];
        for (int i = 0; i < vs.length; i++) d[i] = Double.parseDouble(vs[i].strip());
        out.put(part.substring(0, eq).strip(), d);
      }
      return out;
    }

    private static void printOutcome(String label, org.catalogueoflife.nametracker.eval.tune.JudgeTuner.Outcome o,
                                     List<org.catalogueoflife.nametracker.eval.tune.JudgeTuner.Target> targets,
                                     LinkedHashMap<String, double[]> values) {
      StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%-5s objective %.4f | threshold %.2f", label, o.objective(),
          o.setting().threshold()));
      for (String k : values.keySet()) sb.append(String.format(Locale.ROOT, ", %s %.2f", k, o.setting().weights().get(k)));
      for (int i = 0; i < targets.size(); i++) {
        Metrics m = o.names().get(i);
        sb.append(String.format(Locale.ROOT, " | %s P=%.3f R=%.3f F1=%.3f (fp %d, fn %d)", targets.get(i).label(), m.precision(),
            m.recall(), m.f1(), m.fp(), m.fn()));
      }
      System.out.println(sb);
    }
  }

  @Command(name = "run", mixinStandardHelpOptions = true, description = "Runs a finder over a corpus split and writes a report")
  public static class Run implements Callable<Integer> {
    @Option(names = "--finder", required = true, description = "italics, gnfinder, pipeline or remote:<url> of a finder service, e.g. remote:http://localhost:8095") String finder;
    @Option(names = "--corpus", required = true, description = "corpus directory holding gold.jsonl.gz") Path corpus;
    @Option(names = "--split", required = true, description = "dev or test") String split;
    @Option(names = "--mode", required = true, description = "A (plain text) or B (documents from the PDF via GROBID at $GROBID_URL; always scored per document)") String mode;
    @Option(names = "--out", defaultValue = "eval/reports", description = "report directory, default ${DEFAULT-VALUE}") Path out;
    @Option(names = "--match", defaultValue = "STRICT", description = "STRICT or OVERLAP, default ${DEFAULT-VALUE}") MatchMode match;
    @Option(names = "--doc-level", description = "compare the set of names per document instead of spans") boolean docLevel;
    @Option(names = "--dictionary", description = "COL dictionary file for the pipeline finder and mode B de-hyphenation, default $FINDER_DICTIONARY") Path dictionary;
    @Option(names = "--pdf-cache", defaultValue = "eval/work/pdf", description = "mode B: PDF and TEI cache, default ${DEFAULT-VALUE}") Path pdfCache;
    @Option(names = "--parallel", defaultValue = "4", description = "mode B: documents downloaded and converted at once, default ${DEFAULT-VALUE}") int parallel;
    @Option(names = "--cache-only", description = "mode B: use only PDFs already in the cache, skip the others") boolean cacheOnly;

    @Override
    public Integer call() throws Exception {
      String m = mode.toUpperCase(Locale.ROOT);
      if (!m.equals("A") && !m.equals("B")) throw new IllegalArgumentException("--mode must be A or B");
      checkSplit(split);
      NameFinder f = FinderFactory.create(finder, dictionary);
      List<GoldDocument> docs = readSplit(corpus, split);
      Map<String, FinderResult> results = new LinkedHashMap<>();
      Map<String, org.catalogueoflife.nametracker.finder.api.Document> predicted = Map.of();
      List<String> notes = new ArrayList<>();
      boolean doc = docLevel;
      if (m.equals("A")) {
        for (GoldDocument d : docs) results.put(d.docId(), f.find(d.document(), FinderOptions.defaults()));
      } else {
        doc = true; // converted blocks cannot be aligned with the gold spans
        ColDictionary dict = org.catalogueoflife.nametracker.finder.pipeline.PipelineFactory.readDictionary(
            FinderFactory.dictionaryFile(dictionary, System::getenv));
        var converted = modeB(dict::isKnownWord, parallel, cacheOnly).convert(docs, pdfCache);
        results = org.catalogueoflife.nametracker.eval.modeb.ModeBRunner.find(converted, f);
        predicted = converted.documents();
        int all = docs.size();
        docs = converted.scored(docs);
        notes.add("Mode B: " + docs.size() + " of " + all + " documents converted from PDF by GROBID and scored"
            + (converted.failures().isEmpty() ? "." : "; " + converted.failures().size() + " skipped (download or conversion failed): "
            + String.join(", ", converted.failures().keySet()) + "."));
        long pmc = converted.sources().values().stream().filter(u -> u.contains("pmc-oa-opendata")).count();
        notes.add("PDF sources: " + pmc + " from the PMC Article Datasets on AWS (byte-identical copies), "
            + (converted.sources().size() - pmc) + " from pensoft.net.");
        if (docs.isEmpty()) throw new IllegalStateException("No document could be converted from PDF");
      }
      String corpusName = corpus.toAbsolutePath().normalize().getFileName().toString();
      ScoreOptions opts = corpusName.equals("pensoft")
          ? new ScoreOptions(match, ScoreOptions.pensoft(match).excludedBlocks(), doc)
          : new ScoreOptions(match, ScoreOptions.curated(match).excludedBlocks(), doc);
      EvalReport r = Scorer.score(docs, results, opts, finder, corpusName, m, predicted).withNotes(notes);
      Path md = ReportWriter.write(r, out);
      notes.forEach(System.out::println);
      System.out.println(docs.size() + " documents, match " + match + (doc ? ", document level" : ""));
      line("names", r.names());
      line("acts", r.acts());
      line("contexts", r.contexts());
      line("mainSubject", r.mainSubject());
      String diag = diagnosticTotals(results.values());
      if (!diag.isEmpty()) System.out.println("Diagnostics (sum over documents): " + diag);
      System.out.println("Report: " + md);
      return 0;
    }

    /** Sums the integer diagnostics of all results, in first-seen key order. */
    static String diagnosticTotals(java.util.Collection<FinderResult> results) {
      Map<String, Long> sums = new LinkedHashMap<>();
      for (FinderResult r : results) {
        r.diagnostics().forEach((k, v) -> {
          try {
            sums.merge(k, Long.parseLong(v), Long::sum);
          } catch (NumberFormatException e) {
            // not a count
          }
        });
      }
      return sums.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }

    private static void line(String label, Metrics x) {
      System.out.printf(Locale.ROOT, "%-12s P=%.3f R=%.3f F1=%.3f (tp %d, fp %d, fn %d)%n", label, x.precision(), x.recall(),
          x.f1(), x.tp(), x.fp(), x.fn());
    }
  }

  @Command(name = "curated-select", mixinStandardHelpOptions = true,
      description = "Selects the 100 curated documents (50 English abstracts, 20 non-English, 30 Pensoft excerpts) and writes manifest.tsv plus a local picks file")
  public static class CuratedSelect implements Callable<Integer> {
    @Option(names = "--seed", defaultValue = "42", description = "seed, default ${DEFAULT-VALUE}") long seed;
    @Option(names = "--out", defaultValue = "eval/corpus/curated", description = "corpus directory for manifest.tsv, default ${DEFAULT-VALUE}") Path out;
    @Option(names = "--picks", defaultValue = "eval/work/curated-picks.jsonl.gz", description = "local file with the full picks, default ${DEFAULT-VALUE}") Path picks;
    @Option(names = "--silver", defaultValue = "eval/corpus/pensoft/manifest.tsv", description = "manifest of the silver corpus to exclude, default ${DEFAULT-VALUE}") Path silver;

    @Override
    public Integer call() throws Exception {
      HttpClient http = PensoftFetcher.defaultClient();
      OpenAlexClient oa = new OpenAlexClient(http, System.getenv("OPENALEX_API_KEY"));
      PensoftFetcher pf = new PensoftFetcher(http, oa);
      java.util.Set<String> excluded = new java.util.HashSet<>();
      CorpusManifest.read(silver).rows().forEach(r -> excluded.add(r.docId()));
      List<CuratedSelector.Pick> selected = new CuratedSelector(oa, () -> pf.candidates(seed), pf::fetch, excluded).select(seed);
      Files.createDirectories(out);
      CuratedManifest.of(selected).write(out.resolve("manifest.tsv"));
      Files.createDirectories(picks.toAbsolutePath().getParent());
      try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
          new java.util.zip.GZIPOutputStream(Files.newOutputStream(picks)), java.nio.charset.StandardCharsets.UTF_8))) {
        for (CuratedSelector.Pick p : selected) {
          w.write(FinderJson.mapper().writeValueAsString(p));
          w.write('\n');
        }
      }
      System.out.println(selected.size() + " documents, seed " + seed);
      Map<String, Long> counts = new java.util.TreeMap<>();
      for (CuratedSelector.Pick p : selected) {
        counts.merge("stratum " + p.stratum() + " / " + p.split(), 1L, Long::sum);
        counts.merge("licence " + p.license() + (p.textRetained() ? " (text stored)" : " (text re-fetched)"), 1L, Long::sum);
        if (!p.stratum().equals("PENSOFT")) counts.merge("language " + p.language(), 1L, Long::sum);
      }
      counts.forEach((k, v) -> System.out.println("  " + k + ": " + v));
      System.out.println("Wrote " + out.resolve("manifest.tsv") + " and " + picks);
      return 0;
    }
  }

  static List<CuratedSelector.Pick> readPicks(Path picks) throws IOException {
    List<CuratedSelector.Pick> out = new ArrayList<>();
    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
        new java.util.zip.GZIPInputStream(Files.newInputStream(picks)), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (!line.isBlank()) out.add(FinderJson.mapper().readValue(line, CuratedSelector.Pick.class));
      }
    }
    return out;
  }

  @Command(name = "ls-export", mixinStandardHelpOptions = true,
      description = "Pre-tags the picks of curated-select with name finders and writes the Label Studio tasks file")
  public static class LsExport implements Callable<Integer> {
    @Option(names = "--picks", defaultValue = "eval/work/curated-picks.jsonl.gz", description = "default ${DEFAULT-VALUE}") Path picks;
    @Option(names = "--finders", split = ",", defaultValue = "italics,gnfinder", description = "pre-tagging finders, default ${DEFAULT-VALUE}") List<String> finders;
    @Option(names = "--out", defaultValue = "eval/work/curated-tasks.json", description = "default ${DEFAULT-VALUE}") Path out;

    @Override
    public Integer call() throws Exception {
      List<CuratedSelector.Pick> selected = readPicks(picks);
      List<NameFinder> fs = finders.stream().map(FinderFactory::create).toList();
      Map<String, List<Mention>> pretags = new LinkedHashMap<>();
      int total = 0;
      for (CuratedSelector.Pick p : selected) {
        List<Mention> ms = PreTagger.pretag(p.document(), fs);
        pretags.put(p.docId(), ms);
        total += ms.size();
        if (pretags.size() % 25 == 0) System.err.println(pretags.size() + "/" + selected.size() + " pre-tagged");
      }
      LabelStudioExporter.write(selected, pretags, out);
      System.out.println(selected.size() + " tasks, " + total + " pre-tagged names (" + String.join(" + ", finders) + ")");
      Map<String, Integer> byStratum = new java.util.TreeMap<>();
      for (CuratedSelector.Pick p : selected) byStratum.merge(p.stratum(), pretags.get(p.docId()).size(), Integer::sum);
      byStratum.forEach((k, v) -> System.out.println("  " + k + ": " + v + " names"));
      System.out.println("Wrote " + out);
      return 0;
    }
  }

  @Command(name = "ls-import", mixinStandardHelpOptions = true,
      description = "Reads a Label Studio JSON export and prints the gold documents, optionally writing them to a gold.jsonl.gz")
  public static class LsImport implements Callable<Integer> {
    @Option(names = "--export", required = true, description = "the Label Studio JSON export") Path export;
    @Option(names = "--out", description = "gold.jsonl.gz to write, e.g. eval/corpus/curated/gold.jsonl.gz") Path out;
    @Option(names = "--manifest", defaultValue = "eval/corpus/curated/manifest.tsv", description = "curated manifest whose text hashes the documents must match, default ${DEFAULT-VALUE}") Path manifest;

    @Override
    public Integer call() throws Exception {
      List<GoldDocument> docs = LabelStudioImporter.read(export);
      CuratedManifest.read(manifest).check(docs);
      int names = 0, acts = 0, contexts = 0, subjects = 0;
      for (GoldDocument d : docs) {
        names += d.names().size();
        acts += d.acts().size();
        contexts += d.contexts().stream().mapToInt(c -> c.ranks().size()).sum();
        subjects += d.mainSubjectNames().size();
      }
      System.out.println(docs.size() + " gold documents: " + names + " names, " + acts + " acts, " + contexts
          + " context ranks, " + subjects + " main subject names");
      for (GoldDocument d : docs) {
        System.out.println("  " + d.docId() + " [" + d.split() + "]: " + d.names().size() + " names, " + d.acts().size() + " acts");
      }
      if (out != null) {
        CorpusStore.write(out, docs);
        System.out.println("Wrote " + out);
      }
      return 0;
    }
  }
}
