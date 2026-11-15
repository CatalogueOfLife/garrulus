package org.catalogueoflife.nametracker.indexer;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton-container pattern: one postgres:17 per JVM, shared by every IT. The dispatcher claims nothing
 * (workers=0), so that a cached context cannot take the tasks of another IT; a test that needs it sets
 * {@code nametracker.indexer.workers} itself.
 *
 * <p>Hermetic by default: every external base URL points at the discard port of 127.0.0.1 (nothing listens there),
 * there is no API key and no Crossref mailto, the scheduled harvests are off, and no local {@code .env} is read. An IT
 * that talks to WireMock overrides the base URL it needs.
 */
@SpringBootTest(classes = IndexerApplication.class, properties = {
    "nametracker.indexer.poll-interval=100ms",
    "nametracker.indexer.workers=0",
    "nametracker.dotenv=optional:file:/nonexistent/garrulus-tests.env[.properties]",
    "nametracker.harvest.enabled=false",
    "nametracker.finder.url=",
    "nametracker.finder.dictionary=",
    "nametracker.openalex.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.openalex.content.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.openalex.api-key=",
    "nametracker.crossref.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.crossref.mailto=",
    "nametracker.europepmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.pmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.pensoft.base-url=" + AbstractPostgresIT.UNROUTABLE + "/{slug}"})
public abstract class AbstractPostgresIT {

  /** The discard port: connections are refused at once. */
  public static final String UNROUTABLE = "http://127.0.0.1:9";

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
  }
}
