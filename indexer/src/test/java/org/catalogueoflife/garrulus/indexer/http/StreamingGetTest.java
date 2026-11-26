package org.catalogueoflife.garrulus.indexer.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StreamingGetTest {

  static final URI URI_ = URI.create("http://example.org/file.gz");

  /** A connection whose response head fails with the given exception (null: status 200); it records disconnect. */
  static final class FakeConnection extends HttpURLConnection {
    final IOException failure;
    boolean disconnected;

    FakeConnection(IOException failure) throws IOException {
      super(URI_.toURL());
      this.failure = failure;
    }

    @Override
    public int getResponseCode() throws IOException {
      if (failure != null) throw failure;
      return 200;
    }

    @Override
    public void disconnect() {
      disconnected = true;
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void connect() {}
  }

  @Test
  void headThatTimesOutClosesTheConnectionAndIsRetryable() throws Exception {
    FakeConnection c = new FakeConnection(new SocketTimeoutException("Read timed out"));
    assertThatThrownBy(() -> StreamingGet.open(URI_, Duration.ofSeconds(1), Duration.ofSeconds(1), u -> c))
        .isInstanceOfSatisfying(HttpProblem.class, p -> assertThat(p.retryable()).isTrue())
        .hasRootCauseInstanceOf(SocketTimeoutException.class);
    assertThat(c.disconnected).isTrue();
  }

  @Test
  void unknownHostClosesTheConnectionAndIsNotRetryable() throws Exception {
    FakeConnection c = new FakeConnection(new UnknownHostException("example.org"));
    assertThatThrownBy(() -> StreamingGet.open(URI_, Duration.ofSeconds(1), Duration.ofSeconds(1), u -> c))
        .isInstanceOfSatisfying(HttpProblem.class, p -> assertThat(p.retryable()).isFalse());
    assertThat(c.disconnected).isTrue();
  }

  @Test
  void openConnectionStaysOpenWithItsTimeouts() throws Exception {
    FakeConnection c = new FakeConnection(null);
    StreamingGet.Opened o = StreamingGet.open(URI_, Duration.ofSeconds(2), Duration.ofMinutes(5), u -> c);
    assertThat(o.status()).isEqualTo(200);
    assertThat(o.connection()).isSameAs(c);
    assertThat(c.disconnected).isFalse();
    assertThat(c.getConnectTimeout()).isEqualTo(2000);
    assertThat(c.getReadTimeout()).isEqualTo(300_000);
    assertThat(c.getRequestProperty("User-Agent")).isEqualTo(PoliteHttp.userAgent());
  }
}
