package org.catalogueoflife.garrulus.indexer.lang;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.store.content.TextLookupMapper;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.lang.LanguageCodes;
import org.catalogueoflife.garrulus.store.lang.LanguageEvidence;
import org.catalogueoflife.garrulus.store.lang.LanguageEvidenceMapper;
import org.catalogueoflife.garrulus.store.lang.WorkText;
import org.catalogueoflife.garrulus.store.lang.WorkTextMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Records what each signal says about the language of a work and decides it ({@link LanguageDecider}): declared by a
 * layer, OpenAlex's value, detection from the title and abstract, detection from the full text. The decision is
 * written to {@code work.language} and {@code language_source}, only on a change; {@code title_en} is brought up to date
 * on every decision (it depends on {@code title_lang} too, which other paths set). The evidence holds codes and
 * confidences, never text (the raw column is a declared code and nothing else).
 *
 * <p>A decision that leaves a work in a language other than English with no English title enqueues
 * {@code TRANSLATE_TITLE} (key {@code translate:<id>}, at BACKFILL) when translation is enabled, but only once the work
 * has been named (the translation's name check needs the title's mentions) and not when the configured model's
 * translation of it was dropped before ({@code text_lookup} service {@code TRANSLATE:<model>}, see
 * {@link #translationDropped(String)}).
 */
@Component
public class LanguageService {

  /** The task that translates a title; its handler follows with the translation work. */
  public static final String TRANSLATE_TITLE = "TRANSLATE_TITLE";
  /** The {@code text_lookup} service that records a dropped translation of a model: {@code TRANSLATE:<model>}. */
  public static String translationDropped(String model) {
    return "TRANSLATE:" + model;
  }

  /** Letters of the body the detector reads. */
  static final int BODY_LETTERS = 5000;

  private final LanguageEvidenceMapper evidence;
  private final WorkTextMapper texts;
  private final WorkMapper works;
  private final SourceMapper sources;
  private final IndexMapper index;
  private final LanguageDecider decider;
  private final LanguageDetector detector;
  private final TaskQueue queue;
  private final TextLookupMapper lookups;
  private final boolean translationEnabled;
  private final String translationModel;

  public LanguageService(LanguageEvidenceMapper evidence, WorkTextMapper texts, WorkMapper works, SourceMapper sources,
      IndexMapper index, LanguageDecider decider, LanguageDetector detector, TaskQueue queue, TextLookupMapper lookups,
      @Value("${garrulus.translation.enabled:false}") boolean translationEnabled,
      @Value("${garrulus.translation.model:libretranslate-1.9.6/argos}") String translationModel) {
    this.evidence = evidence;
    this.texts = texts;
    this.works = works;
    this.sources = sources;
    this.index = index;
    this.decider = decider;
    this.detector = detector;
    this.queue = queue;
    this.lookups = lookups;
    this.translationEnabled = translationEnabled;
    this.translationModel = translationModel;
  }

  /** Stores the evidence (one row per work, kind and source) and decides again. */
  public LanguageDecider.@Nullable Decision evidence(long workId, LanguageEvidence e) {
    evidence.upsert(e);
    return decide(workId);
  }

  /**
   * OpenAlex's language of the work as of its latest record: the OPENALEX evidence row, or no row when the record has
   * no language any more. Decides again.
   */
  public void openAlexLanguage(long workId, @Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      evidence.delete(workId, "OPENALEX", "OPENALEX");
      decide(workId);
    } else {
      evidence(workId, openAlex(workId, raw));
    }
  }

  /** Removes the FULLTEXT evidence of a work whose full text is no longer available; the caller decides again. */
  public void dropFulltext(long workId) {
    evidence.delete(workId, "FULLTEXT", "BODY");
  }

  /** DECLARED evidence of a layer: the value is normalised, the raw code is kept. */
  public static LanguageEvidence declared(long workId, String layer, String raw) {
    return new LanguageEvidence(workId, "DECLARED", layer, LanguageCodes.normalise(raw), null, cap(raw));
  }

  /** OpenAlex's own value for the work. */
  public static LanguageEvidence openAlex(long workId, String raw) {
    return new LanguageEvidence(workId, "OPENALEX", "OPENALEX", LanguageCodes.normalise(raw), null, cap(raw));
  }

  /**
   * Decides the language of a work from its evidence and its journal's languages, and writes the decision when it
   * changed. Null for a work that does not exist.
   */
  public LanguageDecider.@Nullable Decision decide(long workId) {
    Work w = works.get(workId);
    if (w == null) return null;
    List<String> journal = w.sourceId() == null ? List.of() : sources.languages(w.sourceId());
    LanguageDecider.Decision d = decider.decide(evidence.forWork(workId), journal);
    if (!Objects.equals(d.lang(), w.language()) || !d.source().equals(w.languageSource())) {
      evidence.setDecision(workId, d.lang(), d.source());
    }
    // also without a new decision: title_lang may have changed alone (the refresh writes only a change)
    texts.refreshTitleEn(workId);
    translateIfNeeded(w, d);
    return d;
  }

  private void translateIfNeeded(Work w, LanguageDecider.Decision d) {
    if (!translationEnabled || d.lang() == null || "en".equals(d.lang()) || w.titleEn() != null
        || "en".equals(w.titleLang()) || index.namesDocSha(w.id()) == null
        || lookups.triedSince(w.id(), translationDropped(translationModel), Instant.EPOCH)) {
      return;
    }
    queue.enqueue(TRANSLATE_TITLE, "{\"workId\":" + w.id() + "}", TaskPriority.BACKFILL, "translate:" + w.id());
  }

  /**
   * DETECTED evidence (source {@code TITLE} and {@code ABSTRACT}) from the title and abstract; the detection's margin
   * is the stored confidence. The original title and abstract versions in {@code work_text} are read when there are
   * any, and the work's own columns are skipped when a layer gave their language ({@code title_lang},
   * {@code abstract_lang}: Magtech's title and abstract are the English parallel, which says nothing of the article).
   * Parallel texts are removed from the abstract before it is read. A signal that yields nothing removes its old row.
   */
  public void detectFromWork(Work w) {
    List<WorkText> versions = texts.forWork(w.id());
    String title = original(versions, "TITLE");
    if (title == null && w.titleLang() == null) title = w.title();
    String abs = original(versions, "ABSTRACT");
    if (abs == null && w.abstractLang() == null) abs = w.abstractText() != null ? w.abstractText() : w.abstractJats();
    List<String> parallels = new ArrayList<>();
    for (WorkText t : versions) if ("PARALLEL".equals(t.kind())) parallels.add(t.text());
    record(w.id(), "DETECTED", "TITLE", detector.detectTitle(title), true);
    record(w.id(), "DETECTED", "ABSTRACT", detector.detectAbstract(abs, parallels), true);
  }

  /**
   * FULLTEXT evidence from the first 5,000 letters of the PARAGRAPH blocks, only when the document has a PARAGRAPH
   * block (an abstract document has none). Only the language and the confidence are kept.
   */
  public void detectFromDocument(long workId, Document doc) {
    StringBuilder body = new StringBuilder();
    boolean paragraphs = false;
    int letters = 0;
    for (Block b : doc.blocks()) {
      if (b.type() != BlockType.PARAGRAPH) continue;
      paragraphs = true;
      body.append(b.text()).append('\n');
      letters += (int) b.text().codePoints().filter(Character::isLetter).count();
      if (letters >= BODY_LETTERS) break;
    }
    if (!paragraphs) return;
    record(workId, "FULLTEXT", "BODY", detector.detectText(body.toString()), false);
  }

  private void record(long workId, String kind, String source, LanguageDetector.@Nullable Detection d,
      boolean useMargin) {
    if (d == null || d.lang() == null) {
      evidence.delete(workId, kind, source);
    } else {
      evidence.upsert(new LanguageEvidence(workId, kind, source, d.lang(), useMargin ? d.margin() : d.confidence(),
          null));
    }
  }

  private static @Nullable String original(List<WorkText> versions, String field) {
    return versions.stream().filter(t -> field.equals(t.field()) && "ORIGINAL".equals(t.kind()))
        .map(WorkText::text).findFirst().orElse(null);
  }

  private static @Nullable String cap(@Nullable String raw) {
    return raw == null ? null : raw.length() <= 64 ? raw : raw.substring(0, 64);
  }
}
