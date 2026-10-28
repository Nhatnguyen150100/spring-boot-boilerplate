package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.JwtService;
import com.spring.app.utils.JwtFunctionUtil;

/**
 * Bearer authentication wired to the REAL ApplicationConfig#userDetailsService()
 * (backed by a mocked repository), so what the filter sees for PENDING, INACTIVE
 * and DELETED accounts is what production sees. Lives in this package because
 * userDetailsService() is package-private.
 */
class BearerAuthenticationUserDetailsTest {

  private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  private final UserRepository userRepository = mock(UserRepository.class);
  private final JwtService jwtService = new JwtService(properties());
  private final JwtFunctionUtil jwtFunction = new JwtFunctionUtil(
      jwtService,
      new ApplicationConfig(userRepository, mock(ApplicationAuditAware.class)).userDetailsService(),
      mock(RedisServiceInterface.class));
  private final MockHttpServletRequest request = new MockHttpServletRequest();

  private static JwtProperties properties() {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(SECRET);
    return properties;
  }

  private String accessTokenFor(EUserStatus status) {
    User user = User.builder().email("user@example.com").password("irrelevant").status(status).build();
    user.setId(UUID.randomUUID());
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    return jwtService.generateToken(user);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void activeUserIsAuthenticated() {
    jwtFunction.authenticateAccessToken(accessTokenFor(EUserStatus.ACTIVE), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @Test
  void pendingUserStaysAnonymousWithoutThrowing() {
    jwtFunction.authenticateAccessToken(accessTokenFor(EUserStatus.PENDING), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void inactiveUserStaysAnonymousWithoutThrowing() {
    jwtFunction.authenticateAccessToken(accessTokenFor(EUserStatus.INACTIVE), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void deletedUserStaysAnonymousWithoutThrowing() {
    jwtFunction.authenticateAccessToken(accessTokenFor(EUserStatus.DELETED), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
