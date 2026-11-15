package org.catalogueoflife.nametracker.indexer;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton-container pattern: one postgres:17 per JVM, shared by every IT. The dispatcher claims nothing
 * (workers=0), so that a cached context cannot take the tasks of another IT; a test that needs it sets
 * {@code nametracker.indexer.workers} itself.
 */
@SpringBootTest(classes = IndexerApplication.class, properties = {"nametracker.indexer.poll-interval=100ms", "nametracker.indexer.workers=0"})
public abstract class AbstractPostgresIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
  }
}
