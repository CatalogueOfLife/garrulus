package org.catalogueoflife.nametracker.store.lang;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkTextMapper {

  /** Inserts the text, or replaces text, source and model of the row with the same work, field, lang and kind. */
  int upsert(@Param("t") WorkText t);

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

  /** Moves the rows of one work to another (a merge); rows the survivor has are dropped. Returns the rows moved. */
  int moveAll(@Param("from") long from, @Param("to") long to);
}
