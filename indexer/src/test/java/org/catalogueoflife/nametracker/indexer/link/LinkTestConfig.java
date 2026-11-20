package org.catalogueoflife.nametracker.indexer.link;

import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** An extra scheme (no migration, no registry edit) and one scripted lookup per entity. */
@TestConfiguration
class LinkTestConfig {

  @Bean
  IdScheme testdbScheme() {
    return IdScheme.define("TESTDB", "Test database", Set.of(IdScheme.Entity.WORK, IdScheme.Entity.SOURCE),
        raw -> raw.strip().matches("\\d+") ? raw.strip() : null, Map.of(IdScheme.Entity.WORK, "https://testdb.example/{v}"),
        500, true);
  }

  @Bean
  ScriptedLookup workLookup() {
    return new ScriptedLookup("test", IdScheme.Entity.WORK, "TESTDB");
  }

  @Bean
  ScriptedLookup sourceLookup() {
    return new ScriptedLookup("testsrc", IdScheme.Entity.SOURCE, "TESTDB", "WIKIDATA");
  }
}
