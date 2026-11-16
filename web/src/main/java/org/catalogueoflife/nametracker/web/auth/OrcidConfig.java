package org.catalogueoflife.nametracker.web.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * The ORCID OIDC client, only when {@code nametracker.orcid.client-id} ({@code ORCID_CLIENT_ID}) is set. Without it
 * there is no {@link ClientRegistrationRepository}, {@link SecurityConfig} does not enable the OAuth2 login, and
 * {@code /oauth2/authorization/orcid} is a 404. Scope {@code openid} only: ORCID's ID token carries the iD
 * ({@code sub}) and the public name, never an email address.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OrcidConfig.Configured.class)
public class OrcidConfig {

  static final String REGISTRATION_ID = "orcid";
  static final String CLIENT_ID = "nametracker.orcid.client-id";

  @Bean
  ClientRegistrationRepository orcidClientRegistrations(@Value("${" + CLIENT_ID + "}") String clientId,
      @Value("${nametracker.orcid.client-secret:}") String clientSecret,
      @Value("${nametracker.orcid.base-url:https://orcid.org}") String baseUrl) {
    return new InMemoryClientRegistrationRepository(registration(clientId, clientSecret, baseUrl));
  }

  static ClientRegistration registration(String clientId, String clientSecret, String baseUrl) {
    String base = baseUrl.strip().replaceAll("/+$", "");
    return ClientRegistration.withRegistrationId(REGISTRATION_ID)
        .clientName("ORCID")
        .clientId(clientId.strip())
        .clientSecret(clientSecret.strip())
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .scope("openid")
        .authorizationUri(base + "/oauth/authorize")
        .tokenUri(base + "/oauth/token")
        .userInfoUri(base + "/oauth/userinfo")
        .jwkSetUri(base + "/oauth/jwks")
        .issuerUri(base)
        .userNameAttributeName(IdTokenClaimNames.SUB)
        .build();
  }

  /** True when the ORCID client id is set and not blank. */
  static final class Configured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String id = context.getEnvironment().getProperty(CLIENT_ID);
      return id != null && !id.isBlank();
    }
  }
}
