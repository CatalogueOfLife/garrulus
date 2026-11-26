package org.catalogueoflife.garrulus.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class AdminAccessConfiguredTest {

  @Configuration
  static class Cfg {
    @Bean
    @Conditional(AdminAccessConfigured.class)
    String marker() {
      return "on";
    }
  }

  private static boolean active(String token, String orcids) {
    try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
      Map<String, Object> props = new HashMap<>();
      if (token != null) {
        props.put(AdminAccessConfigured.TOKEN, token);
      }
      if (orcids != null) {
        props.put(AdminAccessConfigured.ORCIDS, orcids);
      }
      ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", props));
      ctx.register(Cfg.class);
      ctx.refresh();
      return ctx.containsBean("marker");
    }
  }

  @Test
  void tokenWithSingleQuoteStillActivates() {
    assertThat(active("it's a \"secret\" ' token", null)).isTrue();
  }

  @Test
  void plainTokenActivates() {
    assertThat(active("abc", null)).isTrue();
    assertThat(active("abc", "")).isTrue();
  }

  @Test
  void blankOrMissingTokenDeactivates() {
    assertThat(active("", null)).isFalse();
    assertThat(active("   ", null)).isFalse();
    assertThat(active(null, null)).isFalse();
  }

  @Test
  void validAdminOrcidActivatesWithoutToken() {
    assertThat(active(null, "0000-0002-1825-0097")).isTrue();
    assertThat(active("", "https://orcid.org/0000-0002-1825-0097")).isTrue();
  }

  @Test
  void onlyInvalidOrBlankOrcidsDeactivate() {
    assertThat(active("", "not-an-orcid")).isFalse();
    assertThat(active(null, " , ")).isFalse();
  }
}
