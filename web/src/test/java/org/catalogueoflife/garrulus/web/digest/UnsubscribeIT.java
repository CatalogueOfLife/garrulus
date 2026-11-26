package org.catalogueoflife.garrulus.web.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.web.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class UnsubscribeIT extends AbstractPostgresIT {

  static final AtomicInteger SEQ = new AtomicInteger();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired UnsubscribeTokens tokens;
  @Value("${garrulus.public-url}") String publicUrl;

  long user() {
    return jdbc.queryForObject("INSERT INTO app_user (email, email_verified, digest_since, time_zone, digest_frequency, "
        + "digest_weekday, digest_hour, display_name) VALUES (?, now(), now(), 'Europe/Berlin', 'WEEKLY', 5, 9, 'Ada') "
        + "RETURNING id", Long.class, "unsub" + SEQ.incrementAndGet() + "@example.org");
  }

  Map<String, Object> settings(long id) {
    return jdbc.queryForMap("SELECT digest_frequency, time_zone, digest_weekday, digest_hour, display_name "
        + "FROM app_user WHERE id = ?", id);
  }

  HttpResponse<String> send(String method, String query, String body) throws Exception {
    // no cookies, no CSRF token: as a mail client's one-click POST (RFC 8058)
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/unsubscribe" + query))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void oneClickPostTurnsOff() throws Exception {
    long u = user();
    long other = user();
    HttpResponse<String> r = send("POST", "?t=" + tokens.create(u), "List-Unsubscribe=One-Click");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{}");
    assertThat(r.headers().firstValue("Set-Cookie")).isEmpty();
    Map<String, Object> s = settings(u);
    assertThat(s.get("digest_frequency")).isEqualTo("OFF");
    assertThat(s.get("time_zone")).isEqualTo("Europe/Berlin");
    assertThat(((Number) s.get("digest_weekday")).intValue()).isEqualTo(5);
    assertThat(((Number) s.get("digest_hour")).intValue()).isEqualTo(9);
    assertThat(s.get("display_name")).isEqualTo("Ada");
    assertThat(settings(other).get("digest_frequency")).isEqualTo("WEEKLY");
    // twice is fine
    assertThat(send("POST", "?t=" + tokens.create(u), "List-Unsubscribe=One-Click").statusCode()).isEqualTo(200);

    // a token of another user id with this MAC, a broken one, none at all: 400, nothing changes
    String mac = tokens.create(u).substring(tokens.create(u).indexOf('.') + 1);
    assertThat(send("POST", "?t=" + other + "." + mac, "List-Unsubscribe=One-Click").statusCode()).isEqualTo(400);
    assertThat(send("POST", "?t=garbage", "").statusCode()).isEqualTo(400);
    assertThat(send("POST", "", "").statusCode()).isEqualTo(400);
    assertThat(settings(other).get("digest_frequency")).isEqualTo("WEEKLY");
  }

  @Test
  void getDoesNotTurnOff() throws Exception {
    long u = user();
    String token = tokens.create(u);
    HttpResponse<String> r = send("GET", "?t=" + token, null);
    // mail clients without RFC 8058 open the URL: they land on the SPA page, which asks and posts
    assertThat(r.statusCode()).isEqualTo(302);
    assertThat(r.headers().firstValue("Location")).hasValue(publicUrl + "/unsubscribe?t=" + token);
    assertThat(settings(u).get("digest_frequency")).isEqualTo("WEEKLY");
    // whatever the parameter holds, it is passed on encoded
    r = send("GET", "?t=a%26b%3Dc", null);
    assertThat(r.headers().firstValue("Location")).hasValue(publicUrl + "/unsubscribe?t=a%26b%3Dc");
    assertThat(send("GET", "", null).headers().firstValue("Location")).hasValue(publicUrl + "/unsubscribe");
  }
}
