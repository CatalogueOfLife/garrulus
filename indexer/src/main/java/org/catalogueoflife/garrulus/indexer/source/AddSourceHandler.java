package org.catalogueoflife.garrulus.indexer.source;

import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.garrulus.indexer.registry.SeedUpsert;
import org.catalogueoflife.garrulus.indexer.stats.DisciplineService;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code ADD_SOURCE}, payload {@code {"openalexId":"S…","tier":"CORE|BROAD|CANDIDATE"}}: an admin adds a journal. The row
 * gets origin MANUAL and the admin's tier ({@code tier_set_by} ADMIN, so seeding never changes it again); CORE and
 * BROAD sources are queued for onboarding. Written through {@link SeedUpsert}: a candidate the registry already holds
 * for the journal is adopted, and any other duplicate folds into it.
 */
@Component
public class AddSourceHandler implements TaskHandler {

  public static final String TYPE = "ADD_SOURCE";
  private static final Set<String> TIERS = Set.of("CORE", "BROAD", "CANDIDATE");

  private final OpenAlexClient client;
  private final SourceMapper sources;
  private final SourceSeeder seeder;
  private final DisciplineService disciplines;
  private final SeedUpsert upsert;

  public AddSourceHandler(OpenAlexClient client, SourceMapper sources, SourceSeeder seeder,
      DisciplineService disciplines, SeedUpsert upsert) {
    this.upsert = upsert;
    this.disciplines = disciplines;
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
    // upsertSeed leaves the tier of an existing row alone; an admin's choice always wins, written before the fold so a
    // candidate the registry already held as a duplicate folds into the admin's journal
    long sourceId = upsert.upsert(id, keep -> SourceSeeder.build(node, id, tier, "MANUAL", "ADMIN", null, keep),
        (sid, before) -> sources.setTier(sid, tier, true, "ADMIN")).sourceId();
    disciplines.applyOpenAlex(sourceId, node);
    if (!"CANDIDATE".equals(tier)) {
      seeder.enqueueOnboarding(sourceId);
    }
    if (tier.equals("CORE") || tier.equals("BROAD")) seeder.verifyPendingRoutes(sourceId);
  }
}
