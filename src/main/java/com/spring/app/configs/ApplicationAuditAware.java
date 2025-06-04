package com.spring.app.configs;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.AuditorAware;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.spring.app.modules.auth.entities.User;

@Component
public class ApplicationAuditAware implements AuditorAware<UUID> {

  @Override
  @NonNull
  public Optional<UUID> getCurrentAuditor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null ||
        !authentication.isAuthenticated() ||
        authentication instanceof AnonymousAuthenticationToken) {
      return Optional.empty();
    }

    Object principal = authentication.getPrincipal();
    if (principal instanceof User userPrincipal && userPrincipal.getId() != null) {
      return Optional.of(userPrincipal.getId());
    }

    return Optional.empty();
  }
}