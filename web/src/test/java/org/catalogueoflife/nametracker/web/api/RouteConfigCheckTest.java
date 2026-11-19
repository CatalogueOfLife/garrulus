package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RouteConfigCheckTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String check(String layer, String config) {
    return RouteConfigCheck.problem(layer, JSON.readTree(config));
  }

  @Test
  void publicHttpUrlsPass() {
    assertThat(check("OAI", "{\"base\":\"https://www.mapress.com/zt/oai\"}")).isNull();
    assertThat(check("MAGTECH", "{\"site\":\"http://www.zoores.ac.cn\",\"issn\":\"1000-0739\"}")).isNull();
    assertThat(check("OAI", "{\"base\":\"https://93.184.215.14/oai\"}")).isNull();
    assertThat(check("EUROPEPMC", "{\"issns\":[\"1313-2989\"]}")).isNull();
  }

  @Test
  void otherSchemesAndInternalHostsAreRefused() {
    assertThat(check("OAI", "{}")).contains("base");
    assertThat(check("OAI", "{\"base\":\"file:///etc/passwd\"}")).contains("http");
    assertThat(check("OAI", "{\"base\":\"ftp://example.org/oai\"}")).contains("http");
    assertThat(check("OAI", "{\"base\":\"not a url\"}")).isNotNull();
    assertThat(check("MAGTECH", "{\"site\":\"http://localhost:8080\",\"issn\":\"x\"}")).contains("localhost");
    for (String host : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
        "0.0.0.0", "100.64.0.1", "[::1]", "[fd00::1]", "[fe80::1]", "postgres", "db.internal", "app.local"}) {
      assertThat(check("OAI", "{\"base\":\"http://" + host + "/oai\"}")).as(host).isNotNull();
    }
  }
}
