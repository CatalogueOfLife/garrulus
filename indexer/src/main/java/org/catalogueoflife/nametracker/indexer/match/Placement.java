package org.catalogueoflife.nametracker.indexer.match;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a name sits on the XR. {@code method} is MATCH, GENUS, VERBATIM_CONTEXT or NONE; {@code code} is the
 * upper-case NomCode name and {@code taxgroup} the CLB TaxGroup enum name.
 */
public record Placement(String method, @Nullable String usageId, List<String> taxonPath, @Nullable String code,
    @Nullable String taxgroup) {

  public static final String MATCH = "MATCH";
  public static final String GENUS = "GENUS";
  public static final String VERBATIM_CONTEXT = "VERBATIM_CONTEXT";
  public static final String NONE = "NONE";

  public Placement withGroup(@Nullable String code, @Nullable String taxgroup) {
    return new Placement(method, usageId, taxonPath, code, taxgroup);
  }
}
