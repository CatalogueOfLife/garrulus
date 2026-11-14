package org.catalogueoflife.nametracker.web;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Refuses to start against a database that is behind this build. Never migrates: the indexer owns the schema. A
 * database that is ahead (migrations this build does not know) is fine, so the web app keeps running while the
 * indexer rolls out a newer schema (expand/contract).
 */
@Component
public class SchemaCheck implements ApplicationRunner {

  static final String BEHIND = "database schema is behind this web build; start the indexer first to migrate";
  private static final Logger LOG = LoggerFactory.getLogger(SchemaCheck.class);

  private final DataSource dataSource;

  public SchemaCheck(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void run(ApplicationArguments args) {
    ValidateResult result;
    try {
      result =
          Flyway.configure()
              .dataSource(dataSource)
              .locations("classpath:db/migration")
              .ignoreMigrationPatterns("*:future")
              .load()
              .validateWithResult();
    } catch (FlywayException e) {
      throw new IllegalStateException(BEHIND + " (" + e.getMessage() + ")", e);
    }
    if (!result.validationSuccessful) {
      throw new IllegalStateException(BEHIND + " (" + result.getAllErrorMessages() + ")");
    }
    LOG.info("Database schema is compatible with this build");
  }
}
