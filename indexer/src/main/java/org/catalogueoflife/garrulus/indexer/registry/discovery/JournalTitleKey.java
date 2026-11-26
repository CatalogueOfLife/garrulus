package org.catalogueoflife.garrulus.indexer.registry.discovery;

import java.text.Normalizer;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The comparison key of a journal title: NFKD without marks, lower case, {@code &} as {@code and}, punctuation as
 * spaces, a leading {@code the} dropped, spaces collapsed. Null when no letter remains (a label such as "183").
 */
public final class JournalTitleKey {

  private JournalTitleKey() {}

  public static @Nullable String of(@Nullable String title) {
    if (title == null) return null;
    String s = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT).replace("&", " and ");
    StringBuilder sb = new StringBuilder();
    boolean letter = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isLetter(c)) {
        letter = true;
        sb.append(c);
      } else if (Character.isDigit(c)) {
        sb.append(c);
      } else {
        sb.append(' ');
      }
    }
    if (!letter) return null;
    String k = sb.toString().strip().replaceAll(" +", " ");
    if (k.startsWith("the ")) k = k.substring(4);
    return k.isEmpty() || k.equals("the") ? null : k;
  }
}
