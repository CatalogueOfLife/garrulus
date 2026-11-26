package org.catalogueoflife.garrulus.indexer;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton-container pattern: one postgres:19 per JVM, shared by every IT. The dispatcher claims nothing
 * (workers=0), so that a cached context cannot take the tasks of another IT; a test that needs it sets
 * {@code garrulus.indexer.workers} itself.
 *
 * <p>Hermetic by default: every external base URL points at the discard port of 127.0.0.1 (nothing listens there),
 * there is no API key and no Crossref mailto, the scheduled harvests are off, the finder rollout check at start is off,
 * and no local {@code .env} is read. An IT that talks to WireMock overrides the base URL it needs; the loopback names
 * are trusted by PoliteHttp on any port (bare-host entries, tests only: route configs of ITs point at WireMock).
 */
@SpringBootTest(classes = IndexerApplication.class, properties = {
    "garrulus.indexer.poll-interval=100ms",
    "garrulus.indexer.workers=0",
    "garrulus.dotenv=optional:file:/nonexistent/garrulus-tests.env[.properties]",
    "garrulus.harvest.enabled=false",
    "garrulus.http.trusted-hosts=localhost,127.0.0.1",
    "garrulus.finder.url=",
    "garrulus.finder.dictionary=",
    "garrulus.finder.rollout-on-start=false",
    "garrulus.openalex.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.openalex.content.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.openalex.api-key=",
    "garrulus.snapshot.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.clb.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.crossref.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.crossref.mailto=",
    "garrulus.europepmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.doaj.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.pmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.zenodo.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.links.plazi.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.links.wikidata.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.links.ipni.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.links.biodiversitypmc.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.content.institutional.elsevier.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.content.institutional.wiley.base-url=" + AbstractPostgresIT.UNROUTABLE,
    "garrulus.pensoft.base-url=" + AbstractPostgresIT.UNROUTABLE + "/{slug}"})
public abstract class AbstractPostgresIT {

  /** The discard port: connections are refused at once. */
  public static final String UNROUTABLE = "http://127.0.0.1:9";

  /** Every cached test context keeps its own pool of 10 connections open: more than the default 100 in all. */
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:19")
      .withCommand("postgres", "-c", "max_connections=300");

  static {
    POSTGRES.start();
  }
}
