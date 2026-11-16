package org.catalogueoflife.nametracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;

/** ORCID sign-in. Nothing here talks to orcid.org: the success handler is called with a test OIDC login. */
class OrcidLoginIT extends AbstractAuthIT {

  @Test
  void orcidOffMeansNoOauthEndpoints() throws Exception {
    Browser b = browser();
    assertThat(b.get("/oauth2/authorization/orcid").statusCode()).isEqualTo(404);
    assertThat(b.get("/login/oauth2/code/orcid?code=x&state=y").statusCode()).isEqualTo(404);
    assertThat(b.get("/api/auth/config").body()).contains("\"orcid\":false");
  }

  @Nested
  @TestPropertySource(properties = {
      "nametracker.orcid.client-id=APP-TEST", "nametracker.orcid.client-secret=test-secret",
      "nametracker.orcid.base-url=https://orcid.example"})
  class Configured {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrcidLoginSuccessHandler handler;

    @AfterEach
    void clearContext() {
      TestSecurityContextHolder.clearContext();
      SecurityContextHolder.clearContext();
    }

    record Login(MockHttpServletRequest request, MockHttpServletResponse response) {

      String redirect() {
        return response.getRedirectedUrl();
      }

      /** The account id of the principal saved to the session. */
      long sessionUser() {
        SecurityContext c = (SecurityContext) request.getSession().getAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return ((GarrulusPrincipal) c.getAuthentication().getPrincipal()).userId();
      }
    }

    /** An ORCID OIDC login (spring-security-test) handed to the success handler, as the login filter would. */
    Login login(String orcid, Consumer<OidcIdToken.Builder> claims) throws Exception {
      MockHttpServletRequest testLogin = oidcLogin().idToken(t -> {
        t.subject(orcid);
        claims.accept(t);
      }).postProcessRequest(new MockHttpServletRequest());
      Authentication auth = WebTestUtils.getSecurityContextRepository(testLogin)
          .loadDeferredContext(testLogin).get().getAuthentication();
      assertThat(auth.getPrincipal()).isInstanceOf(OidcUser.class);
      TestSecurityContextHolder.clearContext();
      MockHttpServletRequest request = new MockHttpServletRequest();
      MockHttpServletResponse response = new MockHttpServletResponse();
      handler.onAuthenticationSuccess(request, response, auth);
      return new Login(request, response);
    }

    @Test
    void firstOrcidLoginCreatesAccountWithoutEmail() throws Exception {
      String orcid = orcid();
      Login login = login(orcid, t -> t.claim("name", "Ada Lovelace").claim("given_name", "Ada"));

      Map<String, Object> row = jdbc.queryForMap(
          "select id, email, orcid, display_name, last_login from app_user where orcid = ?", orcid);
      assertThat(row.get("email")).isNull();
      assertThat(row.get("display_name")).isEqualTo("Ada Lovelace");
      assertThat(row.get("last_login")).isNotNull();
      assertThat(login.sessionUser()).isEqualTo((Long) row.get("id"));
      assertThat(login.redirect()).isEqualTo("http://localhost:5173/account?addEmail=1");

      // without a name claim: given and family name; without both: none
      String other = orcid();
      login(other, t -> t.claim("given_name", "Grace").claim("family_name", "Hopper"));
      assertThat(jdbc.queryForObject("select display_name from app_user where orcid = ?", String.class, other))
          .isEqualTo("Grace Hopper");
      String nameless = orcid();
      login(nameless, t -> {});
      assertThat(jdbc.queryForObject("select display_name from app_user where orcid = ?", String.class, nameless))
          .isNull();
    }

    @Test
    void secondLoginFindsSameAccount() throws Exception {
      String orcid = orcid();
      long first = login(orcid, t -> t.claim("name", "Ada")).sessionUser();
      jdbc.update("update app_user set email = ?, email_verified = now() where id = ?",
          SignInLinkIT.address("ada"), first);

      Login second = login(orcid, t -> t.claim("name", "Ada"));
      assertThat(second.sessionUser()).isEqualTo(first);
      assertThat(jdbc.queryForObject("select count(*) from app_user where orcid = ?", Long.class, orcid))
          .isEqualTo(1);
      // the account has an address now: straight to the start page
      assertThat(second.redirect()).isEqualTo("http://localhost:5173/");
    }

    @Autowired org.springframework.security.web.FilterChainProxy filters;

    @Test
    void authorizedClientsAreNotKept() {
      // Garrulus never calls ORCID after the login: the login filter keeps no authorized client (and no tokens)
      var login = filters.getFilterChains().stream().flatMap(c -> c.getFilters().stream())
          .filter(f -> f instanceof org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter)
          .findFirst().orElseThrow();
      Object repo = org.springframework.test.util.ReflectionTestUtils.getField(login, "authorizedClientRepository");
      assertThat(repo).isInstanceOf(NoOpAuthorizedClientRepository.class);
    }

    @Test
    void orcidOnOffersOauthEndpoints() throws Exception {
      Browser b = new Browser(port);
      assertThat(b.get("/api/auth/config").body()).contains("\"orcid\":true");

      HttpResponse<String> start = b.get("/oauth2/authorization/orcid");
      assertThat(start.statusCode()).isEqualTo(302);
      String location = start.headers().firstValue("Location").orElseThrow();
      assertThat(location).startsWith("https://orcid.example/oauth/authorize?")
          .contains("client_id=APP-TEST").contains("scope=openid")
          .contains("redirect_uri=http://localhost:" + port + "/login/oauth2/code/orcid");
      assertThat(b.get("/oauth2/authorization/orcid?prompt=login").headers().firstValue("Location").orElseThrow())
          .contains("prompt=login");

      // a callback that does not belong to a started login goes back to the SPA's sign-in page
      HttpResponse<String> bad = b.get("/login/oauth2/code/orcid?code=x&state=y");
      assertThat(bad.statusCode()).isEqualTo(302);
      assertThat(bad.headers().firstValue("Location")).contains("http://localhost:5173/signin?error=orcid");

      // no generated login page
      HttpResponse<String> page = b.get("/login");
      assertThat(page.statusCode()).isIn(401, 404);
      assertThat(page.body()).doesNotContainIgnoringCase("<form").doesNotContainIgnoringCase("orcid");
    }
  }
}
