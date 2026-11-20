package org.catalogueoflife.nametracker.store.lang;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkTextMapper {

  /** Inserts the text, or replaces text, source and model of the row with the same work, field, lang and kind. */
  int upsert(@Param("t") WorkText t);

  /**
   * As {@link #upsert}, for a text a harvest layer delivered: an existing row whose source is a layer ranked better than
   * {@code rank} (ADR-0008 {@code Layer.biblioRank}, lower wins) is left as it is; equal or worse is replaced. A source
   * that is no layer is replaced. {@code ranks} maps every layer name to its rank (see the default method).
   */
  int upsertRanked(@Param("t") WorkText t, @Param("rank") int rank, @Param("ranks") Map<String, Integer> ranks);

  default int upsertRanked(WorkText t, int rank) {
    Map<String, Integer> ranks = new LinkedHashMap<>();
    for (Layer l : Layer.values()) ranks.put(l.name(), l.biblioRank());
    return upsertRanked(t, rank, ranks);
  }

  List<WorkText> forWork(@Param("workId") long workId);

  List<WorkText> forWorks(@Param("ids") Collection<Long> ids);

  /** Deletes the MACHINE rows of the work's field; returns the rows deleted. */
  int deleteMachine(@Param("workId") long workId, @Param("field") String field);

  /**
   * Sets {@code work.title_en} and {@code title_en_kind} from the PARALLEL {@code en} title, else the MACHINE
   * {@code en} title, else null; null too when the title is English itself ({@code coalesce(title_lang, language) =
   * 'en'}). Writes only on a change, so an unchanged value does not recompute {@code fts}. Returns the rows updated (0 or 1).
   */
  int refreshTitleEn(@Param("workId") long workId);

  /**
   * Ids after {@code afterId}, ascending, of works that have been named (a document hash) and whose title language
   * ({@code coalesce(title_lang, language)}) is one of {@code langs}: the candidates of a bulk title translation.
   */
  List<Long> namedWithTitleLanguageAfter(@Param("afterId") long afterId, @Param("limit") int limit,
      @Param("langs") Collection<String> langs);

  /** Moves the rows of one work to another (a merge); rows the survivor has are dropped. Returns the rows moved. */
  int moveAll(@Param("from") long from, @Param("to") long to);
}
