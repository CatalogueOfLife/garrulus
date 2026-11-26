package org.catalogueoflife.garrulus.web.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnsubscribeTokensTest {

  final UnsubscribeTokens tokens = new UnsubscribeTokens("a test signing key");

  @Test
  void roundTrip() {
    String t = tokens.create(42);
    assertThat(t).startsWith("42.").matches("[0-9]+\\.[A-Za-z0-9_-]+");
    assertThat(tokens.verify(t)).isEqualTo(42L);
    // the same key gives the same token; another key does not accept it
    assertThat(new UnsubscribeTokens("a test signing key").verify(t)).isEqualTo(42L);
    assertThat(new UnsubscribeTokens("another key").verify(t)).isNull();
  }

  @Test
  void tamperedRejected() {
    String t = tokens.create(42);
    String mac = t.substring(t.indexOf('.') + 1);
    assertThat(tokens.verify("43." + mac)).isNull();
    char last = mac.charAt(mac.length() - 1);
    assertThat(tokens.verify("42." + mac.substring(0, mac.length() - 1) + (last == 'A' ? 'B' : 'A'))).isNull();
    assertThat(tokens.verify("42." + mac.substring(1))).isNull();
    assertThat(tokens.verify("42")).isNull();
    assertThat(tokens.verify("x." + mac)).isNull();
    assertThat(tokens.verify("")).isNull();
    assertThat(tokens.verify(null)).isNull();
    assertThat(tokens.verify("42.!!!")).isNull();
  }

  @Test
  void blankKeyIsRandomPerInstance() {
    UnsubscribeTokens a = new UnsubscribeTokens("");
    UnsubscribeTokens b = new UnsubscribeTokens(" ");
    assertThat(a.verify(a.create(7))).isEqualTo(7L);
    assertThat(b.verify(a.create(7))).isNull();
  }
}
