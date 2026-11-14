package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.eval.corpus.CorpusManifest;
import org.catalogueoflife.nametracker.eval.corpus.CorpusRebuilder;
import org.catalogueoflife.nametracker.eval.corpus.CorpusStore;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/** The evaluation command line. More subcommands (curated-select, ls-export, ls-import, build-dictionary) arrive with later tasks. */
@Command(name = "eval", mixinStandardHelpOptions = true, subcommands = {EvalCli.FetchPensoft.class, EvalCli.RebuildPensoft.class, EvalCli.Run.class},
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
      java.net.http.HttpClient http = PensoftFetcher.defaultClient();
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
      java.net.http.HttpClient http = PensoftFetcher.defaultClient();
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
      List<GoldDocument> docs = CorpusStore.read(corpus.resolve("gold.jsonl.gz"), null).stream()
          .filter(d -> split.equals(d.split())).toList();
      if (docs.isEmpty()) throw new IllegalArgumentException("No documents in split " + split + " of " + corpus);
      Map<String, FinderResult> results = new LinkedHashMap<>();
      for (GoldDocument d : docs) results.put(d.docId(), f.find(d.document(), FinderOptions.defaults()));
      String corpusName = corpus.toAbsolutePath().normalize().getFileName().toString();
      ScoreOptions opts = corpusName.equals("pensoft")
          ? new ScoreOptions(match, ScoreOptions.pensoft(match).excludedBlocks(), docLevel)
          : new ScoreOptions(match, java.util.Set.of(), docLevel);
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
}
