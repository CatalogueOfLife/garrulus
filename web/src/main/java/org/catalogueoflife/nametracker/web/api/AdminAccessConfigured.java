package org.catalogueoflife.nametracker.web.api;

import org.catalogueoflife.nametracker.web.auth.AdminOrcids;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Maps the admin controllers when there is a way in: {@code nametracker.admin.token} is set and not blank, or
 * {@code nametracker.admin.orcids} lists at least one valid ORCID iD. Reads the properties directly, no SpEL.
 * Without either the admin endpoints do not exist (404).
 */
public class AdminAccessConfigured implements Condition {

  static final String TOKEN = "nametracker.admin.token";
  static final String ORCIDS = AdminOrcids.PROPERTY;

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String token = context.getEnvironment().getProperty(TOKEN);
    return (token != null && !token.isBlank()) || AdminOrcids.anyValid(context.getEnvironment().getProperty(ORCIDS));
  }
}
