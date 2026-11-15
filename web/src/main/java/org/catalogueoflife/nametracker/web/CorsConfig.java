package org.catalogueoflife.nametracker.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the taxon endpoints only, so that the COL portal can embed the Literature widget. GET only; the origins
 * are the comma-separated {@code nametracker.web.cors-origins}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private final String[] origins;

  public CorsConfig(@Value("${nametracker.web.cors-origins}") String[] origins) {
    this.origins = java.util.Arrays.stream(origins).map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (origins.length > 0) {
      registry.addMapping("/api/taxa/**").allowedOrigins(origins).allowedMethods("GET");
    }
  }
}
