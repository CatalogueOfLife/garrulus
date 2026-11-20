package org.catalogueoflife.nametracker.store.registry;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface IssnLinkMapper {

  /** The linking ISSN of the ISSN, null when the table does not know it. */
  @Nullable String issnL(@Param("issn") String issn);

  /** Every ISSN linked to the ISSN-L, ordered. */
  List<String> siblings(@Param("issnL") String issnL);

  /**
   * Upserts {@code issn_link} from the session temp table {@code issn_link_load(issn, issn_l)} (filled by the ISSN-L
   * refresh with COPY) and returns the number of rows written.
   */
  long upsertFromTemp();
}
