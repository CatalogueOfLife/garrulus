package org.catalogueoflife.nametracker.indexer.link;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;

/** A lookup the test scripts: what it answers per target id, what it throws, and the batches it was asked. */
class ScriptedLookup implements IdentifierLookup {

  final String name;
  final IdScheme.Entity entity;
  final List<String> schemes;
  final Map<Long, List<Identifier>> answers = new ConcurrentHashMap<>();
  final List<List<LinkTarget>> asked = new ArrayList<>();
  /** What a call throws; a supplier that returns null lets that call answer. */
  volatile Supplier<RuntimeException> failure;
  volatile boolean enabled = true;
  volatile boolean applies = true;
  int batchSize = 1;
  /** Whether the lookup is on after {@link #reset()}; a lookup only some tests use starts off. */
  final boolean enabledByDefault;

  ScriptedLookup(String name, IdScheme.Entity entity, String... schemes) {
    this(name, entity, true, schemes);
  }

  ScriptedLookup(String name, IdScheme.Entity entity, boolean enabledByDefault, String... schemes) {
    this.name = name;
    this.entity = entity;
    this.enabledByDefault = enabledByDefault;
    this.enabled = enabledByDefault;
    this.schemes = List.of(schemes);
  }

  void reset() {
    answers.clear();
    asked.clear();
    failure = null;
    enabled = enabledByDefault;
    applies = true;
    batchSize = 1;
  }

  long calls() {
    return asked.stream().mapToLong(List::size).sum();
  }

  @Override public String name() { return name; }
  @Override public List<String> schemes() { return schemes; }
  @Override public IdScheme.Entity appliesTo() { return entity; }
  @Override public boolean enabled() { return enabled; }
  @Override public int batchSize() { return batchSize; }
  @Override public boolean applies(LinkTarget t) { return applies; }

  @Override
  public synchronized Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) throws IOException {
    asked.add(List.copyOf(batch));
    RuntimeException f = failure == null ? null : failure.get();
    if (f != null) throw f;
    Map<Long, List<Identifier>> out = new java.util.HashMap<>();
    for (LinkTarget t : batch) if (answers.containsKey(t.id())) out.put(t.id(), answers.get(t.id()));
    return out;
  }
}
