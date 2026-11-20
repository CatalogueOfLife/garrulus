package org.catalogueoflife.nametracker.store.lang;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface LanguageEvidenceMapper {

  /** Inserts the evidence, or replaces lang, confidence and raw of the row with the same work, kind and source. */
  int upsert(@Param("e") LanguageEvidence e);

  /** Deletes the row with this work, kind and source (a detection that no longer yields a language). */
  int delete(@Param("workId") long workId, @Param("kind") String kind, @Param("source") String source);

  List<LanguageEvidence> forWork(@Param("workId") long workId);

  /** Writes the decision: {@code language}, {@code language_source} and {@code language_decided_at = now()}. */
  int setDecision(@Param("workId") long workId, @Param("lang") @Nullable String lang, @Param("source") String source);

  /** Moves the rows of one work to another (a merge); rows the survivor has are dropped. Returns the rows moved. */
  int moveAll(@Param("from") long from, @Param("to") long to);
}
