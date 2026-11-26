package org.catalogueoflife.garrulus.store.discipline;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ClassificationRawMapper {

  /** Deletes the source's rows of the scheme and inserts {@code rows}; returns the inserted count. Call in a transaction. */
  default int replace(long sourceId, String scheme, List<RawClassification> rows) {
    delete(sourceId, scheme);
    int n = 0;
    for (RawClassification r : rows) {
      n += insert(sourceId, r);
    }
    return n;
  }

  int delete(@Param("sourceId") long sourceId, @Param("scheme") String scheme);

  int insert(@Param("sourceId") long sourceId, @Param("o") RawClassification o);

  /** By scheme, rank. */
  List<RawClassification> forSource(@Param("sourceId") long sourceId);
}
