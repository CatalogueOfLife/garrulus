package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.catalogueoflife.nametracker.store.net.PublicHost;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The fields of a route config; the host rule itself is tested in the store's {@code PublicHostTest}. */
class RouteConfigCheckTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A resolver without DNS: the names of the tests and their addresses. */
  static final PublicHost.Resolver FAKE = host -> {
    Map<String, String> known = Map.of("www.mapress.com", "93.184.215.14", "www.zoores.ac.cn", "93.184.215.15",
        "intranet.example.org", "10.0.0.5", "rebind.example.org", "93.184.215.16,127.0.0.1");
    String a = known.get(host);
    if (a == null) throw new UnknownHostException(host);
    String[] parts = a.split(",");
    InetAddress[] out = new InetAddress[parts.length];
    for (int i = 0; i < parts.length; i++) out[i] = InetAddress.getByName(parts[i]);
    return out;
  };

  private static String check(String layer, String config) {
    return new RouteConfigCheck(FAKE).problem(layer, JSON.readTree(config));
  }

  @Test
  void publicHttpUrlsPass() {
    assertThat(check("OAI", "{\"base\":\"https://www.mapress.com/zt/oai\"}")).isNull();
    assertThat(check("MAGTECH", "{\"site\":\"http://www.zoores.ac.cn\",\"issn\":\"1000-0739\"}")).isNull();
    assertThat(check("EUROPEPMC", "{\"issns\":[\"1313-2989\"]}")).isNull();
  }

  @Test
  void theFieldIsNamedInEveryProblem() {
    assertThat(check("OAI", "{}")).isEqualTo("config needs base");
    assertThat(check("MAGTECH", "{\"site\":\"  \"}")).isEqualTo("config needs site");
    assertThat(check("OAI", "{\"base\":\"not a url\"}")).isEqualTo("base is not a URL");
    assertThat(check("OAI", "{\"base\":\"file:///etc/passwd\"}")).isEqualTo("base must be an http or https URL");
    assertThat(check("MAGTECH", "{\"site\":\"http://localhost:8080\",\"issn\":\"x\"}"))
        .isEqualTo("site names a local host (localhost)");
    assertThat(check("OAI", "{\"base\":\"https://rebind.example.org/oai\"}")).startsWith("base names a loopback")
        .contains("127.0.0.1");
    assertThat(check("OAI", "{\"base\":\"https://nowhere.example.org/oai\"}"))
        .isEqualTo("base does not resolve (nowhere.example.org)");
  }
}
