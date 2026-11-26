package org.catalogueoflife.garrulus.web.auth;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The ORCID iDs of the admins ({@code garrulus.admin.orcids}, env {@code GARRULUS_ADMIN_ORCIDS},
 * comma-separated), read once at start. An account signed in through ORCID with one of these iDs gets ROLE_ADMIN; the
 * admin endpoints check the list again on every request (ADR-0006, amendment M6b). iDs are compared normalised: a
 * leading {@code https://orcid.org/} dropped, the check character {@code x} upper-cased. An entry that is not an iD
 * ({@code 0000-0000-0000-000X}) is logged as a WARN and ignored.
 */
@Component
public class AdminOrcids {

  public static final String PROPERTY = "garrulus.admin.orcids";
  /** The header of the script access to {@code /api/admin/**}. */
  public static final String TOKEN_HEADER = "X-Admin-Token";
  private static final Logger LOG = LoggerFactory.getLogger(AdminOrcids.class);
  private static final Pattern ORCID = Pattern.compile("\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]");
  private static final Pattern PREFIX = Pattern.compile("^https?://(www\\.)?orcid\\.org/", Pattern.CASE_INSENSITIVE);

  private final Set<String> ids;

  public AdminOrcids(@Value("${" + PROPERTY + ":}") @Nullable String csv) {
    this.ids = parse(csv, true);
    if (!ids.isEmpty()) {
      LOG.info("{} admin ORCID iD(s) configured", ids.size());
    }
  }

  /** Header present ({@link #TOKEN_HEADER}, any value): the token path, the only one exempt from XSRF. */
  public static boolean tokenSent(jakarta.servlet.http.HttpServletRequest request) {
    return request.getHeader(TOKEN_HEADER) != null;
  }

  /** True if the iD, normalised, is listed. */
  public boolean contains(@Nullable String orcid) {
    String id = normalise(orcid);
    return id != null && ids.contains(id);
  }

  /** True if at least one valid iD is listed. */
  public boolean any() {
    return !ids.isEmpty();
  }

  /** {@link #any()} of a list, without logging (for conditions evaluated before the bean exists). */
  public static boolean anyValid(@Nullable String csv) {
    return !parse(csv, false).isEmpty();
  }

  /** The iD without URL prefix and with an upper-case check character, or null if it is not an ORCID iD. */
  static @Nullable String normalise(@Nullable String orcid) {
    if (orcid == null) {
      return null;
    }
    String id = PREFIX.matcher(orcid.strip()).replaceFirst("").toUpperCase(Locale.ROOT);
    return ORCID.matcher(id).matches() ? id : null;
  }

  private static Set<String> parse(@Nullable String csv, boolean warn) {
    Set<String> ids = new LinkedHashSet<>();
    if (csv == null) {
      return Set.of();
    }
    for (String entry : csv.split(",")) {
      if (entry.isBlank()) {
        continue;
      }
      String id = normalise(entry);
      if (id != null) {
        ids.add(id);
      } else if (warn) {
        LOG.warn("{} entry '{}' is not an ORCID iD, ignored", PROPERTY, entry.strip());
      }
    }
    return Set.copyOf(ids);
  }
}
