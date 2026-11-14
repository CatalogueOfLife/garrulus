package org.catalogueoflife.nametracker.finder.api;

import java.util.*;

/** Null-safe immutable copies used by the record constructors so that collections are never null. */
final class Util {
  private Util() {}

  static <T> List<T> list(List<T> l) {
    return l == null ? List.of() : List.copyOf(l);
  }

  static <K, V> Map<K, V> map(Map<K, V> m) {
    return m == null || m.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
  }
}
