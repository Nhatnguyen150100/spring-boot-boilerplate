package com.spring.app.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.services.TokenRevocationService;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.JwtService;

import io.jsonwebtoken.JwtException;

class JwtFunctionUtilTest {

  private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  private final JwtService jwtService = new JwtService(properties());
  private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
  private final RedisServiceInterface redisService = mock(RedisServiceInterface.class);
  private final StringRedisTemplate stringRedis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> redisValues = mock(ValueOperations.class);
  private final JwtFunctionUtil jwtFunction = new JwtFunctionUtil(jwtService, userDetailsService, redisService,
      new TokenRevocationService(stringRedis, properties()));
  private final MockHttpServletRequest request = new MockHttpServletRequest();

  private static JwtProperties properties() {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(SECRET);
    return properties;
  }

  private static User user(EUserStatus status) {
    User user = User.builder().email("user@example.com").password("irrelevant").status(status).build();
    user.setId(UUID.randomUUID());
    return user;
  }

  @BeforeEach
  void setUp() {
    when(stringRedis.opsForValue()).thenReturn(redisValues);
  }

  /** What a password reset at {@code epochSecond} leaves in Redis for the user. */
  private void givenPasswordResetAt(User user, long epochSecond) {
    when(redisValues.get("auth:tokens-not-before:" + user.getId())).thenReturn(String.valueOf(epochSecond));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void activeUserWithAnAccessTokenIsAuthenticated() {
    User user = user(EUserStatus.ACTIVE);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);

    jwtFunction.authenticateAccessToken(jwtService.generateToken(user), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
  }

  @Test
  void refreshTokenDoesNotAuthenticate() {
    jwtFunction.authenticateAccessToken(jwtService.generateRefreshToken(user(EUserStatus.ACTIVE)), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(userDetailsService);
  }

  @Test
  void blacklistedTokenDoesNotAuthenticate() {
    User user = user(EUserStatus.ACTIVE);
    String token = jwtService.generateToken(user);
    when(redisService.isTokenBlacklisted(token)).thenReturn(true);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);

    jwtFunction.authenticateAccessToken(token, request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void nonActiveUserDoesNotAuthenticate() {
    User user = user(EUserStatus.INACTIVE);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);

    jwtFunction.authenticateAccessToken(jwtService.generateToken(user), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void redisFailureDuringTheBlacklistCheckFailsClosed() {
    User user = user(EUserStatus.ACTIVE);
    String token = jwtService.generateToken(user);
    when(redisService.isTokenBlacklisted(token)).thenThrow(new RedisConnectionFailureException("redis down"));

    jwtFunction.authenticateAccessToken(token, request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(userDetailsService);
  }

  @Test
  void malformedTokenThrowsForTheFilterToHandle() {
    assertThatThrownBy(() -> jwtFunction.authenticateAccessToken("not-a-jwt", request))
        .isInstanceOf(JwtException.class);
  }

  @Test
  void aTokenIssuedBeforeAPasswordResetDoesNotAuthenticate() {
    User user = user(EUserStatus.ACTIVE);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);
    String token = jwtService.generateToken(user);
    givenPasswordResetAt(user, Instant.now().getEpochSecond() + 1);

    jwtFunction.authenticateAccessToken(token, request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void aTokenIssuedAfterAPasswordResetAuthenticates() {
    User user = user(EUserStatus.ACTIVE);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);
    givenPasswordResetAt(user, Instant.now().getEpochSecond() - 5);

    jwtFunction.authenticateAccessToken(jwtService.generateToken(user), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @Test
  void redisFailureDuringTheResetCheckFailsClosed() {
    User user = user(EUserStatus.ACTIVE);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);
    when(redisValues.get("auth:tokens-not-before:" + user.getId()))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    jwtFunction.authenticateAccessToken(jwtService.generateToken(user), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
