package org.catalogueoflife.nametracker.store;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Singleton-container pattern: one postgres:17 per JVM, shared by every IT. */
@SpringBootTest(classes = StoreTestApplication.class)
public abstract class AbstractPostgresIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
  }
}
