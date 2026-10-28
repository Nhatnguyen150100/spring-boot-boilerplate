package com.spring.app.modules.auth.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ConflictException;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.auth.dto.response.LoginResponseDto;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.mapper.AuthMapperImpl;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.LoginAttemptService;
import com.spring.app.modules.auth.services.TokenRevocationService;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.AuthCacheService;
import com.spring.app.shared.services.JwtService;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.shared.services.RateLimitManagerService;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.micrometer.core.instrument.Counter;
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

  static final String CLIENT_IP = "203.0.113.7";
  static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Spy AuthProperties authProperties = new AuthProperties();
  @Spy MockEnvironment environment = new MockEnvironment();
  @Spy JwtProperties jwtProperties = jwtProperties();
  @Spy JwtService jwtService = new JwtService(jwtProperties);
  @Spy MonitoringService monitoringService = new MonitoringService(meterRegistry);
  @Spy AuthMapper authMapper = new AuthMapperImpl();
  @Spy PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
  final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
  @Spy TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

  @Mock UserRepository userRepository;
  @Mock RefreshTokenRepository refreshTokenRepository;
  @Mock AuthenticationManager authenticationManager;
  @Mock JwtFunctionUtil jwtFunction;
  @Mock OtpFunctionUtil otpFunction;
  @Mock OtpEmailService otpEmailService;
  @Mock AuthCacheService authCacheService;
  @Mock RedisServiceInterface redisService;
  @Mock RateLimitManagerService rateLimitManagerService;
  @Mock LoginAttemptService loginAttemptService;
  @Mock TokenRevocationService tokenRevocationService;

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

  private void givenUser(User user) {
    when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
  }

  /** What the database holds for a refresh JWT: its SHA-256, never the JWT itself. */
  static String sha256Hex(String value) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  /** 254 characters, the longest address RFC 5321 allows (64-char local part). */
  static String maxLengthEmail() {
    return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".com";
  }

  private RefreshToken givenStoredRefreshToken(String token, User owner) throws Exception {
    String hash = sha256Hex(token);
    RefreshToken stored = RefreshToken.builder()
        .token(hash)
        .user(owner)
        .expiryDate(Instant.now().plusSeconds(3600))
        .build();
    when(refreshTokenRepository.findByToken(hash)).thenReturn(Optional.of(stored));
    when(refreshTokenRepository.revokeIfActive(hash)).thenReturn(1);
    return stored;
  }

  static RegisterRequestDto registerDto(String email) {
    return new RegisterRequestDto(email, "Str0ng!Pass", "New User", "+84 912 345 678", "1 Main St");
  }

  private User savedUser() {
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).saveAndFlush(captor.capture());
    return captor.getValue();
  }

  private ResponseEntity<?> login(String email, String password) {
    return authService.login(new LoginRequestDto(email, password), CLIENT_IP);
  }

  private void givenAuthenticationSucceedsFor(User user) {
    when(authenticationManager.authenticate(any()))
        .thenReturn(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
  }

  private static Object data(ResponseEntity<?> response) {
    return ((BaseResponse<?>) response.getBody()).getData();
  }

  private double count(String name, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
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

  // ---- Task 3: status checks and enumeration -----------------------------------

  @Test
  void registerRejectsAnEmailHeldByADeletedAccount() {
    when(userRepository.existsByEmail("gone@example.com")).thenReturn(true);

    assertThatThrownBy(() -> authService.register(registerDto("gone@example.com")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("This email cannot be registered");
    verify(userRepository, never()).saveAndFlush(any());
    verifyNoInteractions(otpEmailService);
  }

  @Test
  void registerTurnsAConcurrentDuplicateIntoAConflictAndSendsNoOtp() {
    when(userRepository.saveAndFlush(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("uk_users_email"));

    assertThatThrownBy(() -> authService.register(registerDto("race@example.com")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("This email cannot be registered");
    verifyNoInteractions(otpEmailService);
  }

  @Test
  void registerSendsTheOtpAfterTheUserIsSaved() {
    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.PENDING);
    verify(otpEmailService).sendOtpEmailAsync("new@example.com");
  }

  @Test
  void resendOtpAnswersIdenticallyWhateverTheAccountState() {
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    givenUser(user("pending@example.com", EUserStatus.PENDING));

    Object unknown = data(authService.resendOtp("nobody@example.com"));
    Object active = data(authService.resendOtp("active@example.com"));
    Object pending = data(authService.resendOtp("pending@example.com"));

    assertThat(unknown).isEqualTo(active).isEqualTo(pending);
    verify(otpEmailService).sendOtpEmailAsync("pending@example.com");
    verify(otpEmailService, never()).sendOtpEmailAsync("active@example.com");
    verify(otpEmailService, never()).sendOtpEmailAsync("nobody@example.com");
  }

  @Test
  void forgotPasswordAnswersIdenticallyAndOnlyMailsActiveAccounts() {
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    givenUser(user("pending@example.com", EUserStatus.PENDING));

    Object unknown = data(authService.forgotPassword("nobody@example.com"));
    Object active = data(authService.forgotPassword("active@example.com"));
    Object pending = data(authService.forgotPassword("pending@example.com"));

    assertThat(unknown).isEqualTo(active).isEqualTo(pending);
    verify(otpEmailService).sendOtpEmailAsync("active@example.com");
    verify(otpEmailService, never()).sendOtpEmailAsync("pending@example.com");
  }

  @Test
  void activationFailsIdenticallyForUnknownEmailWrongStatusAndWrongOtp() {
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    givenUser(user("pending@example.com", EUserStatus.PENDING));
    when(otpFunction.validateOtp("nobody@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("active@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("pending@example.com", "123456")).thenReturn(false);

    for (String email : List.of("nobody@example.com", "active@example.com", "pending@example.com")) {
      assertThatThrownBy(() -> authService.activeAccount(new ActiveAccountRequestDto(email, "123456")))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("Invalid or expired OTP");
    }
    verify(userRepository, never()).save(any());
  }

  @Test
  void activationActivatesAPendingAccountAndEvictsItsProfile() {
    User pending = user("pending@example.com", EUserStatus.PENDING);
    givenUser(pending);
    when(otpFunction.validateOtp("pending@example.com", "123456")).thenReturn(true);

    authService.activeAccount(new ActiveAccountRequestDto("pending@example.com", "123456"));

    assertThat(pending.getStatus()).isEqualTo(EUserStatus.ACTIVE);
    verify(userRepository).save(pending);
    verify(otpFunction).removeOtp("pending@example.com");
    verify(authCacheService).updateCachedUser(pending);
  }

  @Test
  void passwordResetFailsIdenticallyForUnknownNonActiveAndWrongOtp() {
    givenUser(user("pending@example.com", EUserStatus.PENDING));
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    when(otpFunction.validateOtp("nobody@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("pending@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("active@example.com", "123456")).thenReturn(false);

    for (String email : List.of("nobody@example.com", "pending@example.com", "active@example.com")) {
      assertThatThrownBy(() -> authService.resetPassword(
          new ResetPasswordRequestDto(email, "123456", "NewStr0ng!Pass")))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("Invalid or expired OTP");
    }
    verify(refreshTokenRepository, never()).revokeAllByUser(any());
    verifyNoInteractions(tokenRevocationService);
  }

  @ParameterizedTest
  @EnumSource(value = EUserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
  void refreshIsRefusedForANonActiveAccount(EUserStatus status) throws Exception {
    User owner = user("owner@example.com", status);
    String token = jwtService.generateRefreshToken(owner);
    givenStoredRefreshToken(token, owner);

    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(token)))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("Invalid or expired refresh token");
    verify(refreshTokenRepository, never()).revokeIfActive(anyString());
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void refreshIsRefusedForAnExpiredStoredToken() throws Exception {
    User active = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateRefreshToken(active);
    givenStoredRefreshToken(token, active).setExpiryDate(Instant.now().minusSeconds(1));

    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(token)))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("Invalid or expired refresh token");
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void refreshIsRefusedForARevokedOrUnknownToken() throws Exception {
    User active = user("active@example.com", EUserStatus.ACTIVE);
    String revoked = jwtService.generateRefreshToken(active);
    givenStoredRefreshToken(revoked, active).setRevoked(true);
    String unknown = jwtService.generateRefreshToken(active);

    for (String token : List.of(revoked, unknown)) {
      assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(token)))
          .isInstanceOf(InvalidTokenException.class)
          .hasMessage("Invalid or expired refresh token");
    }
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void loginGivesTheSameAnswerForUnknownEmailAndWrongPassword() {
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("Bad credentials"))
        .thenThrow(new UsernameNotFoundException("nobody"));

    assertThatThrownBy(() -> login("active@example.com", "Wr0ng!Pass"))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Invalid email or password");
    assertThatThrownBy(() -> login("nobody@example.com", "Wr0ng!Pass"))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Invalid email or password");
  }

  @Test
  void loginReportsANonActiveAccountOnlyWhenTheProviderDoes() {
    when(authenticationManager.authenticate(any())).thenThrow(new UserNotActiveException("Account is not active"));

    assertThatThrownBy(() -> login("pending@example.com", "Str0ng!Pass"))
        .isInstanceOf(UserNotActiveException.class)
        .hasMessage("Account is not active");
  }

  // ---- Task 4: metrics ---------------------------------------------------------

  @Test
  void successfulLoginIsCountedAsSuccess() {
    givenAuthenticationSucceedsFor(user("active@example.com", EUserStatus.ACTIVE));

    login("active@example.com", "Str0ng!Pass");

    assertThat(count("auth.login.success")).isEqualTo(1.0);
    assertThat(count("auth.login.failed")).isZero();
  }

  @Test
  void failedLoginsAreCountedWithTheirReason() {
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("x"))
        .thenThrow(new UserNotActiveException("Account is not active"));

    catchThrowable(() -> login("a@example.com", "Wr0ng!Pass"));
    catchThrowable(() -> login("a@example.com", "Str0ng!Pass"));

    assertThat(count("auth.login.failed", "reason", "bad_credentials")).isEqualTo(1.0);
    assertThat(count("auth.login.failed", "reason", "not_active")).isEqualTo(1.0);
    assertThat(count("auth.login.success")).isZero();
  }

  @Test
  void wrongOtpsAreCountedPerPurpose() {
    catchThrowable(() -> authService.activeAccount(new ActiveAccountRequestDto("nobody@example.com", "123456")));
    catchThrowable(() -> authService.resetPassword(
        new ResetPasswordRequestDto("nobody@example.com", "123456", "NewStr0ng!Pass")));

    assertThat(count("auth.otp.failed", "purpose", "activation")).isEqualTo(1.0);
    assertThat(count("auth.otp.failed", "purpose", "password_reset")).isEqualTo(1.0);
  }

  @Test
  void refreshOutcomesAreCounted() throws Exception {
    catchThrowable(() -> authService.refreshToken(new RefreshTokenDto("not-a-jwt")));
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateRefreshToken(user);
    givenStoredRefreshToken(token, user);

    authService.refreshToken(new RefreshTokenDto(token));

    assertThat(count("auth.token.refresh", "result", "failure")).isEqualTo(1.0);
    assertThat(count("auth.token.refresh", "result", "success")).isEqualTo(1.0);
  }

  @Test
  void logoutIsCounted() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateToken(user);
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(token);
    givenUser(user);

    authService.logout(new MockHttpServletRequest());

    assertThat(count("auth.logout")).isEqualTo(1.0);
  }

  @Test
  void perEmailThrottleRejectionIsCounted() {
    doThrow(new RateLimitExceededException("slow down"))
        .when(rateLimitManagerService).checkRateLimitAndThrow(any(), anyString());

    assertThatThrownBy(() -> authService.forgotPassword("user@example.com"))
        .isInstanceOf(RateLimitExceededException.class);
    assertThat(count("auth.ratelimit.rejected", "bucket", "auth_email")).isEqualTo(1.0);
  }

  // ---- Task 5: e-mail verification opt-in ---------------------------------------

  @Test
  void registrationRequiresEmailVerificationByDefaultEvenInDev() {
    environment.setActiveProfiles("dev");

    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.PENDING);
    verify(otpEmailService).sendOtpEmailAsync("new@example.com");
  }

  @Test
  void skippingVerificationNeedsTheFlagAndTheDevProfile() {
    authProperties.setSkipEmailVerification(true);
    environment.setActiveProfiles("dev");

    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.ACTIVE);
    verifyNoInteractions(otpEmailService);
  }

  @Test
  void theFlagIsIgnoredOutsideTheDevProfile() {
    authProperties.setSkipEmailVerification(true);
    environment.setActiveProfiles("prod");

    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.PENDING);
    verify(otpEmailService).sendOtpEmailAsync("new@example.com");
  }

  // ---- Task 6: normalisation and persisted fields --------------------------------

  @Test
  void registerStoresTheNormalizedEmailAndKeepsPhoneAndAddress() {
    authService.register(new RegisterRequestDto(
        "  New.User@Example.COM ", "Str0ng!Pass", "Nguyễn Văn A", "+84 912 345 678", "1 Main St"));

    User saved = savedUser();
    assertThat(saved.getEmail()).isEqualTo("new.user@example.com");
    assertThat(saved.getFullName()).isEqualTo("Nguyễn Văn A");
    assertThat(saved.getPhone()).isEqualTo("+84 912 345 678");
    assertThat(saved.getAddress()).isEqualTo("1 Main St");
    verify(userRepository).existsByEmail("new.user@example.com");
    verify(otpEmailService).sendOtpEmailAsync("new.user@example.com");
  }

  @Test
  void loginAuthenticatesWithTheNormalizedEmail() {
    givenAuthenticationSucceedsFor(user("user@example.com", EUserStatus.ACTIVE));

    login(" User@Example.COM ", "Str0ng!Pass");

    ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("user@example.com");
  }

  @Test
  void otpFlowsUseTheNormalizedEmail() {
    authService.resendOtp(" User@Example.COM ");
    authService.forgotPassword("USER@example.com");
    catchThrowable(() -> authService.activeAccount(new ActiveAccountRequestDto("User@Example.com", "123456")));
    catchThrowable(() -> authService.resetPassword(
        new ResetPasswordRequestDto("User@Example.com", "123456", "NewStr0ng!Pass")));

    verify(userRepository, times(4)).findByEmail("user@example.com");
    verify(otpFunction, times(2)).validateOtp("user@example.com", "123456");
  }

  @Test
  void perEmailThrottleKeyIsAHashNotTheAddress() {
    authService.forgotPassword("user@example.com");
    authService.forgotPassword("USER@Example.com");

    ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
    verify(rateLimitManagerService, times(2)).checkRateLimitAndThrow(eq(ERateLimitEndpoint.AUTH), ids.capture());
    assertThat(ids.getAllValues().get(0))
        .startsWith("email:")
        .doesNotContain("user@example.com")
        .isEqualTo(ids.getAllValues().get(1));
  }

  // ---- Task 7: transactions -------------------------------------------------------

  @Test
  void noAuthMethodHoldsATransactionAcrossRedisOrBcrypt() {
    assertThat(AuthServiceImpl.class.isAnnotationPresent(
        org.springframework.transaction.annotation.Transactional.class)).isFalse();
    for (Method method : AuthServiceImpl.class.getDeclaredMethods()) {
      assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
          .as(method.getName())
          .isFalse();
    }
  }

  @Test
  void refreshRotatesTheTokenOnce() throws Exception {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateRefreshToken(user);
    givenStoredRefreshToken(token, user);

    authService.refreshToken(new RefreshTokenDto(token));

    verify(refreshTokenRepository).revokeIfActive(sha256Hex(token));
    verify(refreshTokenRepository).save(any(RefreshToken.class));
    verify(transactionManager).commit(any());
  }

  @Test
  void refreshSignsBothTokensBeforeOpeningTheTransaction() throws Exception {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = new JwtService(jwtProperties()).generateRefreshToken(user);
    givenStoredRefreshToken(token, user);

    authService.refreshToken(new RefreshTokenDto(token));

    InOrder order = inOrder(jwtService, transactionManager, refreshTokenRepository);
    order.verify(jwtService).generateToken(user);
    order.verify(jwtService).generateRefreshToken(user);
    order.verify(transactionManager).getTransaction(any());
    order.verify(refreshTokenRepository).revokeIfActive(sha256Hex(token));
    order.verify(refreshTokenRepository).save(any(RefreshToken.class));
    order.verify(transactionManager).commit(any());
  }

  // ---- Final review: refresh tokens are stored hashed --------------------------------

  @Test
  void aMaximumLengthEmailRoundTripsLoginAndRefreshWithAHashedStoredToken() throws Exception {
    User user = user(maxLengthEmail(), EUserStatus.ACTIVE);
    assertThat(user.getEmail()).hasSize(254);
    givenAuthenticationSucceedsFor(user);

    String refreshJwt = ((LoginResponseDto) data(login(user.getEmail(), "Str0ng!Pass"))).refreshToken();

    ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokenRepository).save(saved.capture());
    RefreshToken stored = saved.getValue();
    assertThat(refreshJwt).as("the raw JWT would not fit refresh_tokens.token VARCHAR(512)").hasSizeGreaterThan(512);
    assertThat(stored.getToken()).hasSizeLessThanOrEqualTo(64).isEqualTo(sha256Hex(refreshJwt));

    when(refreshTokenRepository.findByToken(stored.getToken())).thenReturn(Optional.of(stored));
    when(refreshTokenRepository.revokeIfActive(stored.getToken())).thenReturn(1);

    TokenResponseDto rotated = (TokenResponseDto) data(authService.refreshToken(new RefreshTokenDto(refreshJwt)));

    assertThat(jwtService.isRefreshToken(rotated.refreshToken())).isTrue();
    verify(refreshTokenRepository, never()).findByToken(refreshJwt);
    verify(refreshTokenRepository, never()).revokeIfActive(refreshJwt);
  }

  @Test
  void refreshThatLosesTheRotationRaceIsRejected() throws Exception {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateRefreshToken(user);
    givenStoredRefreshToken(token, user);
    when(refreshTokenRepository.revokeIfActive(sha256Hex(token))).thenReturn(0);

    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(token)))
        .isInstanceOf(InvalidTokenException.class);
    verify(refreshTokenRepository, never()).save(any());
    verify(transactionManager).rollback(any());
  }

  @Test
  void passwordResetHashesBeforeOpeningTheTransaction() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    givenUser(user);
    when(otpFunction.validateOtp("active@example.com", "123456")).thenReturn(true);

    authService.resetPassword(new ResetPasswordRequestDto("active@example.com", "123456", "NewStr0ng!Pass"));

    InOrder order = inOrder(passwordEncoder, transactionManager, userRepository, refreshTokenRepository);
    order.verify(passwordEncoder).encode("NewStr0ng!Pass");
    order.verify(transactionManager).getTransaction(any());
    order.verify(userRepository).save(user);
    order.verify(refreshTokenRepository).revokeAllByUser(user);
    order.verify(transactionManager).commit(any());
  }

  @Test
  void passwordResetEndsLiveAccessTokensOnceCommitted() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    givenUser(user);
    when(otpFunction.validateOtp("active@example.com", "123456")).thenReturn(true);

    authService.resetPassword(new ResetPasswordRequestDto("active@example.com", "123456", "NewStr0ng!Pass"));

    InOrder order = inOrder(transactionManager, tokenRevocationService);
    order.verify(transactionManager).commit(any());
    order.verify(tokenRevocationService).revokeAccessTokens(user.getId());
  }

  // ---- Task 8: failure-only login lockout ------------------------------------------

  @Test
  void loginIsRefusedWhileTheEmailAndIpPairIsLocked() {
    when(loginAttemptService.isBlocked("user@example.com", CLIENT_IP)).thenReturn(true);

    assertThatThrownBy(() -> login("User@Example.com", "Str0ng!Pass"))
        .isInstanceOf(RateLimitExceededException.class);
    verifyNoInteractions(authenticationManager);
    assertThat(count("auth.login.failed", "reason", "locked")).isEqualTo(1.0);
    assertThat(count("auth.ratelimit.rejected", "bucket", "login")).isEqualTo(1.0);
  }

  @Test
  void aWrongPasswordIsRecordedForTheEmailAndIp() {
    when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("x"));

    catchThrowable(() -> login("user@example.com", "Wr0ng!Pass"));

    verify(loginAttemptService).recordFailure("user@example.com", CLIENT_IP);
  }

  @Test
  void aSuccessfulLoginIsNotCountedAndClearsEarlierFailures() {
    givenAuthenticationSucceedsFor(user("user@example.com", EUserStatus.ACTIVE));

    login("user@example.com", "Str0ng!Pass");

    verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
    verify(loginAttemptService).reset("user@example.com", CLIENT_IP);
  }

  @Test
  void aCorrectPasswordOnAnInactiveAccountIsNotAFailedGuess() {
    when(authenticationManager.authenticate(any())).thenThrow(new UserNotActiveException("Account is not active"));

    catchThrowable(() -> login("pending@example.com", "Str0ng!Pass"));

    verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
  }

  @Test
  void loginNoLongerConsumesThePerEmailBucket() {
    givenAuthenticationSucceedsFor(user("user@example.com", EUserStatus.ACTIVE));

    login("user@example.com", "Str0ng!Pass");

    verify(rateLimitManagerService, never()).checkRateLimitAndThrow(any(), anyString());
  }

  @Test
  void anInfrastructureFailureDuringLoginIsRethrownAndNotCountedAsAGuess() {
    InternalAuthenticationServiceException outage = new InternalAuthenticationServiceException("database down");
    when(authenticationManager.authenticate(any())).thenThrow(outage);

    assertThatThrownBy(() -> login("user@example.com", "Str0ng!Pass")).isSameAs(outage);

    verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
    verify(loginAttemptService, never()).reset(anyString(), anyString());
    assertThat(count("auth.login.failed", "reason", "bad_credentials")).isZero();
  }

  // ---- Task 9: OAuth2 token issuance -------------------------------------------------

  @Test
  void oauth2LoginIssuesTypedTokensForAnActiveAccount() throws Exception {
    givenUser(user("user@example.com", EUserStatus.ACTIVE));

    TokenResponseDto tokens = authService.issueTokensForOAuth2Login(" User@Example.com ");

    assertThat(jwtService.isAccessToken(tokens.accessToken())).isTrue();
    assertThat(jwtService.isRefreshToken(tokens.refreshToken())).isTrue();
    ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
    verify(refreshTokenRepository).save(saved.capture());
    assertThat(saved.getValue().getToken()).isEqualTo(sha256Hex(tokens.refreshToken()));
    assertThat(count("auth.oauth2.login", "result", "success")).isEqualTo(1.0);
  }

  @Test
  void oauth2LoginRefusesUnknownOrNonActiveAccounts() {
    givenUser(user("pending@example.com", EUserStatus.PENDING));

    for (String email : java.util.Arrays.asList("pending@example.com", "nobody@example.com", null)) {
      assertThatThrownBy(() -> authService.issueTokensForOAuth2Login(email))
          .isInstanceOf(UserNotActiveException.class)
          .hasMessage("Account is not active");
    }
    verify(refreshTokenRepository, never()).save(any());
  }
}
