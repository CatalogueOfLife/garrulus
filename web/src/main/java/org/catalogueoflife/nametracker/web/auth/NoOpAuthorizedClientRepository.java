package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Keeps no authorized clients. Garrulus never calls ORCID after the login (the ID token gives the iD and the name), so
 * the access token is not needed; Spring's default in-memory service would keep an entry per ORCID user for the
 * lifetime of the process.
 */
final class NoOpAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

  @Override
  public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(String clientRegistrationId,
      Authentication principal, HttpServletRequest request) {
    return null;
  }

  @Override
  public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
      HttpServletRequest request, HttpServletResponse response) {
  }

  @Override
  public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
      HttpServletRequest request, HttpServletResponse response) {
  }
}
