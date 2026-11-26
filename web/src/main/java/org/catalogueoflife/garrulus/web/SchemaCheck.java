package org.catalogueoflife.garrulus.web;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * Replaces Spring Boot's default "migrate on startup" in the web app: validates and never migrates, because the
 * indexer owns the schema. It runs inside the Flyway auto-configuration, so before the MyBatis beans and before the
 * web server accepts connections. A database that is ahead (migrations this build does not know, validated with
 * {@code ignoreMigrationPatterns("*:future")}) is fine, so the web app keeps running while the indexer rolls out a
 * newer schema (expand/contract).
 */
@Component
public class SchemaCheck implements FlywayMigrationStrategy {

  static final String BEHIND = "database schema is behind this web build; start the indexer first to migrate";
  static final String INCONSISTENT = "database schema does not match this web build (changed or missing migrations)";
  private static final Logger LOG = LoggerFactory.getLogger(SchemaCheck.class);

  @Override
  public void migrate(Flyway flyway) {
    // copy of the auto-configured Flyway, forced to tolerate migrations from a newer indexer
    Flyway validator = Flyway.configure()
        .configuration(flyway.getConfiguration())
        .ignoreMigrationPatterns("*:future")
        .load();
    ValidateResult result;
    try {
      result = validator.validateWithResult();
    } catch (FlywayException e) {
      throw new IllegalStateException(BEHIND + " (" + e.getMessage() + ")", e);
    }
    if (!result.validationSuccessful) {
      throw new IllegalStateException(
          (onlyPending(result.invalidMigrations) ? BEHIND : INCONSISTENT) + " (" + result.getAllErrorMessages() + ")");
    }
    LOG.info("Database schema is compatible with this build");
  }

  private static boolean onlyPending(List<ValidateOutput> invalid) {
    return !invalid.isEmpty()
        && invalid.stream().allMatch(o -> o.errorDetails != null
            && (o.errorDetails.errorCode == CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED
                || o.errorDetails.errorCode == CoreErrorCode.RESOLVED_REPEATABLE_MIGRATION_NOT_APPLIED));
  }
}
