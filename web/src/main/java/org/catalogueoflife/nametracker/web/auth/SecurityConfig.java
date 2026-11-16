package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ott.JdbcOneTimeTokenService;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Security of web: public reads stay open, {@code /api/me/**} needs a session (401 JSON, never a redirect), the admin
 * endpoints keep their header token ({@code AdminAuth}). Sessions live in Postgres (Spring Session JDBC). Sign-in is
 * by email link through Spring's one-time-token login: {@code POST /api/auth/link} (SignInLinkController) generates
 * and mails the token, {@code POST /login/ott} with the form parameter {@code token} signs in. Spring's own token
 * generation endpoint and its default pages are switched off.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /** Shared by the filter chain and {@link CurrentUser#signIn}, so a swapped authentication lands in the session. */
  @Bean
  SecurityContextRepository securityContextRepository() {
    return new DelegatingSecurityContextRepository(
        new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
  }

  /** Tokens in {@code one_time_tokens} (V7); expired ones are deleted hourly by the service itself. */
  @Bean
  JdbcOneTimeTokenService oneTimeTokenService(JdbcTemplate jdbc, Clock clock) {
    JdbcOneTimeTokenService service = new JdbcOneTimeTokenService(jdbc);
    service.setClock(clock);
    return service;
  }

  /**
   * The token proves that its holder reads the address, so any address is a user here; the account is found or
   * created afterwards ({@link EmailLinkLoginHandler}). Deliberately not a bean, so that no other authentication
   * mechanism can ever pick it up.
   */
  static UserDetailsService emailLinkUsers() {
    return email -> User.withUsername(AccountService.normaliseEmail(email)).password("").authorities(List.of()).build();
  }

  /** 401 with the {status, error} body of the public API; never a redirect to a login page. */
  static final AuthenticationEntryPoint JSON_401 = (request, response, e) -> EmailLinkLoginHandler.write(
      response, HttpServletResponse.SC_UNAUTHORIZED, "{\"status\":401,\"error\":\"not signed in\"}");

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, JdbcOneTimeTokenService tokens,
      SecurityContextRepository contexts, EmailLinkLoginHandler emailLink,
      @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) throws Exception {
    CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfTokens.setCookieCustomizer(c -> c.secure(secureCookies).sameSite("Lax"));

    http
        .securityContext(c -> c.securityContextRepository(contexts))
        // no saved requests: a signed-out GET /api/me must not open a session
        .requestCache(c -> c.requestCache(new NullRequestCache()))
        .authorizeHttpRequests(auth -> auth
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            .requestMatchers("/api/me", "/api/me/**").authenticated()
            // AdminAuth checks the X-Admin-Token header in the controllers
            .requestMatchers("/api/admin/**").permitAll()
            .requestMatchers("/api/auth/**", "/api/unsubscribe", "/login/**", "/oauth2/**", "/feeds/**",
                "/actuator/health", "/api/openapi.yaml", "/error").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
            // CORS preflights of the public API (CorsConfig answers them)
            .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
            .anyRequest().authenticated())
        .csrf(csrf -> csrf
            .csrfTokenRepository(csrfTokens)
            .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
            // admin: header token, no cookie; unsubscribe: signed token; link: no session yet, rate limited
            .ignoringRequestMatchers("/api/admin/**", "/api/unsubscribe", "/api/auth/link"))
        .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
        .oneTimeTokenLogin(ott -> ott
            .tokenService(tokens)
            .authenticationProvider(new OneTimeTokenAuthenticationProvider(tokens, emailLinkUsers()))
            .authenticationSuccessHandler(emailLink)
            .authenticationFailureHandler(emailLink)
            // Spring's POST /ott/generate never resolves a request (so it falls through to a 401/404), and
            // its handler would refuse anyway: tokens are made by SignInLinkController only
            .generateRequestResolver(request -> null)
            .tokenGenerationSuccessHandler((request, response, token) ->
                response.sendError(HttpStatus.NOT_FOUND.value()))
            .showDefaultSubmitPage(false))
        .logout(logout -> logout
            .logoutUrl("/api/auth/logout")
            .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .exceptionHandling(ex -> ex.authenticationEntryPoint(JSON_401));
    return http.build();
  }
}
