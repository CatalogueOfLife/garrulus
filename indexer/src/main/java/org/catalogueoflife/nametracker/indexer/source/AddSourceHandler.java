package org.catalogueoflife.nametracker.indexer.source;

import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code ADD_SOURCE}, payload {@code {"openalexId":"S…","tier":"CORE|BROAD|CANDIDATE"}}: an admin adds a journal. The row
 * gets origin MANUAL and the admin's tier ({@code tier_set_by} ADMIN, so seeding never changes it again); CORE and
 * BROAD sources are queued for onboarding.
 */
@Component
public class AddSourceHandler implements TaskHandler {

  public static final String TYPE = "ADD_SOURCE";
  private static final Set<String> TIERS = Set.of("CORE", "BROAD", "CANDIDATE");

  private final OpenAlexClient client;
  private final SourceMapper sources;
  private final SourceSeeder seeder;

  public AddSourceHandler(OpenAlexClient client, SourceMapper sources, SourceSeeder seeder) {
    this.client = client;
    this.sources = sources;
    this.seeder = seeder;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    JsonNode payload = FinderJson.mapper().readTree(task.payload());
    String id = SourceSeeder.bareId(payload.path("openalexId").asString(""));
    String tier = payload.path("tier").asString("");
    if (id.isEmpty() || !TIERS.contains(tier)) {
      throw new IllegalArgumentException("ADD_SOURCE needs openalexId and a tier of CORE, BROAD or CANDIDATE: "
          + task.payload());
    }
    JsonNode node = client.source(id).orElseThrow(() -> new IllegalArgumentException("Unknown OpenAlex source " + id));
    Source row = SourceSeeder.build(node, id, tier, "MANUAL", "ADMIN", null);
    long sourceId = sources.upsertSeed(row);
    // upsertSeed leaves the tier of an existing row alone; an admin's choice always wins
    sources.setTier(sourceId, tier, true, "ADMIN");
    if (!"CANDIDATE".equals(tier)) {
      seeder.enqueueOnboarding(sourceId);
    }
  }
}
