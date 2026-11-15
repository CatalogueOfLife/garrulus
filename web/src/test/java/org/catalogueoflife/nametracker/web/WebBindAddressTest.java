package org.catalogueoflife.nametracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** M2-M: web listens on loopback unless NAMETRACKER_WEB_ADDRESS says otherwise, like the indexer. */
class WebBindAddressTest {

  private static String address(Map<String, Object> env) throws IOException {
    StandardEnvironment e = new StandardEnvironment();
    e.getPropertySources().addFirst(new MapPropertySource("env", env));
    new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
        .forEach(ps -> e.getPropertySources().addLast(ps));
    return e.getProperty("server.address");
  }

  @Test
  void loopbackByDefault() throws IOException {
    assertThat(address(Map.of())).isEqualTo("127.0.0.1");
  }

  @Test
  void overridable() throws IOException {
    assertThat(address(Map.of("NAMETRACKER_WEB_ADDRESS", "0.0.0.0"))).isEqualTo("0.0.0.0");
  }
}
