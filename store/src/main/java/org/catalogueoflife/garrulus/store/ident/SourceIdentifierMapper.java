package org.catalogueoflife.garrulus.store.ident;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/**
 * Lookup identifiers of a journal (WIKIDATA, IPNI_PUB, BPH, BHL_TITLE, DOAJ, CROSSREF_JOURNAL). ISSN, ISSN-L and the
 * OpenAlex id are the source's own columns and are never stored here.
 */
@Mapper
public interface SourceIdentifierMapper {

  /** Adds the identifiers; one that exists already (for any source) is left alone. Returns the rows added. */
  int insertAll(@Param("sourceId") long sourceId, @Param("ids") List<Identifier> ids, @Param("origin") String origin);

  List<Identifier> list(@Param("sourceId") long sourceId);

  /** The source holding the identifier, or null. */
  @Nullable Long findSource(@Param("scheme") String scheme, @Param("value") String value);

  /** Re-points every identifier of one source to another (a fold); rows the survivor holds are dropped. */
  int moveAll(@Param("fromSource") long fromSource, @Param("toSource") long toSource);
}
