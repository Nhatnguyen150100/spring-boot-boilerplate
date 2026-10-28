package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

@ExtendWith(OutputCaptureExtension.class)
class CustomOAuth2UserServiceTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ApplicationProperties applicationProperties = new ApplicationProperties();
  private final CustomOAuth2UserService service =
      new CustomOAuth2UserService(userRepository, new BCryptPasswordEncoder(4), applicationProperties);

  @BeforeEach
  void setUp() {
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
      User user = invocation.getArgument(0);
      user.setId(UUID.randomUUID());
      return user;
    });
  }

  /** Google-like attributes with no "picture": exercises the null-avatar path. */
  private static Map<String, Object> google(String email, Object emailVerified) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("sub", "1234567890");
    attributes.put("email", email);
    if (emailVerified != null) {
      attributes.put("email_verified", emailVerified);
    }
    attributes.put("name", "Nguyễn Văn A");
    return attributes;
  }

  private static String errorCode(Throwable thrown) {
    return ((OAuth2AuthenticationException) thrown).getError().getErrorCode();
  }

  private User existing(String email, EUserStatus status) {
    User user = User.builder().email(email).password("x").status(status).build();
    user.setId(UUID.randomUUID());
    when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    return user;
  }

  @Test
  void createsAnActiveUserForANewVerifiedEmailWithoutAnAvatar() {
    when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

    User user = service.resolveUser(google("New@Example.com", true));

    assertThat(user.getEmail()).isEqualTo("new@example.com");
    assertThat(user.getStatus()).isEqualTo(EUserStatus.ACTIVE);
    assertThat(user.getAvatarUrl()).isNull();
    OAuth2User principal = service.toPrincipal(user);
    assertThat(principal.<String>getAttribute("email")).isEqualTo("new@example.com");
    assertThat(principal.getAttributes()).doesNotContainKey("avatarUrl");
  }

  @Test
  void refusesAnUnverifiedEmailBeforeTouchingAnyAccount() {
    assertThatThrownBy(() -> service.resolveUser(google("victim@example.com", false)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("email_not_verified"));
    verifyNoInteractions(userRepository);
  }

  @Test
  void treatsAMissingEmailVerifiedClaimAsUnverified() {
    assertThatThrownBy(() -> service.resolveUser(google("victim@example.com", null)))
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("email_not_verified"));
  }

  @Test
  void acceptsEmailVerifiedSentAsAString() {
    existing("user@example.com", EUserStatus.ACTIVE);

    assertThat(service.resolveUser(google("user@example.com", "true")).getEmail()).isEqualTo("user@example.com");
  }

  @Test
  void linksAnExistingActiveAccount() {
    User user = existing("user@example.com", EUserStatus.ACTIVE);

    assertThat(service.resolveUser(google("user@example.com", true))).isSameAs(user);
    verify(userRepository, never()).save(any());
  }

  @Test
  void refusesAPendingAccountSoGoogleCannotBypassTheOtp() {
    existing("pending@example.com", EUserStatus.PENDING);

    assertThatThrownBy(() -> service.resolveUser(google("pending@example.com", true)))
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("account_not_active"));
  }

  @Test
  void refusesADisallowedDomain() {
    applicationProperties.setOauth2AllowedEmailDomains(List.of("solashi.com"));

    assertThatThrownBy(() -> service.resolveUser(google("user@example.com", true)))
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("email_domain_not_allowed"));
  }

  @Test
  void wrapsUnexpectedFailuresSoTheFailureHandlerRuns() {
    when(userRepository.findByEmail("user@example.com")).thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatThrownBy(() -> service.resolveUser(google("user@example.com", true)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("server_error"));
  }

  @Test
  void unexpectedFailuresAreLoggedAtErrorByTypeOnly(CapturedOutput output) {
    when(userRepository.findByEmail("user@example.com")).thenThrow(new DataAccessResourceFailureException(
        "db down for user@example.com", new java.sql.SQLException("user@example.com")));

    assertThatThrownBy(() -> service.resolveUser(google("user@example.com", true)))
        .isInstanceOf(OAuth2AuthenticationException.class);

    assertThat(output.getAll().lines().filter(line -> line.contains("ERROR")))
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line)
            .contains("DataAccessResourceFailureException")
            .contains("SQLException")
            .doesNotContain("user@example.com"));
  }

  @Test
  void theOidcPrincipalCarriesTheAppUserIdForTheAuditor() {
    when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
    Map<String, Object> claims = google("New@Example.com", true);
    OidcIdToken idToken = new OidcIdToken("id.jwt", Instant.now(), Instant.now().plusSeconds(60), claims);
    OidcUser fromGoogle = new DefaultOidcUser(List.of(), idToken, new OidcUserInfo(claims), "sub");

    User user = service.resolveUser(fromGoogle.getAttributes());
    OidcUser principal = service.toOidcPrincipal(user, fromGoogle);

    assertThat(principal.<String>getAttribute("id")).isEqualTo(user.getId().toString());
    assertThat(principal.<String>getAttribute("role")).isEqualTo("USER");
    // ID-token claims take precedence in DefaultOidcUser, so the e-mail keeps
    // the provider's casing; AuthServiceImpl normalises it before the lookup.
    assertThat(principal.getName()).isEqualToIgnoringCase("new@example.com");
    assertThat(principal.getIdToken()).isSameAs(idToken);
    assertThat(AuthorityUtils.authorityListToSet(principal.getAuthorities()))
        .isEqualTo(AuthorityUtils.authorityListToSet(user.getAuthorities()));
  }
}
