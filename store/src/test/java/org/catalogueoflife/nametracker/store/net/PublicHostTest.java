package org.catalogueoflife.nametracker.store.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicHostTest {

  /** A resolver without DNS: the names of the tests and their addresses. */
  public static final PublicHost.Resolver FAKE = host -> {
    Map<String, String> known = Map.of("www.mapress.com", "93.184.215.14", "www.zoores.ac.cn", "93.184.215.15",
        "intranet.example.org", "10.0.0.5", "rebind.example.org", "93.184.215.16,127.0.0.1",
        "journal.test", "127.0.0.1");
    String a = known.get(host);
    if (a == null) throw new UnknownHostException(host);
    String[] parts = a.split(",");
    InetAddress[] out = new InetAddress[parts.length];
    for (int i = 0; i < parts.length; i++) out[i] = InetAddress.getByName(parts[i]);
    return out;
  };

  private static final PublicHost HOSTS = new PublicHost(FAKE);

  private static String check(String url) {
    return HOSTS.problem(URI.create(url));
  }

  @Test
  void publicHttpUrlsPass() {
    assertThat(check("https://www.mapress.com/zt/oai")).isNull();
    assertThat(check("http://www.zoores.ac.cn")).isNull();
    assertThat(check("https://93.184.215.14/oai")).isNull();
    PublicHost.Checked c = HOSTS.check(URI.create("https://www.mapress.com/zt/oai"));
    assertThat(c.ok()).isTrue();
    assertThat(c.addresses()).extracting(InetAddress::getHostAddress).containsExactly("93.184.215.14");
  }

  @Test
  void otherSchemesAndInternalHostsAreRefused() {
    assertThat(check("file:///etc/passwd")).contains("http");
    assertThat(check("ftp://example.org/oai")).contains("http");
    assertThat(check("http://user:pw@www.mapress.com/")).contains("user info");
    assertThat(check("http://localhost:8080")).contains("localhost");
    for (String host : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
        "0.0.0.0", "100.64.0.1", "[::1]", "[fd00::1]", "[fe80::1]", "[::ffff:127.0.0.1]", "postgres", "db.internal",
        "app.local", "x.localhost"}) {
      assertThat(check("http://" + host + "/oai")).as(host).isNotNull();
    }
  }

  @Test
  void hostsAreResolvedBeforeTheyAreAccepted() {
    // short numeric forms are addresses too
    assertThat(check("http://127.1/oai")).isNotNull();
    assertThat(check("http://10.1/oai")).isNotNull();
    // a name of a private address, or with any loopback address among its addresses
    assertThat(check("https://intranet.example.org/oai")).contains("10.0.0.5");
    assertThat(check("https://rebind.example.org/oai")).contains("127.0.0.1");
    // a name that does not resolve
    PublicHost.Checked c = HOSTS.check(URI.create("https://nowhere.example.org/oai"));
    assertThat(c.problem()).contains("does not resolve");
    assertThat(c.unresolved()).isTrue();
    assertThat(HOSTS.check(URI.create("https://intranet.example.org/")).unresolved()).isFalse();
  }

  @Test
  void allowedTestNamesSkipTheAddressRule() {
    assertThat(check("http://journal.test:8080/")).contains("127.0.0.1");
    PublicHost allowing = new PublicHost(FAKE, Set.of("Journal.test"));
    assertThat(allowing.problem(URI.create("http://journal.test:8080/"))).isNull();
    // the allow-list names hosts, not addresses
    assertThat(allowing.problem(URI.create("http://127.0.0.1:8080/"))).isNotNull();
    assertThat(allowing.problem(URI.create("http://intranet.example.org/"))).isNotNull();
  }
}
