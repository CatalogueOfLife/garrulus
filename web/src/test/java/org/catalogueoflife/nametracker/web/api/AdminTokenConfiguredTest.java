package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

class AdminTokenConfiguredTest {

  @Configuration
  static class Cfg {
    @Bean
    @Conditional(AdminTokenConfigured.class)
    String marker() {
      return "on";
    }
  }

  private static boolean active(String token) {
    try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
      if (token != null) {
        ctx.getEnvironment().getPropertySources()
            .addFirst(new MapPropertySource("t", Map.of(AdminTokenConfigured.PROPERTY, token)));
      }
      ctx.register(Cfg.class);
      ctx.refresh();
      return ctx.containsBean("marker");
    }
  }

  @Test
  void tokenWithSingleQuoteStillActivates() {
    assertThat(active("it's a \"secret\" ' token")).isTrue();
  }

  @Test
  void plainTokenActivates() {
    assertThat(active("abc")).isTrue();
  }

  @Test
  void blankOrMissingTokenDeactivates() {
    assertThat(active("")).isFalse();
    assertThat(active("   ")).isFalse();
    assertThat(active(null)).isFalse();
  }
}
