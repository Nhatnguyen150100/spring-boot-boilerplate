package com.spring.app.configs;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import com.spring.app.modules.auth.entities.User;

/**
 * Supplies the value JPA auditing writes to {@code created_by} /
 * {@code updated_by}.
 *
 * <p>
 * Resolution order:
 * </p>
 * <ol>
 * <li>no authentication, or an anonymous one: {@code SYSTEM}</li>
 * <li>the app {@link User} entity (what {@code JwtAuthenticatorFilter} puts in
 * the security context): its id</li>
 * <li>an OAuth2 principal carrying the app user id in its {@code id}
 * attribute (set by {@code CustomOAuth2UserService}): that id</li>
 * <li>any other principal: {@link Authentication#getName()}</li>
 * </ol>
 *
 * <p>
 * Never casts the principal to a fixed type and never queries the database:
 * this runs inside Hibernate's flush, where a query would re-enter the session
 * being flushed.
 * </p>
 */
@Component
public class ApplicationAuditAware implements AuditorAware<String> {

  private static final String SYSTEM_AUDITOR = "SYSTEM";
  private static final String OAUTH2_USER_ID_ATTRIBUTE = "id";

  @Override
  @NonNull
  public Optional<String> getCurrentAuditor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return Optional.of(SYSTEM_AUDITOR);
    }

    Object principal = authentication.getPrincipal();

    if (principal instanceof User user && user.getId() != null) {
      return Optional.of(user.getId().toString());
    }

    if (principal instanceof OAuth2AuthenticatedPrincipal oauth2Principal) {
      Object userId = oauth2Principal.getAttribute(OAUTH2_USER_ID_ATTRIBUTE);
      if (userId != null) {
        return Optional.of(userId.toString());
      }
    }

    String name = authentication.getName();
    return Optional.of(name == null || name.isBlank() ? SYSTEM_AUDITOR : name);
  }
}
