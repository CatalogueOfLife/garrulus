package org.catalogueoflife.nametracker.indexer.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.store.net.PublicHost;
import org.junit.jupiter.api.Test;

/** Hostile servers: the page read is bounded in time and in header size, whatever the server does. */
class JournalPageTest {

  private static final PublicHost HOSTS =
      new PublicHost(h -> new InetAddress[] {InetAddress.getLoopbackAddress()}, Set.of("journal.test"));

  @FunctionalInterface
  interface Script {
    void run(OutputStream out) throws IOException, InterruptedException;
  }

  /** Serves one connection with the script, ignoring the request; returns the time the fetch took. */
  private static Duration serve(Script script, Duration deadline, String expectedReason) throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread t = Thread.ofVirtual().start(() -> {
        try (Socket s = server.accept()) {
          script.run(s.getOutputStream());
        } catch (IOException | InterruptedException e) {
          // the client hung up
        }
      });
      JournalPage page = new JournalPage(EuropePmcSearchClientTest.http(), HOSTS, deadline);
      long start = System.nanoTime();
      JournalPage.Fetched f = page.fetch(URI.create("http://journal.test:" + server.getLocalPort() + "/"));
      Duration took = Duration.ofNanos(System.nanoTime() - start);
      assertThat(f.reason()).isEqualTo(expectedReason);
      t.interrupt();
      return took;
    }
  }

  private static byte[] ascii(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  void tricklingHeadersHitTheOverallDeadline() throws Exception {
    Duration took = serve(out -> {
      out.write(ascii("HTTP/1.1 200 OK\r\n"));
      for (int i = 0; i < 60; i++) { // 18 s in all, each line well inside the 30 s socket timeout
        out.write(ascii("X-Slow-" + i + ": a\r\n"));
        out.flush();
        Thread.sleep(300);
      }
    }, Duration.ofSeconds(2), "PAGE_UNREACHABLE");
    assertThat(took).isLessThan(Duration.ofSeconds(6));
  }

  @Test
  void tricklingBodyHitsTheOverallDeadline() throws Exception {
    Duration took = serve(out -> {
      out.write(ascii("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 100000\r\n\r\n"));
      for (int i = 0; i < 100; i++) {
        out.write(ascii("<p>ISSN 1175-5326</p>"));
        out.flush();
        Thread.sleep(300);
      }
    }, Duration.ofSeconds(2), "PAGE_UNREACHABLE");
    assertThat(took).isLessThan(Duration.ofSeconds(6));
  }

  @Test
  void endlessHeaderLineIsCutOff() throws Exception {
    Duration took = serve(out -> {
      out.write(ascii("HTTP/1.1 200 OK\r\nX-Long: "));
      byte[] chunk = ascii("a".repeat(64 * 1024));
      for (int i = 0; i < 4096; i++) out.write(chunk); // 256 MB, if anybody read it
    }, Duration.ofSeconds(30), "PAGE_UNREACHABLE");
    assertThat(took).isLessThan(Duration.ofSeconds(10));
  }

  @Test
  void tooManyHeadersAreCutOff() throws Exception {
    Duration took = serve(out -> {
      out.write(ascii("HTTP/1.1 200 OK\r\n"));
      for (int i = 0; i < 100_000; i++) out.write(ascii("X-" + i + ": a\r\n"));
      out.write(ascii("Content-Type: text/html\r\nContent-Length: 20\r\n\r\n<p>ISSN 1175-5326</p>"));
    }, Duration.ofSeconds(30), "PAGE_UNREACHABLE");
    assertThat(took).isLessThan(Duration.ofSeconds(10));
  }
}
