package org.catalogueoflife.nametracker.indexer.translate;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.lang.LanguageService;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.content.TextLookupMapper;
import org.catalogueoflife.nametracker.store.lang.LanguageCodes;
import org.catalogueoflife.nametracker.store.lang.WorkText;
import org.catalogueoflife.nametracker.store.lang.WorkTextMapper;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code TRANSLATE_TITLE}, payload {@code {"workId":n,"force":false}} or {@code {"all":true,"afterId":0,"batch":500,
 * "script":"NON_LATIN"|"LATIN","force":false}}. Translates the title of a work whose decided title language is not
 * English and is one the translator reads, when the work has no PARALLEL English title and (unless {@code force}) no
 * MACHINE English title of the translator's current model. The result is stored as a MACHINE {@code work_text} row
 * (never in {@code work.title}, never given to the finder) and {@code title_en} is refreshed.
 *
 * <p>Nothing is written for a work that has not been named yet (the name check needs the finder's title mentions;
 * {@code FindNamesHandler} enqueues the task again through {@code LanguageService}), nor for a translation that is
 * blank, echoes the title, or drops a Latin-script name mentioned in the title. Such a drop is remembered per model
 * ({@code text_lookup} service {@code TRANSLATE:<model>}): the title is not sent again for that model unless
 * {@code force}, and {@code LanguageService} enqueues no more tasks for it. {@code all} pages the works of non-Latin
 * languages first, then those of Latin-script languages, and continues per batch ({@code translate-all:<script>:<afterId>},
 * at this task's priority and run_after). Off (the task ends DONE with an INFO) unless translation is enabled and a
 * translator is configured. An outage of the service defers the task.
 */
@Component
public class TranslateTitleHandler implements TaskHandler {

  public static final String TYPE = "TRANSLATE_TITLE";
  static final int DEFAULT_BATCH = 500;
  static final int MAX_BATCH = 5000;
  private static final Logger LOG = LoggerFactory.getLogger(TranslateTitleHandler.class);

  private final TranslationProperties props;
  private final ObjectProvider<TitleTranslator> translator;
  private final WorkMapper works;
  private final WorkTextMapper texts;
  private final SearchMapper search;
  private final TaskQueue queue;
  private final TextLookupMapper lookups;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final AtomicBoolean warned = new AtomicBoolean();

  public TranslateTitleHandler(TranslationProperties props, ObjectProvider<TitleTranslator> translator,
      WorkMapper works, WorkTextMapper texts, SearchMapper search, TaskQueue queue, TextLookupMapper lookups) {
    this.props = props;
    this.translator = translator;
    this.works = works;
    this.texts = texts;
    this.search = search;
    this.queue = queue;
    this.lookups = lookups;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    TitleTranslator t = props.enabled() ? translator.getIfAvailable() : null;
    if (t == null) {
      if (props.enabled() && warned.compareAndSet(false, true)) {
        LOG.warn("nametracker.translation.enabled is true but NAMETRACKER_TRANSLATION_URL is not set: titles are not translated");
      } else {
        LOG.info("Title translation is off: task {} ends without translating", task.id());
      }
      return;
    }
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    boolean force = p.path("force").asBoolean(false);
    if (p.has("workId")) {
      Work w = works.get(p.path("workId").asLong());
      if (w != null) translateOrSkip(t, w, force);
      return;
    }
    if (!p.path("all").asBoolean(false)) return;
    long afterId = p.path("afterId").asLong(0);
    int batch = Math.max(1, Math.min(MAX_BATCH, p.path("batch").asInt(DEFAULT_BATCH)));
    boolean nonLatin = !"LATIN".equals(p.path("script").asString("NON_LATIN"));
    List<String> langs = new ArrayList<>();
    for (String l : t.sources()) {
      if (LanguageCodes.isLatinScript(l) != nonLatin) langs.add(l);
    }
    List<Long> ids = texts.namedWithTitleLanguageAfter(afterId, batch, langs);
    for (long id : ids) {
      Work w = works.get(id);
      if (w != null) translateOrSkip(t, w, force);
    }
    LOG.info("Translated titles of {} {} works after work id {}", ids.size(), nonLatin ? "NON_LATIN" : "LATIN", afterId);
    String prefix = ",\"batch\":" + batch + (force ? ",\"force\":true" : "") + "}";
    if (ids.size() == batch) {
      long last = ids.get(ids.size() - 1);
      queue.enqueue(TYPE, "{\"all\":true,\"afterId\":" + last + ",\"script\":\"" + (nonLatin ? "NON_LATIN" : "LATIN")
          + "\"" + prefix, task.priority(), "translate-all:" + (nonLatin ? "NON_LATIN" : "LATIN") + ":" + last,
          task.runAfter());
    } else if (nonLatin) {
      queue.enqueue(TYPE, "{\"all\":true,\"afterId\":0,\"script\":\"LATIN\"" + prefix, task.priority(),
          "translate-all:LATIN:0", task.runAfter());
    }
  }

  /** A failure of this one title (not an outage) is logged and counted; the work is skipped. */
  private void translateOrSkip(TitleTranslator t, Work w, boolean force) throws Exception {
    try {
      translate(t, w, force);
    } catch (java.io.IOException e) {
      LOG.warn("Could not translate the title of work {} ({} failures so far): {}", w.id(), failed.incrementAndGet(),
          e.getMessage());
    }
  }

  private void translate(TitleTranslator t, Work w, boolean force) throws Exception {
    String from = LanguageCodes.normalise(w.titleLang() != null ? w.titleLang() : w.language());
    if (from == null || "en".equals(from) || !t.sources().contains(from)) return;
    if (w.title() == null || w.title().isBlank()) return;
    if (search.namesDocSha(w.id()) == null) return; // not named yet: the name check has no mentions to check
    List<WorkText> versions = texts.forWork(w.id());
    for (WorkText v : versions) {
      if (!"TITLE".equals(v.field()) || !"en".equals(v.lang())) continue;
      if ("PARALLEL".equals(v.kind())) return;
      if ("MACHINE".equals(v.kind()) && !force && t.model().equals(v.model())) return;
    }
    String droppedKey = LanguageService.translationDropped(t.model());
    if (!force && lookups.triedSince(w.id(), droppedKey, Instant.EPOCH)) return; // dropped before, same model
    String out = t.translate(w.title(), from);
    String reason = rejection(w, out);
    if (reason != null) {
      LOG.info("Dropped the translation of the title of work {} ({}); {} dropped so far", w.id(), reason,
          dropped.incrementAndGet());
      lookups.record(w.id(), droppedKey, false);
      return;
    }
    texts.upsert(new WorkText(w.id(), "TITLE", "en", "MACHINE", out.strip(), t.engine(), t.model(), null));
    texts.refreshTitleEn(w.id());
  }

  /** Why the translation is not stored, or null. */
  private String rejection(Work w, String out) {
    if (out == null || out.isBlank()) return "empty";
    if (fold(out).equalsIgnoreCase(fold(w.title()))) return "echo";
    String nfc = Normalizer.normalize(out, Normalizer.Form.NFC);
    for (TitleMentionRow m : search.titleMentions(Set.of(w.id()))) {
      if (latin(m.verbatim()) && !containsWord(nfc, Normalizer.normalize(m.verbatim(), Normalizer.Form.NFC))) {
        return "drops the name " + m.verbatim();
      }
    }
    return null;
  }

  /** The name occurs verbatim with no letter or digit right before or after it. */
  static boolean containsWord(String text, String name) {
    for (int i = text.indexOf(name); i >= 0; i = text.indexOf(name, i + 1)) {
      int end = i + name.length();
      boolean before = i > 0 && Character.isLetterOrDigit(text.codePointBefore(i));
      boolean after = end < text.length() && Character.isLetterOrDigit(text.codePointAt(end));
      if (!before && !after) return true;
    }
    return false;
  }

  private static String fold(String s) {
    return s.strip().replaceAll("\\s+", " ");
  }

  private static boolean latin(String s) {
    return s.codePoints().filter(Character::isLetter)
        .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
  }
}
