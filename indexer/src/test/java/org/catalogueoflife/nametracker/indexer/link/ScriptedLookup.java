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
  volatile Supplier<RuntimeException> failure;
  volatile boolean enabled = true;
  int batchSize = 1;

  ScriptedLookup(String name, IdScheme.Entity entity, String... schemes) {
    this.name = name;
    this.entity = entity;
    this.schemes = List.of(schemes);
  }

  void reset() {
    answers.clear();
    asked.clear();
    failure = null;
    enabled = true;
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
  @Override public boolean applies(LinkTarget t) { return true; }

  @Override
  public synchronized Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) throws IOException {
    asked.add(List.copyOf(batch));
    if (failure != null) throw failure.get();
    Map<Long, List<Identifier>> out = new java.util.HashMap<>();
    for (LinkTarget t : batch) if (answers.containsKey(t.id())) out.put(t.id(), answers.get(t.id()));
    return out;
  }
}
