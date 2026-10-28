package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

/**
 * Drives the real DaoAuthenticationProvider built by ApplicationConfig, with a
 * mocked repository and real BCrypt.
 */
class ApplicationConfigTest {

  private static final String PASSWORD = "Str0ng!Pass";

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ApplicationConfig config = new ApplicationConfig(userRepository, mock(ApplicationAuditAware.class));
  private final PasswordEncoder encoder = config.passwordEncoder();

  private User user(String email, EUserStatus status) {
    User user = User.builder().email(email).password(encoder.encode(PASSWORD)).status(status).build();
    user.setId(UUID.randomUUID());
    when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    return user;
  }

  private Authentication authenticate(String email, String password) {
    AuthenticationProvider provider = config.authenticationProvider();
    return provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
  }

  @Test
  void wrongPasswordOnAPendingAccountLooksLikeAnyBadLogin() {
    user("pending@example.com", EUserStatus.PENDING);

    assertThatThrownBy(() -> authenticate("pending@example.com", "Wr0ng!Pass"))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void unknownEmailLooksLikeAWrongPassword() {
    when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authenticate("nobody@example.com", PASSWORD))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void deletedAccountLooksLikeAWrongPasswordEvenWithTheRightPassword() {
    user("deleted@example.com", EUserStatus.DELETED);

    assertThatThrownBy(() -> authenticate("deleted@example.com", PASSWORD))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void correctPasswordOnAPendingAccountReportsNotActiveWithoutTheEmail() {
    user("pending@example.com", EUserStatus.PENDING);

    assertThatThrownBy(() -> authenticate("pending@example.com", PASSWORD))
        .isInstanceOf(UserNotActiveException.class)
        .hasMessage("Account is not active");
  }

  @Test
  void activeAccountAuthenticates() {
    User user = user("active@example.com", EUserStatus.ACTIVE);

    assertThat(authenticate("active@example.com", PASSWORD).getPrincipal()).isSameAs(user);
  }

  @Test
  void looksUsersUpByTheNormalizedEmail() {
    user("user@example.com", EUserStatus.ACTIVE);

    assertThat(config.userDetailsService().loadUserByUsername("  User@Example.COM ").getUsername())
        .isEqualTo("user@example.com");
  }

  /**
   * The lookup itself never throws UserNotActiveException (the status check is a
   * post-authentication check), so Spring Security cannot wrap it in an
   * InternalAuthenticationServiceException: GlobalExceptionHandler needs no
   * wrapped-cause branch for it.
   */
  @Test
  void lookupLoadsEveryNonDeletedStatusSoNotActiveIsNeverWrapped() {
    user("pending@example.com", EUserStatus.PENDING);
    user("inactive@example.com", EUserStatus.INACTIVE);

    assertThat(config.userDetailsService().loadUserByUsername("pending@example.com")).isNotNull();
    assertThat(config.userDetailsService().loadUserByUsername("inactive@example.com")).isNotNull();
    assertThatThrownBy(() -> authenticate("pending@example.com", PASSWORD))
        .isExactlyInstanceOf(UserNotActiveException.class);
  }
}
