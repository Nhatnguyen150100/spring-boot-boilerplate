package com.spring.app.modules.upload.services.impl;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.upload.services.UploadOwnerProvider;

/**
 * Takes the owner from the JWT-authenticated principal, which
 * {@code JwtAuthenticatorFilter} sets to the {@link User} entity. The session
 * policy is STATELESS, so file endpoints never see an OAuth2 session principal.
 */
@Component
public class SecurityContextUploadOwnerProvider implements UploadOwnerProvider {

  @Override
  public String currentOwnerId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
      && authentication.getPrincipal() instanceof User user
      && user.getId() != null) {
      return user.getId().toString();
    }
    throw new AccessDeniedException("Uploads require an authenticated user");
  }
}
