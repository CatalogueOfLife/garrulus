package org.catalogueoflife.garrulus.eval.modeb;

import org.catalogueoflife.garrulus.eval.corpus.GoldDocument;
import org.catalogueoflife.garrulus.eval.corpus.PensoftFetcher;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.doc.TeiConverter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Mode B of the evaluation, the PDF path: the article's PDF goes through a local GROBID, the TEI through
 * {@link TeiConverter} (which de-hyphenates line breaks with the COL dictionary), and the finder runs on the converted
 * document. Its names are scored per document against the gold of the XML version ({@code documentLevel=true}), since
 * the converted blocks cannot be aligned with the gold spans.
 *
 * <p>PDFs and TEI are cached in one directory ({@code eval/work/pdf/}, not committed) as {@code <docId>.pdf} and
 * {@code <docId>.tei.xml}, plus {@code <docId>.pdf.url} with the URL the PDF came from, so every finder runs on the same
 * converted documents and later runs need neither the network nor GROBID. A document whose PDF cannot be downloaded or
 * converted is logged, skipped and counted, never fatal.
 */
public final class ModeBRunner {
  /** Gets the PDF of a document. */
  @FunctionalInterface
  public interface PdfSource {
    /** The PDF and the URL it was downloaded from. */
    record Fetched(byte[] pdf, String url) {}

    Fetched fetch(GoldDocument g) throws IOException, InterruptedException;

    /** One URL per document. */
    static PdfSource of(PdfDownloader downloader, Function<GoldDocument, String> url) {
      return g -> {
        String u = url.apply(g);
        return new Fetched(downloader.download(u), u);
      };
    }

    /**
     * For Pensoft: the copy in the PMC Article Datasets (identical in spot checks (MD5)) on AWS if there is one (fast), else
     * {@link PensoftFetcher#pdfUrl} (slow, throttled by Pensoft). A failure of the PMC route falls back to Pensoft.
     */
    static PdfSource pmcThenPensoft(PdfDownloader downloader, PmcPdfLocator pmc, Consumer<String> log) {
      return g -> {
        String doi = doi(g);
        try {
          java.util.Optional<String> u = pmc.pdfUrl(doi);
          if (u.isPresent()) return new Fetched(downloader.download(u.get()), u.get());
        } catch (IOException e) {
          log.accept("PMC copy of " + g.docId() + " not available (" + e.getMessage() + "), trying Pensoft");
        }
        String p = PensoftFetcher.pdfUrl(doi);
        return new Fetched(downloader.download(p), p);
      };
    }
  }

  /**
   * The documents converted from PDF, in input order, the reason for each one that failed, and for each converted one
   * the URL its PDF came from ("unknown" for PDFs cached without one).
   */
  public record Converted(Map<String, Document> documents, Map<String, String> failures, Map<String, String> sources) {
    public Converted {
      documents = Collections.unmodifiableMap(new LinkedHashMap<>(documents));
      failures = Collections.unmodifiableMap(new LinkedHashMap<>(failures));
      sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    /** The gold documents that were converted. */
    public List<GoldDocument> scored(List<GoldDocument> gold) {
      return gold.stream().filter(g -> documents.containsKey(g.docId())).toList();
    }
  }

  private final PdfSource source;
  private final GrobidClient grobid;
  private final Predicate<String> isKnownWord;
  private final int parallel;
  private final Consumer<String> log;

  /**
   * @param isKnownWord the dictionary test for de-hyphenation, normally {@code ColDictionary::isKnownWord}
   * @param parallel documents downloaded and converted at the same time; download starts stay at one per second
   */
  public ModeBRunner(PdfSource source, GrobidClient grobid, Predicate<String> isKnownWord, int parallel, Consumer<String> log) {
    if (parallel < 1) throw new IllegalArgumentException("parallel must be at least 1");
    this.source = source;
    this.grobid = grobid;
    this.isKnownWord = isKnownWord;
    this.parallel = parallel;
    this.log = log;
  }

  static String doi(GoldDocument g) {
    String doi = g.document().meta().get("doi");
    if (doi == null || doi.isBlank()) throw new IllegalArgumentException("Document " + g.docId() + " has no doi in its meta");
    return doi;
  }

  static String pensoftPdfUrl(GoldDocument g) {
    return PensoftFetcher.pdfUrl(doi(g));
  }

  /** Converts the documents (cached, see the class documentation) and runs the finder on each converted one. */
  public Map<String, FinderResult> run(List<GoldDocument> docs, NameFinder finder, Path pdfCache) {
    return find(convert(docs, pdfCache), finder);
  }

  /** Runs the finder on every converted document, in order. */
  public static Map<String, FinderResult> find(Converted converted, NameFinder finder) {
    Map<String, FinderResult> out = new LinkedHashMap<>();
    converted.documents().forEach((id, doc) -> out.put(id, finder.find(doc, FinderOptions.defaults())));
    return out;
  }

  /** Downloads, runs GROBID and converts, using and filling the cache. */
  public Converted convert(List<GoldDocument> docs, Path cache) {
    Map<String, Document> converted = new LinkedHashMap<>();
    Map<String, String> failures = new LinkedHashMap<>();
    List<Object> outcomes = process(docs, cache, g -> {
      byte[] tei = tei(g, cache);
      Document d = new TeiConverter().convert(new ByteArrayInputStream(tei), g.docId(), isKnownWord);
      if (d.blocks().stream().allMatch(b -> b.text().isBlank())) throw new IOException("GROBID found no text in the PDF");
      return d;
    });
    Map<String, String> sources = new LinkedHashMap<>();
    for (int i = 0; i < docs.size(); i++) {
      Object o = outcomes.get(i);
      String id = docs.get(i).docId();
      if (o instanceof Document d) {
        converted.put(id, d);
        sources.put(id, source(cache, id));
      } else {
        failures.put(id, (String) o);
      }
    }
    return new Converted(converted, failures, sources);
  }

  private static String source(Path cache, String docId) {
    Path f = cache.resolve(fileName(docId) + ".pdf.url");
    try {
      return Files.isRegularFile(f) ? Files.readString(f).strip() : "unknown";
    } catch (IOException e) {
      return "unknown";
    }
  }

  /** Fills the cache only (PDF and TEI), e.g. ahead of several runs; returns the reason per failed document. */
  public Map<String, String> prefetch(List<GoldDocument> docs, Path cache) {
    Map<String, String> failures = new LinkedHashMap<>();
    List<Object> outcomes = process(docs, cache, g -> tei(g, cache));
    for (int i = 0; i < docs.size(); i++) {
      if (outcomes.get(i) instanceof String reason) failures.put(docs.get(i).docId(), reason);
    }
    return failures;
  }

  @FunctionalInterface
  private interface Step {
    Object apply(GoldDocument g) throws Exception;
  }

  /** Applies the step to every document, {@link #parallel} at a time; a failure becomes its reason (a String). */
  private List<Object> process(List<GoldDocument> docs, Path cache, Step step) {
    try {
      Files.createDirectories(cache);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create the PDF cache " + cache, e);
    }
    AtomicInteger done = new AtomicInteger();
    List<Object> out = new ArrayList<>(docs.size());
    try (ExecutorService pool = Executors.newFixedThreadPool(parallel)) {
      List<Future<Object>> futures = new ArrayList<>();
      for (GoldDocument g : docs) {
        futures.add(pool.submit(() -> {
          Object r;
          try {
            r = step.apply(g);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = "interrupted";
          } catch (Exception e) {
            r = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.accept("skipping " + g.docId() + ": " + r);
          }
          int n = done.incrementAndGet();
          if (n % 10 == 0 || n == docs.size()) log.accept(n + "/" + docs.size() + " documents through the PDF path");
          return r;
        }));
      }
      for (Future<Object> f : futures) out.add(f.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while converting PDFs", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e.getCause());
    }
    return out;
  }

  /** The cached TEI of a document, made from the cached or downloaded PDF if needed. */
  private byte[] tei(GoldDocument g, Path cache) throws IOException, InterruptedException {
    Path teiFile = cache.resolve(fileName(g.docId()) + ".tei.xml");
    if (Files.isRegularFile(teiFile)) return Files.readAllBytes(teiFile);
    Path pdfFile = cache.resolve(fileName(g.docId()) + ".pdf");
    byte[] pdf;
    if (Files.isRegularFile(pdfFile)) {
      pdf = Files.readAllBytes(pdfFile);
    } else {
      PdfSource.Fetched f = source.fetch(g);
      pdf = f.pdf();
      writeAtomically(cache.resolve(fileName(g.docId()) + ".pdf.url"), (f.url() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      writeAtomically(pdfFile, pdf);
    }
    byte[] tei = grobid.processFulltext(pdf).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    writeAtomically(teiFile, tei);
    return tei;
  }

  /** "pensoft:zookeys.1251.145353" becomes "pensoft_zookeys.1251.145353". */
  static String fileName(String docId) {
    return docId.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static void writeAtomically(Path file, byte[] content) throws IOException {
    Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
    try {
      Files.write(tmp, content);
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
