package org.catalogueoflife.nametracker.store.ident;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Reads what {@code LINK_WORK} and {@code LINK_SOURCE} need. Works are the listed ones (INDEXED or NOTIFIED), sources
 * are all that have not ceased; pages and id lists come back in id order.
 */
@Mapper
public interface LinkTargetMapper {

  List<LinkSubject> works(@Param("ids") List<Long> ids);

  /** Up to {@code limit} listed works with an id above {@code afterId}. */
  List<LinkSubject> worksAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  List<LinkSubject> sources(@Param("ids") List<Long> ids);

  /** Up to {@code limit} sources that have not ceased with an id above {@code afterId}. */
  List<LinkSubject> sourcesAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  List<SubjectIdentifier> workIdentifiers(@Param("ids") List<Long> ids);

  List<SubjectIdentifier> sourceIdentifiers(@Param("ids") List<Long> ids);

  /** Those of the sources with a PLAZI discovery signal or a BLR sighting of one of their works. */
  List<Long> plaziOrBlrSources(@Param("ids") List<Long> ids);
}
