package com.spring.app.modules.auth.services.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.mapper.AuthMapperImpl;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.AuthCacheService;
import com.spring.app.shared.services.JwtService;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.shared.services.RateLimitManagerService;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Plain Mockito: no Spring context. {@code @InjectMocks} picks the Lombok
 * constructor and fills every parameter from the {@code @Mock}/{@code @Spy}
 * fields by type, so adding a constructor dependency only needs a new field
 * here. Real collaborators (JWT, mapper, BCrypt, metrics) are {@code @Spy}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

  static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Spy JwtProperties jwtProperties = jwtProperties();
  @Spy JwtService jwtService = new JwtService(jwtProperties);
  @Spy MonitoringService monitoringService = new MonitoringService(meterRegistry);
  @Spy AuthMapper authMapper = new AuthMapperImpl();
  @Spy PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

  @Mock UserRepository userRepository;
  @Mock RefreshTokenRepository refreshTokenRepository;
  @Mock AuthenticationManager authenticationManager;
  @Mock JwtFunctionUtil jwtFunction;
  @Mock OtpFunctionUtil otpFunction;
  @Mock OtpEmailService otpEmailService;
  @Mock AuthCacheService authCacheService;
  @Mock RedisServiceInterface redisService;
  @Mock RateLimitManagerService rateLimitManagerService;

  @InjectMocks AuthServiceImpl authService;

  static JwtProperties jwtProperties() {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(SECRET);
    return properties;
  }

  static User user(String email, EUserStatus status) {
    User user = User.builder()
        .email(email)
        .password("$2a$04$irrelevantirrelevantirrelevantirrelevantirrelevantirr")
        .fullName("Test User")
        .status(status)
        .build();
    user.setId(UUID.randomUUID());
    return user;
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  // ---- Task 2: token types --------------------------------------------------

  @Test
  void refreshRejectsAnAccessToken() {
    String accessToken = jwtService.generateToken(user("active@example.com", EUserStatus.ACTIVE));

    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(accessToken)))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("Invalid or expired refresh token");
    verifyNoInteractions(refreshTokenRepository);
  }

  @Test
  void refreshRejectsAGarbledToken() {
    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto("not-a-jwt")))
        .isInstanceOf(InvalidTokenException.class);
    verifyNoInteractions(refreshTokenRepository);
  }

  @Test
  void logoutWithoutABearerTokenIsUnauthorizedNotAServerError() {
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(null);

    assertThatThrownBy(() -> authService.logout(new MockHttpServletRequest()))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void logoutWithAGarbledTokenIsUnauthorizedNotAServerError() {
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn("not-a-jwt");

    assertThatThrownBy(() -> authService.logout(new MockHttpServletRequest()))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("Invalid or expired token");
  }

  @Test
  void logoutRejectsARefreshToken() {
    String refreshToken = jwtService.generateRefreshToken(user("active@example.com", EUserStatus.ACTIVE));
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(refreshToken);

    assertThatThrownBy(() -> authService.logout(new MockHttpServletRequest()))
        .isInstanceOf(InvalidTokenException.class);
    verifyNoInteractions(redisService);
  }

  @Test
  void logoutBlacklistsTheAccessTokenAndRevokesRefreshTokens() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateToken(user);
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(token);
    when(userRepository.findByEmail("active@example.com")).thenReturn(Optional.of(user));

    authService.logout(new MockHttpServletRequest());

    verify(redisService).blacklistToken(eq(token), longThat(seconds -> seconds > 0));
    verify(refreshTokenRepository).revokeAllByUser(user);
  }
}
