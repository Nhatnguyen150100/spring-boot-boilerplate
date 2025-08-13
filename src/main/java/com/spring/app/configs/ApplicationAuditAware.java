package com.spring.app.configs;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.spring.app.modules.auth.entities.User;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ApplicationAuditAware implements AuditorAware<String> {

  private static final String SYSTEM_AUDITOR = "SYSTEM";

  @Override
  @NonNull
  public Optional<String> getCurrentAuditor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null ||
        !authentication.isAuthenticated() ||
        authentication instanceof AnonymousAuthenticationToken) {
      log.warn("No authenticated user found, returning default auditor.");
      return Optional.of(SYSTEM_AUDITOR);
    }

    Object principal = authentication.getPrincipal();
    if (principal instanceof User userDetails && userDetails.getId() != null) {
      return Optional.of(userDetails.getId().toString());
    }

    log.warn("Principal is not a valid User or ID is null, returning default auditor.");
    return Optional.of(SYSTEM_AUDITOR);
  }
}