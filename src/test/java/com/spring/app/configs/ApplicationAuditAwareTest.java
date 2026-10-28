package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.spring.app.modules.auth.entities.User;

class ApplicationAuditAwareTest {

  private static final UUID USER_ID = UUID.fromString("3b0a4f0e-6a43-4c2b-9d0e-0c5a3f7d1e21");

  private final ApplicationAuditAware auditAware = new ApplicationAuditAware();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noAuthenticationIsAttributedToSystem() {
    assertThat(auditAware.getCurrentAuditor()).contains("SYSTEM");
  }

  @Test
  void anonymousAuthenticationIsAttributedToSystem() {
    authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    assertThat(auditAware.getCurrentAuditor()).contains("SYSTEM");
  }

  @Test
  void jwtAuthenticatedAppUserIsAttributedToTheirId() {
    User user = User.builder().email("john@example.com").password("hash").build();
    user.setId(USER_ID);
    authenticate(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

    assertThat(auditAware.getCurrentAuditor()).contains(USER_ID.toString());
  }

  @Test
  void oauth2PrincipalIsAttributedToTheAppUserIdAttribute() {
    OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
        Map.of("id", USER_ID, "email", "jane@example.com"), "email");
    authenticate(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));

    assertThat(auditAware.getCurrentAuditor()).contains(USER_ID.toString());
  }

  @Test
  void oauth2PrincipalWithoutIdFallsBackToItsName() {
    OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
        Map.of("email", "jane@example.com"), "email");
    authenticate(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));

    assertThat(auditAware.getCurrentAuditor()).contains("jane@example.com");
  }

  @Test
  void anyOtherPrincipalFallsBackToTheAuthenticationName() {
    authenticate(new UsernamePasswordAuthenticationToken("ops-script", null,
        AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

    assertThat(auditAware.getCurrentAuditor()).contains("ops-script");
  }

  private static void authenticate(Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
