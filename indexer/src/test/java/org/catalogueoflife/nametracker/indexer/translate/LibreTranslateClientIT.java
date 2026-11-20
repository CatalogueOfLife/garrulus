package org.catalogueoflife.nametracker.indexer.translate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.junit.jupiter.api.Test;

@WireMockTest
class LibreTranslateClientIT {

  private static final String LANGS = "[{\"code\":\"zh\",\"targets\":[\"en\",\"de\"]},{\"code\":\"en\",\"targets\":[\"zh\"]},"
      + "{\"code\":\"ru\",\"targets\":[\"en\"]}]";

  private static LibreTranslateClient client(String base, Clock clock) {
    return new LibreTranslateClient(URI.create(base), "libretranslate-1.9.6/argos", Duration.ofSeconds(5), clock);
  }

  @Test
  void translatesAndCachesLanguages(WireMockRuntimeInfo wm) throws Exception {
    wm.getWireMock().register(get(urlEqualTo("/languages")).willReturn(aResponse().withBody(LANGS)));
    wm.getWireMock().register(post(urlEqualTo("/translate")).willReturn(aResponse()
        .withBody("{\"translatedText\":\"A new species of Carabus\"}")));
    LibreTranslateClient c = client(wm.getHttpBaseUrl(), Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC));

    assertThat(c.sources()).containsExactlyInAnyOrder("zh", "ru");
    assertThat(c.sources()).containsExactlyInAnyOrder("zh", "ru");
    assertThat(c.translate("一新种 Carabus", "zh")).isEqualTo("A new species of Carabus");
    assertThat(c.engine()).isEqualTo("libretranslate");
    assertThat(c.model()).isEqualTo("libretranslate-1.9.6/argos");
    wm.getWireMock().verifyThat(1, getRequestedFor(urlEqualTo("/languages")));
    wm.getWireMock().verifyThat(1, postRequestedFor(urlEqualTo("/translate")).withRequestBody(
        equalToJson("{\"q\":\"一新种 Carabus\",\"source\":\"zh\",\"target\":\"en\",\"format\":\"text\"}")));
  }

  @Test
  void connectionRefusedIsOutage() {
    LibreTranslateClient c = client("http://127.0.0.1:9", Clock.systemUTC());
    assertThatThrownBy(() -> c.translate("x", "zh")).isInstanceOf(ServiceOutageException.class);
    assertThatThrownBy(c::sources).isInstanceOf(ServiceOutageException.class);
  }

  @Test
  void fiveHundredIsOutage(WireMockRuntimeInfo wm) {
    wm.getWireMock().register(post(urlEqualTo("/translate")).willReturn(aResponse().withStatus(500)));
    Instant now = Instant.parse("2026-11-20T10:00:00Z");
    LibreTranslateClient c = client(wm.getHttpBaseUrl(), Clock.fixed(now, ZoneOffset.UTC));
    assertThatThrownBy(() -> c.translate("x", "zh")).isInstanceOfSatisfying(ServiceOutageException.class,
        e -> assertThat(e.retryAt()).isEqualTo(now.plus(Duration.ofMinutes(30))));
  }

  @Test
  void clientErrorIsAFailureNotAnOutage(WireMockRuntimeInfo wm) {
    wm.getWireMock().register(post(urlEqualTo("/translate")).willReturn(aResponse().withStatus(400)
        .withBody("{\"error\":\"bad\"}")));
    LibreTranslateClient c = client(wm.getHttpBaseUrl(), Clock.systemUTC());
    assertThatThrownBy(() -> c.translate("x", "zh")).isInstanceOf(java.io.IOException.class)
        .isNotInstanceOf(ServiceOutageException.class);
  }
}
