package org.catalogueoflife.nametracker.store.registry;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PkpBeaconMapper {

  /** The rows listing any of the ISSNs (upper case, hyphenated), ordered by id. */
  List<PkpBeaconRow> byIssns(@Param("issns") List<String> issns);
}
