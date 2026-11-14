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
    EvalCli.LsExport.class, EvalCli.LsImport.class, EvalCli.BuildDictionary.class},
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

  @Command(name = "run", mixinStandardHelpOptions = true, description = "Runs a finder over a corpus split and writes a report")
  public static class Run implements Callable<Integer> {
    @Option(names = "--finder", required = true, description = "italics or gnfinder (more arrive with later tasks)") String finder;
    @Option(names = "--corpus", required = true, description = "corpus directory holding gold.jsonl.gz") Path corpus;
    @Option(names = "--split", required = true, description = "dev or test") String split;
    @Option(names = "--mode", required = true, description = "A (plain text) or B (documents from PDF)") String mode;
    @Option(names = "--out", defaultValue = "eval/reports", description = "report directory, default ${DEFAULT-VALUE}") Path out;
    @Option(names = "--match", defaultValue = "STRICT", description = "STRICT or OVERLAP, default ${DEFAULT-VALUE}") MatchMode match;
    @Option(names = "--doc-level", description = "compare the set of names per document instead of spans") boolean docLevel;

    @Override
    public Integer call() throws Exception {
      String m = mode.toUpperCase(Locale.ROOT);
      if (m.equals("B")) throw new IllegalArgumentException("mode B arrives in Task 18");
      if (!m.equals("A")) throw new IllegalArgumentException("--mode must be A or B");
      if (!split.equals("dev") && !split.equals("test")) throw new IllegalArgumentException("--split must be dev or test");
      NameFinder f = FinderFactory.create(finder);
      // curated abstracts under a restrictive licence are stored without text and re-fetched from OpenAlex here
      HttpClient http = PensoftFetcher.defaultClient();
      List<GoldDocument> docs = CorpusStore.read(corpus.resolve("gold.jsonl.gz"),
          new org.catalogueoflife.nametracker.eval.corpus.OpenAlexAbstractRefetcher(new OpenAlexClient(http, System.getenv("OPENALEX_API_KEY")))).stream()
          .filter(d -> split.equals(d.split())).toList();
      if (docs.isEmpty()) throw new IllegalArgumentException("No documents in split " + split + " of " + corpus);
      Map<String, FinderResult> results = new LinkedHashMap<>();
      for (GoldDocument d : docs) results.put(d.docId(), f.find(d.document(), FinderOptions.defaults()));
      String corpusName = corpus.toAbsolutePath().normalize().getFileName().toString();
      ScoreOptions opts = corpusName.equals("pensoft")
          ? new ScoreOptions(match, ScoreOptions.pensoft(match).excludedBlocks(), docLevel)
          : new ScoreOptions(match, ScoreOptions.curated(match).excludedBlocks(), docLevel);
      EvalReport r = Scorer.score(docs, results, opts, finder, corpusName, m);
      Path md = ReportWriter.write(r, out);
      System.out.println(docs.size() + " documents, match " + match + (docLevel ? ", document level" : ""));
      line("names", r.names());
      line("acts", r.acts());
      line("contexts", r.contexts());
      line("mainSubject", r.mainSubject());
      System.out.println("Report: " + md);
      return 0;
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
