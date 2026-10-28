package com.spring.app.modules.upload.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.spring.app.modules.auth.entities.User;

class SecurityContextUploadOwnerProviderTest {

  private final SecurityContextUploadOwnerProvider provider = new SecurityContextUploadOwnerProvider();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void returnsTheIdOfTheAuthenticatedUser() {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    User user = User.builder().email("a@example.com").password("x").build();
    user.setId(id);
    authenticate(new UsernamePasswordAuthenticationToken(user, null, List.of()));

    assertThat(provider.currentOwnerId()).isEqualTo(id.toString());
  }

  @Test
  void rejectsMissingAuthentication() {
    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsAnonymousCaller() {
    authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
      AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsPrincipalThatIsNotAnApplicationUser() {
    authenticate(new UsernamePasswordAuthenticationToken("someone@example.com", null, List.of()));

    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void rejectsUserWithoutId() {
    User user = User.builder().email("a@example.com").password("x").build();
    authenticate(new UsernamePasswordAuthenticationToken(user, null, List.of()));

    assertThatThrownBy(provider::currentOwnerId).isInstanceOf(AccessDeniedException.class);
  }

  private static void authenticate(Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
