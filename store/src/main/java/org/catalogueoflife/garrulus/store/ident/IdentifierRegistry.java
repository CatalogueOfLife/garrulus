package org.catalogueoflife.garrulus.store.ident;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.catalogueoflife.garrulus.store.ident.IdScheme.Entity;
import org.jspecify.annotations.Nullable;

/** The identifier schemes the system knows: the built-ins plus any {@link IdScheme} bean, looked up by code. */
public final class IdentifierRegistry {

  private final Map<String, IdScheme> schemes = new LinkedHashMap<>();

  public IdentifierRegistry(Collection<IdScheme> extra) {
    for (IdScheme s : IdScheme.builtIns()) {
      add(s);
    }
    for (IdScheme s : extra) {
      add(s);
    }
  }

  private void add(IdScheme s) {
    if (schemes.putIfAbsent(s.code(), s) != null) {
      throw new IllegalStateException("Duplicate identifier scheme " + s.code());
    }
  }

  public @Nullable IdScheme find(String code) {
    return schemes.get(code);
  }

  /**
   * The identifiers of an entity that are shown, ordered by scheme order, then value. An unknown code is listed with its
   * code as label and no url; a known scheme of another entity or one that is not shown is left out.
   */
  public List<IdentifierLink> links(Entity e, List<Identifier> ids) {
    record Row(int order, IdentifierLink link) {}
    List<Row> rows = new ArrayList<>();
    for (Identifier id : ids) {
      IdScheme s = schemes.get(id.scheme());
      if (s == null) {
        rows.add(new Row(Integer.MAX_VALUE, new IdentifierLink(id.scheme(), id.scheme(), id.value(), null)));
      } else if (s.shown() && s.entities().contains(e)) {
        rows.add(new Row(s.sortOrder(), new IdentifierLink(s.code(), s.label(), id.value(), s.url(e, id.value()))));
      }
    }
    rows.sort(Comparator.<Row>comparingInt(Row::order).thenComparing(r -> r.link().value()));
    return rows.stream().map(Row::link).toList();
  }

  /** {@code SCHEME:value} (split at the first colon, scheme case-insensitive) as an identifier, or null if either part fails. */
  public @Nullable Identifier parse(@Nullable String schemeColonValue) {
    if (schemeColonValue == null) {
      return null;
    }
    int i = schemeColonValue.indexOf(':');
    if (i <= 0) {
      return null;
    }
    IdScheme s = schemes.get(schemeColonValue.substring(0, i).strip().toUpperCase(Locale.ROOT));
    if (s == null) {
      return null;
    }
    String v = s.normalise(schemeColonValue.substring(i + 1));
    return v == null ? null : new Identifier(s.code(), v);
  }
}
