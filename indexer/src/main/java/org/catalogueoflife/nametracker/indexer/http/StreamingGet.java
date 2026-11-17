package org.catalogueoflife.nametracker.indexer.http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;

/**
 * A GET whose body is read as a stream, through {@link HttpURLConnection}: its read timeout applies to every read of the
 * socket, while the JDK HttpClient has none for a body, so a download that stalls would block for ever. Used for the
 * OpenAlex snapshot files and the XR zip. The caller reads the body and disconnects.
 */
public final class StreamingGet {

  /** An open connection and its status; the response head has arrived. */
  public record Opened(HttpURLConnection connection, int status) {}

  /** Opens a connection to a URI; replaceable in tests. */
  @FunctionalInterface
  interface Connector {
    URLConnection connect(URI uri) throws IOException;
  }

  private StreamingGet() {}

  /**
   * Sends the GET (User-Agent of {@link PoliteHttp}, redirects followed) and waits for the response head. A connection
   * that cannot be made or a head that does not arrive within the read timeout is a retryable {@link HttpProblem}, an
   * unknown host a non-retryable one; the connection is closed then.
   */
  public static Opened open(URI uri, Duration connectTimeout, Duration readTimeout) {
    return open(uri, connectTimeout, readTimeout, u -> u.toURL().openConnection());
  }

  static Opened open(URI uri, Duration connectTimeout, Duration readTimeout, Connector connector) {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) connector.connect(uri);
      conn.setConnectTimeout(millis(connectTimeout));
      conn.setReadTimeout(millis(readTimeout));
      conn.setInstanceFollowRedirects(true);
      conn.setRequestProperty("User-Agent", PoliteHttp.userAgent());
      return new Opened(conn, conn.getResponseCode());
    } catch (IOException e) {
      if (conn != null) conn.disconnect();
      if (PoliteHttp.unknownHost(e)) throw new HttpProblem(uri, 0, false, "Unknown host: " + uri, e);
      throw new HttpProblem(uri, 0, true, "I/O failure: " + uri, e);
    }
  }

  private static int millis(Duration d) {
    return (int) Math.min(Integer.MAX_VALUE, d.toMillis()); // 0: no timeout
  }
}
