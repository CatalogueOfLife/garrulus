package org.catalogueoflife.nametracker.indexer.hits;

import org.jspecify.annotations.Nullable;

/** A work_name as the hit rules and the hit snapshot see it. {@code taxonPath} and {@code actTypes} are never null (empty when none). */
public record WorkNameView(long nameStringId, String name, String[] taxonPath, @Nullable String taxgroup,
    boolean mainSubject, boolean onlyReference, String[] actTypes, String placementMethod,
    boolean inTitle, boolean inAbstract, boolean inHeading, int bodyCount) {

  /** In one of the parts: TITLE, ABSTRACT, BODY (text or a heading). */
  public boolean mentionedIn(java.util.List<String> parts) {
    for (String p : parts) {
      if (switch (p) {
        case "TITLE" -> inTitle;
        case "ABSTRACT" -> inAbstract;
        case "BODY" -> bodyCount > 0 || inHeading;
        default -> false;
      }) return true;
    }
    return false;
  }
}
