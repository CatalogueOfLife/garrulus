package org.catalogueoflife.nametracker.web.api;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when {@code nametracker.admin.token} is set and not blank. Reads the property directly, no SpEL. */
public class AdminTokenConfigured implements Condition {

  static final String PROPERTY = "nametracker.admin.token";

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String token = context.getEnvironment().getProperty(PROPERTY);
    return token != null && !token.isBlank();
  }
}
