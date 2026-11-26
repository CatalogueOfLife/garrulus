package org.catalogueoflife.garrulus.web.auth;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/** A tiny cookie-keeping HTTP client, the way the SPA talks to web: session cookie plus the XSRF-TOKEN echo. */
public final class Browser {

  final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
  private final HttpClient http =
      HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
  private final String base;

  public Browser(int port) {
    this.base = "http://localhost:" + port;
  }

  public HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<String> json(String method, String path, String body, boolean csrf) throws Exception {
    return send(method, path, "application/json", body, csrf);
  }

  public HttpResponse<String> form(String path, String name, String value, boolean csrf) throws Exception {
    return send("POST", path, "application/x-www-form-urlencoded",
        name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8), csrf);
  }

  public HttpResponse<String> send(String method, String path, String contentType, String body, boolean csrf)
      throws Exception {
    return send(method, path, contentType, body, csrf ? xsrf() : null);
  }

  /** With this exact X-XSRF-TOKEN header (none if null), whatever the cookie says. */
  public HttpResponse<String> send(String method, String path, String contentType, String body, String xsrfHeader)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
        .header("Content-Type", contentType)
        .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (xsrfHeader != null) {
      b.header("X-XSRF-TOKEN", xsrfHeader);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** A JSON request (no body if null) with exactly these extra headers, keeping this browser's cookies. */
  public HttpResponse<String> json(String method, String path, String body, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(b::header);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** The CSRF cookie value; fetched with a GET first if this browser has none yet. */
  public String xsrf() throws Exception {
    Optional<String> token = cookie("XSRF-TOKEN");
    if (token.isEmpty()) {
      get("/api/auth/config");
      token = cookie("XSRF-TOKEN");
    }
    return token.orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie"));
  }

  /** Takes over the other browser's cookies (session and XSRF), e.g. to call another server with the same session. */
  public void copyCookiesFrom(Browser other) {
    other.cookies.getCookieStore().getCookies().forEach(c -> cookies.getCookieStore().add(URI.create(base), c));
  }

  public Optional<String> cookie(String name) {
    return cookies.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals(name) && !c.hasExpired())
        .map(HttpCookie::getValue)
        .findFirst();
  }

  /** Requests a sign-in link and signs in with the token from the captured mail; returns the /login/ott answer. */
  public HttpResponse<String> signIn(String email, CapturingMailSender mail) throws Exception {
    HttpResponse<String> link = json("POST", "/api/auth/link", "{\"email\":\"" + email + "\"}", false);
    if (link.statusCode() != 202) {
      throw new AssertionError("link request answered " + link.statusCode());
    }
    return form("/login/ott", "token", mail.lastTokenFor(email.strip().toLowerCase()), true);
  }
}
