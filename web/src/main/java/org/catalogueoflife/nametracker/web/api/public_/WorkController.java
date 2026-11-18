package org.catalogueoflife.nametracker.web.api.public_;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.content.Content;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.index.ContextRow;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.search.FacetCount;
import org.catalogueoflife.nametracker.store.search.MainNameRow;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.store.search.WorkHit;
import org.catalogueoflife.nametracker.store.search.WorkNameRow;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.catalogueoflife.nametracker.web.api.public_.dto.Author;
import org.catalogueoflife.nametracker.web.api.public_.dto.ContextDto;
import org.catalogueoflife.nametracker.web.api.public_.dto.MentionSpan;
import org.catalogueoflife.nametracker.web.api.public_.dto.NameDetail;
import org.catalogueoflife.nametracker.web.api.public_.dto.NameRef;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourceRef;
import org.catalogueoflife.nametracker.web.api.public_.dto.TaxonRef;
import org.catalogueoflife.nametracker.web.api.public_.dto.TextBlock;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkDetail;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkPage;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkSummary;
import org.gbif.nameparser.api.NomCode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * The public, unauthenticated read API for works: search with filters and facets, and the article. Only works in
 * state INDEXED or NOTIFIED exist here. Nothing is written.
 */
@RestController
public class WorkController {

  private static final Logger LOG = LoggerFactory.getLogger(WorkController.class);

  static final int MAX_LIMIT = 100;
  static final int MAX_OFFSET = 10_000;
  public static final Set<String> CODES = enumNames(NomCode.values());
  public static final Set<String> TAXGROUPS = enumNames(TaxGroup.values());
  public static final Set<String> TIERS = Set.of("CORE", "BROAD", "CANDIDATE", "EXCLUDED");
  public static final Set<String> OA = Set.of("DIAMOND", "GOLD", "HYBRID", "CLOSED");
  public static final Set<String> JOURNAL_TYPES = Set.of("TAXONOMIC", "GENERAL");

  private final SearchMapper search;
  private final WorkMapper works;
  private final SourceMapper sources;
  private final ContentMapper contents;
  private final IndexMapper index;
  private final BlobStore blobs;

  public WorkController(SearchMapper search, WorkMapper works, SourceMapper sources, ContentMapper contents,
      IndexMapper index, BlobStore blobs) {
    this.search = search;
    this.works = works;
    this.sources = sources;
    this.contents = contents;
    this.index = index;
    this.blobs = blobs;
  }

  private static <E extends Enum<E>> Set<String> enumNames(E[] values) {
    return java.util.Arrays.stream(values).map(Enum::name).collect(Collectors.toUnmodifiableSet());
  }

  @GetMapping("/api/works")
  public WorkPage list(
      @RequestParam(required = false) @Nullable String taxon,
      @RequestParam(required = false) @Nullable Long name,
      @RequestParam(required = false) @Nullable String code,
      @RequestParam(required = false) @Nullable String taxgroup,
      @RequestParam(required = false) @Nullable String subfield,
      @RequestParam(required = false) @Nullable String journalType,
      @RequestParam(required = false) @Nullable String tier,
      @RequestParam(required = false) @Nullable String oa,
      @RequestParam(required = false) @Nullable String country,
      @RequestParam(required = false) @Nullable Long source,
      @RequestParam(required = false) @Nullable Integer yearFrom,
      @RequestParam(required = false) @Nullable Integer yearTo,
      @RequestParam(required = false) @Nullable Boolean hasActs,
      @RequestParam(required = false) @Nullable String actType,
      @RequestParam(defaultValue = "false") boolean mainSubjectOnly,
      @RequestParam(required = false) @Nullable String q,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(name = "facet", required = false) @Nullable List<String> facet) {
    checkPaging(limit, offset);
    requireOneOf("code", code, CODES);
    requireOneOf("taxgroup", taxgroup, TAXGROUPS);
    requireOneOf("tier", tier, TIERS);
    requireOneOf("oa", oa, OA);
    requireOneOf("journalType", journalType, JOURNAL_TYPES);
    List<String> facets = facet == null ? List.of() : facet.stream().distinct().toList();
    for (String f : facets) {
      requireOneOf("facet", f, SearchMapper.FACETS);
    }
    WorkFilter f = new WorkFilter(blankToNull(taxon), name, code, taxgroup, blankToNull(subfield), journalType, tier, oa,
        blankToNull(country), source, yearFrom, yearTo, hasActs, blankToNull(actType), mainSubjectOnly, blankToNull(q));
    return page(f, limit, offset, facets);
  }

  public static void checkPaging(int limit, int offset) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw bad("limit must be 1.." + MAX_LIMIT);
    }
    if (offset < 0 || offset > MAX_OFFSET) {
      throw bad("offset must be 0.." + MAX_OFFSET);
    }
  }

  /** The search, count and facets of one filter: the code path of /api/works, shared by the taxon and source endpoints. */
  WorkPage page(WorkFilter f, int limit, int offset, List<String> facets) {
    List<WorkSummary> results = summarise(search.search(f, limit, offset));
    Map<String, List<FacetCount>> facetMap = new LinkedHashMap<>();
    for (String name1 : facets) {
      facetMap.put(name1, search.facet(f, name1));
    }
    return new WorkPage(search.count(f), limit, offset, results, facetMap);
  }

  /** The summaries of the listed works among the ids, by work id; works that are not listed are absent. */
  public Map<Long, WorkSummary> summaries(java.util.Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return summarise(search.byIds(ids)).stream().collect(Collectors.toMap(WorkSummary::id, w -> w));
  }

  private List<WorkSummary> summarise(List<WorkHit> hits) {
    Map<Long, List<NameRef>> mainNames = hits.isEmpty() ? Map.of()
        : search.mainNames(hits.stream().map(WorkHit::id).toList()).stream().collect(
            Collectors.groupingBy(MainNameRow::workId, LinkedHashMap::new,
                Collectors.mapping(r -> new NameRef(r.nameStringId(), r.name(), r.placementMethod(), r.usageId(), r.kind(),
                    r.rank(), r.usageRank(), r.actTypes()),
                    Collectors.toList())));
    Map<Long, List<TitleMentionRow>> titles = hits.isEmpty() ? Map.of()
        : search.titleMentions(hits.stream().map(WorkHit::id).toList()).stream().collect(
            Collectors.groupingBy(TitleMentionRow::workId));
    return hits.stream().map(h -> summary(h, mainNames.getOrDefault(h.id(), List.of()),
        TextSpans.locate(h.title(), titles.getOrDefault(h.id(), List.of())))).toList();
  }

  @GetMapping("/api/works/{id}")
  public WorkDetail get(@PathVariable long id) {
    Work w = search.isIndexed(id) ? works.get(id) : null;
    if (w == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no work " + id);
    }
    Source src = w.sourceId() == null ? null : sources.get(w.sourceId());
    Optional<Content> full = Content.newestFullText(contents.listByWork(id));
    boolean retained = full.isPresent() && Boolean.TRUE.equals(full.get().retained());
    String licence = full.isPresent() && full.get().license() != null ? full.get().license() : w.license();

    List<NameDetail> names = search.workNames(id).stream().map(WorkController::nameDetail).toList();
    List<MentionRow> mentions = index.mentions(id);
    List<ContextDto> contexts = index.contexts(id).stream().map(WorkController::context).toList();
    List<TextBlock> text = retained ? text(id, full.get(), mentions) : null;
    String abstractText = abstractOf(w);
    List<MentionSpan> abstractMentions = TextSpans.locate(abstractText, WorkMentions.inBlocks(mentions, "ABSTRACT"));

    return new WorkDetail(w.id(), w.doi(), w.title(), w.year() == null ? null : (int) w.year(), w.publicationDate(),
        src == null ? null : new SourceRef(src.id(), src.title()), authors(w.authors()), w.volume(), w.issue(),
        w.pages(), w.language(), w.type(), w.oaStatus(), w.bestOaUrl(), abstractText, licence, retained,
        w.textLevel(), Boolean.TRUE.equals(w.hasActs()), w.codes() == null ? List.of() : w.codes(),
        w.taxgroups() == null ? List.of() : w.taxgroups(), names, contexts, text,
        TextSpans.locate(w.title(), search.titleMentions(List.of(id))),
        abstractMentions, ActSpans.locate(abstractText, abstractMentions),
        text == null ? WorkMentions.bodySnippets(names, mentions) : List.of());
  }

  /**
   * The Crossref JATS abstract as plain paragraphs, else the OpenAlex abstract: the finder reads them in that order too,
   * so the abstract mentions are found in the text they came from, and JATS keeps the paragraphs.
   */
  private static @Nullable String abstractOf(Work w) {
    String jats = JatsAbstract.plain(w.abstractJats());
    return jats != null ? jats : w.abstractText() != null && !w.abstractText().isBlank() ? w.abstractText() : null;
  }

  /** The blocks of the retained document with their mention spans, or null if the blob is gone or unreadable, or if it is not the document the names were found in (names_doc_sha). */
  private @Nullable List<TextBlock> text(long workId, Content row, List<MentionRow> mentions) {
    // the mention offsets belong to the document the finder saw; serve text only if that is this document
    if (row.docSha256() == null || !row.docSha256().equals(search.namesDocSha(workId))) {
      return null;
    }
    Optional<byte[]> json = blobs.get(row.docSha256());
    if (json.isEmpty()) {
      return null;
    }
    Document doc;
    try {
      doc = FinderJson.mapper().readValue(json.get(), Document.class);
    } catch (RuntimeException e) {
      LOG.warn("Unreadable document {}: {}", row.docSha256(), e.toString());
      return null;
    }
    Map<String, List<MentionSpan>> byBlock = new LinkedHashMap<>();
    for (MentionRow m : mentions) {
      byBlock.computeIfAbsent(m.blockId(), k -> new ArrayList<>()).add(
          new MentionSpan(m.id(), m.nameStringId(), m.startCp(), m.endCp(), m.verbatim(), m.actType()));
    }
    List<TextBlock> out = new ArrayList<>();
    for (Block b : doc.blocks()) {
      List<MentionSpan> spans = byBlock.getOrDefault(b.id(), List.of()).stream()
          .sorted(java.util.Comparator.comparingInt(MentionSpan::startCp).thenComparingInt(MentionSpan::endCp)).toList();
      out.add(new TextBlock(b.id(), b.type().name(), b.level(), b.sectionPath(), b.text(), spans,
          ActSpans.locate(b.text(), spans)));
    }
    return out;
  }

  private static NameDetail nameDetail(WorkNameRow r) {
    List<TaxonRef> classification = List.of();
    try {
      classification = FinderJson.mapper().readValue(r.classification(), new TypeReference<List<TaxonRef>>() {});
    } catch (RuntimeException e) {
      LOG.warn("Unreadable classification of name {}: {}", r.nameStringId(), e.toString());
    }
    return new NameDetail(r.nameStringId(), r.name(), r.kind(), r.rank(), r.placementMethod(), r.usageId(),
        r.usageName(), r.usageRank(), r.usageStatus(), r.code(), r.taxgroup(), r.salience(), r.mainSubject(),
        r.onlyReference(), r.mentionCount(), r.actTypes(), r.bestSnippet(), classification);
  }

  private static ContextDto context(ContextRow c) {
    JsonNode ranks = FinderJson.mapper().readTree(c.ranks());
    return new ContextDto(c.localId(), c.verbatim(), ranks, c.scope(), c.blockId(), c.untilBlockId());
  }

  private static WorkSummary summary(WorkHit h, List<NameRef> mainNames, List<MentionSpan> titleMentions) {
    List<Author> all = authors(h.authors());
    return new WorkSummary(h.id(), h.doi(), h.title(), h.year(), h.publicationDate(),
        h.sourceId() == null ? null : new SourceRef(h.sourceId(), h.sourceTitle()),
        all.stream().limit(3).map(Author::name).toList(), all.size(), h.textLevel(), h.hasActs(),
        mainNames.size() > 5 ? mainNames.subList(0, 5) : mainNames, h.volume(), h.issue(), h.pages(),
        titleMentions);
  }

  private static List<Author> authors(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return FinderJson.mapper().readValue(json, new TypeReference<List<Author>>() {});
    } catch (RuntimeException e) {
      LOG.warn("Unreadable authors json: {}", e.toString());
      return List.of();
    }
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  public static void requireOneOf(String param, @Nullable String value, Set<String> allowed) {
    if (value != null && !allowed.contains(value)) {
      throw bad("unknown " + param + " " + value);
    }
  }

  public static ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
