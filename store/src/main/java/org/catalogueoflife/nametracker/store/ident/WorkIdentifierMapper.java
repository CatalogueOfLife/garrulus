package org.catalogueoflife.nametracker.store.ident;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkIdentifierMapper {

  /** Adds the identifiers to the work; one that exists already (for any work) is left alone. Returns the rows added. */
  int insertAll(@Param("workId") long workId, @Param("ids") List<Identifier> ids, @Param("layer") String layer);

  /** Distinct ids of the works holding any of the identifiers, ascending. */
  List<Long> findWorkIds(@Param("ids") List<Identifier> ids);

  List<Identifier> list(@Param("workId") long workId);

  /** Re-points every identifier of one work to another (a merge); returns the rows moved. */
  int moveAll(@Param("fromWork") long fromWork, @Param("toWork") long toWork);
}
