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
 * there is no API key and no Crossref mailto, the scheduled harvests are off, the finder rollout check at start is off,
 * and no local {@code .env} is read. An IT that talks to WireMock overrides the base URL it needs; the loopback names
 * are trusted by PoliteHttp on any port (bare-host entries, tests only: route configs of ITs point at WireMock).
 */
@SpringBootTest(classes = IndexerApplication.class, properties = {
    "nametracker.indexer.poll-interval=100ms",
    "nametracker.indexer.workers=0",
    "nametracker.dotenv=optional:file:/nonexistent/garrulus-tests.env[.properties]",
    "nametracker.harvest.enabled=false",
    "nametracker.http.trusted-hosts=localhost,127.0.0.1",
    "nametracker.finder.url=",
    "nametracker.finder.dictionary=",
    "nametracker.finder.rollout-on-start=false",
    "nametracker.openalex.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.openalex.content.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.openalex.api-key=",
    "nametracker.snapshot.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.clb.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.crossref.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.crossref.mailto=",
    "nametracker.europepmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.doaj.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.pmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.zenodo.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.links.plazi.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.links.wikidata.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.links.ipni.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.links.biodiversitypmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "nametracker.pensoft.base-url=" + AbstractPostgresIT.UNROUTABLE + "/{slug}"})
public abstract class AbstractPostgresIT {

  /** The discard port: connections are refused at once. */
  public static final String UNROUTABLE = "http://127.0.0.1:9";

  /** Every cached test context keeps its own pool of 10 connections open: more than the default 100 in all. */
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
      .withCommand("postgres", "-c", "max_connections=300");

  static {
    POSTGRES.start();
  }
}
