package org.catalogueoflife.nametracker.indexer.source;

import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.task.Task;
import org.springframework.stereotype.Component;

/** {@code SEED_SOURCES} (payload {@code {}}, dedup key {@code seed-sources}): (re)builds the journal registry. */
@Component
public class SeedSourcesHandler implements TaskHandler {

  public static final String TYPE = "SEED_SOURCES";

  private final SourceSeeder seeder;

  public SeedSourcesHandler(SourceSeeder seeder) {
    this.seeder = seeder;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    seeder.seed();
  }
}
