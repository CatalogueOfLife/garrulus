package org.catalogueoflife.garrulus.web.api.public_;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.garrulus.store.index.MentionRow;
import org.catalogueoflife.garrulus.store.search.TitleMentionRow;
import org.catalogueoflife.garrulus.web.api.public_.dto.MentionSpan;
import org.catalogueoflife.garrulus.web.api.public_.dto.NameDetail;
import org.catalogueoflife.garrulus.web.api.public_.dto.Snippet;

/** The stored mentions of one work, picked for the parts of the work page: the abstract and the body snippets. */
final class WorkMentions {

  /** Title, abstract and references are not body text: the page shows the first two itself, the last is citations. */
  private static final Set<String> NOT_BODY = Set.of("TITLE", "ABSTRACT", "REFERENCE");
  private static final Pattern BLOCK_NO = Pattern.compile("b(\\d{1,9})");

  /** Reading order: block number (b2 before b10), then start, longest first, then id. */
  static final Comparator<MentionRow> READING_ORDER = Comparator.comparingLong((MentionRow m) -> blockNo(m.blockId()))
      .thenComparing(MentionRow::blockId)
      .thenComparingInt(MentionRow::startCp)
      .thenComparing(Comparator.comparingInt(MentionRow::endCp).reversed())
      .thenComparingLong(m -> m.id() == null ? 0 : m.id());

  private WorkMentions() {}

  /** The mentions in blocks of the type, in reading order, as rows for {@link TextSpans#locate}. */
  static List<TitleMentionRow> inBlocks(List<MentionRow> mentions, String blockType) {
    return mentions.stream().filter(m -> blockType.equals(m.blockType())).sorted(READING_ORDER)
        .map(WorkMentions::row).toList();
  }

  /**
   * One snippet per name that occurs in the body, in the order of the names: the snippet of its first mention in a
   * paragraph, else of its first mention in any other body block (caption, table cell, heading, footnote), with the
   * name and its act marker marked in it. Names only in the title, abstract or references get none.
   */
  static List<Snippet> bodySnippets(List<NameDetail> names, List<MentionRow> mentions) {
    Map<Long, MentionRow> first = new HashMap<>();
    mentions.stream().filter(m -> !NOT_BODY.contains(m.blockType()) && !m.snippet().isBlank())
        .sorted(Comparator.comparing((MentionRow m) -> !"PARAGRAPH".equals(m.blockType())).thenComparing(READING_ORDER))
        .forEach(m -> first.putIfAbsent(m.nameStringId(), m));
    return names.stream().map(n -> first.get(n.id())).filter(m -> m != null)
        .map(m -> {
          List<MentionSpan> spans = TextSpans.locate(m.snippet(), List.of(row(m)));
          return new Snippet(m.nameStringId(), m.blockType(), m.snippet(), spans, ActSpans.locate(m.snippet(), spans));
        })
        .toList();
  }

  private static TitleMentionRow row(MentionRow m) {
    return new TitleMentionRow(m.workId(), m.id() == null ? 0 : m.id(), m.nameStringId(), m.blockId(), m.startCp(),
        m.endCp(), m.verbatim(), m.actType());
  }

  private static long blockNo(String blockId) {
    Matcher b = BLOCK_NO.matcher(blockId);
    return b.matches() ? Long.parseLong(b.group(1)) : Long.MAX_VALUE;
  }
}
