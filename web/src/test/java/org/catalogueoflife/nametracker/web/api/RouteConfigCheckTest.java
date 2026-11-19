package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RouteConfigCheckTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A resolver without DNS: the names of the tests and their addresses. */
  static final RouteConfigCheck.HostResolver FAKE = host -> {
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

  @Test
  void hostsAreResolvedBeforeTheyAreAccepted() {
    // short numeric forms are addresses too
    assertThat(check("OAI", "{\"base\":\"http://127.1/oai\"}")).isNotNull();
    assertThat(check("OAI", "{\"base\":\"http://10.1/oai\"}")).isNotNull();
    // a name of a private address, or with any loopback address among its addresses
    assertThat(check("OAI", "{\"base\":\"https://intranet.example.org/oai\"}")).contains("10.0.0.5");
    assertThat(check("OAI", "{\"base\":\"https://rebind.example.org/oai\"}")).contains("127.0.0.1");
    // a name that does not resolve
    assertThat(check("OAI", "{\"base\":\"https://nowhere.example.org/oai\"}")).contains("does not resolve");
  }
}
