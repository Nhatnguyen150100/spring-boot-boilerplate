package com.spring.app.modules.auth.services.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.spring.app.common.response.ResponseBuilder;
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
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.modules.auth.services.LoginAttemptService;
import com.spring.app.modules.auth.services.OtpAttemptService;
import com.spring.app.modules.auth.services.TokenRevocationService;
import com.spring.app.modules.auth.utils.EmailNormalizer;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.AuthCacheService;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.shared.services.RateLimitManagerService;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Authentication use cases.
 *
 * <p>
 * <b>Transactions:</b> no method here is {@code @Transactional}. An open
 * transaction pins a Hikari connection, and with virtual threads the pool
 * (not Tomcat's thread count) is the concurrency cap - so BCrypt, JWT signing
 * and Redis round-trips must never run inside one. A single write relies on
 * the repository's own transaction; a step with several writes wraps only
 * those writes in {@link TransactionTemplate}.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

  static final String INVALID_CREDENTIALS = "Invalid email or password";
  static final String INVALID_OTP = "Invalid or expired OTP";
  static final String INVALID_ACCESS_TOKEN = "Invalid or expired token";
  static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";
  static final String REGISTRATION_REJECTED = "This email cannot be registered";
  static final String ACCOUNT_NOT_ACTIVE = "Account is not active";
  static final String OTP_PURPOSE_ACTIVATION = "activation";
  static final String OTP_PURPOSE_PASSWORD_RESET = "password_reset";

  private final AuthProperties authProperties;
  private final Environment environment;
  private final JwtProperties jwtProperties;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtServiceInterface jwtService;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final AuthMapper authMapper;
  private final JwtFunctionUtil jwtFunction;
  private final OtpFunctionUtil otpFunction;
  private final MonitoringService monitoringService;
  private final OtpEmailService otpEmailService;
  private final AuthCacheService authCacheService;
  private final RedisServiceInterface redisService;
  private final RateLimitManagerService rateLimitManagerService;
  private final LoginAttemptService loginAttemptService;
  private final TokenRevocationService tokenRevocationService;
  private final OtpAttemptService otpAttemptService;
  private final TransactionTemplate transactionTemplate;

  /**
   * Per-email throttle for the actions that SEND an OTP e-mail (register,
   * resend, forgot-password). Complements the per-IP limit in
   * {@link com.spring.app.filter.RateLimitFilter} so one mailbox cannot be
   * e-mail-bombed from many IPs. OTP verification is deliberately not
   * throttled per e-mail (a stranger could block the owner's own activation);
   * {@link OtpAttemptService} caps the guesses per OTP instead. Logins are
   * covered by {@link LoginAttemptService}. The identifier is a hash: it ends
   * up in Redis keys and rate-limit log lines.
   */
  private void throttleByEmail(String email) {
    if (email == null || email.isBlank()) {
      return;
    }
    try {
      rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, "email:" + EmailNormalizer.fingerprint(email));
    } catch (RateLimitExceededException e) {
      monitoringService.recordRateLimitRejection("auth_email");
      throw e;
    }
  }

  /**
   * Mails a fresh OTP and clears the wrong-guess counter for {@code purpose}:
   * earlier wrong guesses were against the OTP this one replaces, so they must
   * not eat into the new OTP's {@link OtpAttemptService} budget.
   */
  private void sendOtp(String email, String purpose) {
    otpAttemptService.reset(email, purpose);
    otpEmailService.sendOtpEmailAsync(email);
  }

  /**
   * The OTP step is skipped only when BOTH application.auth.skip-email-verification
   * is true AND the dev profile is active.
   */
  private boolean isEmailVerificationSkipped() {
    if (!authProperties.isSkipEmailVerification()) {
      return false;
    }
    if (environment.acceptsProfiles(Profiles.of("dev"))) {
      return true;
    }
    log.warn("application.auth.skip-email-verification=true is ignored: the dev profile is not active");
    return false;
  }

  @Override
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();
    try {
      String emailRegister = EmailNormalizer.normalize(dto.email());
      throttleByEmail(emailRegister);

      // uk_users_email covers every row, DELETED included, so any existing row
      // owns the address. Checking only non-DELETED rows let the INSERT hit the
      // constraint (500) after the OTP had already been sent.
      if (userRepository.existsByEmail(emailRegister)) {
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      boolean skipVerification = isEmailVerificationSkipped();
      User newUser = User.builder()
          .email(emailRegister)
          .password(passwordEncoder.encode(dto.password()))
          .fullName(dto.fullName())
          .phone(dto.phone())
          .address(dto.address())
          .status(skipVerification ? EUserStatus.ACTIVE : EUserStatus.PENDING)
          .build();
      try {
        userRepository.saveAndFlush(newUser);
      } catch (DataIntegrityViolationException e) {
        // Lost a race with a concurrent registration of the same address.
        // Types only: the driver's message quotes the duplicate e-mail.
        log.warn("Registration rejected by a constraint: {} (root cause {})", e.getClass().getSimpleName(),
            NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      // Only once the row exists: an OTP for an account that failed to save is useless.
      if (!skipVerification) {
        sendOtp(emailRegister, OTP_PURPOSE_ACTIVATION);
      }

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();
      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> resendOtp(String rawEmail) {
    String email = EmailNormalizer.normalize(rawEmail);
    throttleByEmail(email);
    // Same answer whether the account is unknown, ACTIVE or PENDING.
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING)
        .ifPresent(user -> sendOtp(email, OTP_PURPOSE_ACTIVATION));
    return ResponseBuilder.success("If the account is awaiting activation, a new OTP has been sent");
  }

  @Override
  public ResponseEntity<?> activeAccount(ActiveAccountRequestDto dto) {
    String email = EmailNormalizer.normalize(dto.email());

    // Unknown e-mail, wrong status and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> pending = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING);
    if (!otpValid || pending.isEmpty()) {
      throw rejectOtp(email, OTP_PURPOSE_ACTIVATION, !otpValid);
    }

    User user = pending.get();
    user.setStatus(EUserStatus.ACTIVE);
    userRepository.save(user);
    otpFunction.removeOtp(email);
    otpAttemptService.reset(email, OTP_PURPOSE_ACTIVATION);
    authCacheService.updateCachedUser(user);
    return ResponseBuilder.success("Account activated successfully");
  }

  @Override
  public ResponseEntity<?> login(LoginRequestDto dto, String clientIp) {
    Timer.Sample timer = monitoringService.startLoginTimer();
    try {
      String email = EmailNormalizer.normalize(dto.email());
      // Only FAILED attempts count, per e-mail + client IP: a stranger can no
      // longer lock a user out by spamming logins with the user's address.
      if (loginAttemptService.isBlocked(email, clientIp)) {
        monitoringService.recordLoginFailure("locked");
        monitoringService.recordRateLimitRejection("login");
        throw new RateLimitExceededException("Too many failed login attempts. Please try again later.");
      }

      User user = authenticate(email, dto.password(), clientIp);
      loginAttemptService.reset(email, clientIp);

      String accessToken = jwtService.generateToken(user);
      String refreshToken = createAndStoreRefreshToken(user);

      var response = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);

      monitoringService.recordLoginSuccess();
      return ResponseBuilder.success("Login successful", response);

    } finally {
      monitoringService.stopLoginTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = jwtFunction.extractTokenFromHeader(request);
    if (token == null) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    Claims claims = parseAccessToken(token);
    // Tokens minted for legacy mixed-case rows carry that casing as subject.
    User user = Optional.ofNullable(EmailNormalizer.normalize(claims.getSubject()))
        .flatMap(userRepository::findByEmail)
        .orElseThrow(() -> new InvalidTokenException(INVALID_ACCESS_TOKEN));

    long remainingSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
    if (remainingSeconds > 0) {
      redisService.blacklistToken(token, remainingSeconds);
    }
    refreshTokenRepository.revokeAllByUser(user);
    // Sign out everywhere: the access tokens of the user's other devices end too.
    tokenRevocationService.revokeAccessTokens(user.getId());

    SecurityContextHolder.clearContext();
    monitoringService.recordLogout();
    log.info("User {} logged out", user.getId());
    return ResponseBuilder.success("User logged out successfully");
  }

  @Override
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    String presented = dto.refreshToken();
    if (!jwtService.isRefreshToken(presented)) {
      throw rejectRefresh();
    }
    String presentedHash = hashRefreshToken(presented);
    RefreshToken stored = refreshTokenRepository.findByToken(presentedHash)
        .filter(token -> !token.isRevoked())
        .filter(token -> token.getExpiryDate() != null && token.getExpiryDate().isAfter(Instant.now()))
        .orElseThrow(this::rejectRefresh);

    User user = stored.getUser();
    // A DELETED / INACTIVE / PENDING account must not keep minting tokens.
    if (user.getStatus() != EUserStatus.ACTIVE) {
      throw rejectRefresh();
    }

    // Signed before the transaction opens, so it only spans the two writes.
    String accessToken = jwtService.generateToken(user);
    String refreshToken = jwtService.generateRefreshToken(user);
    transactionTemplate.executeWithoutResult(status -> {
      // Conditional UPDATE: of two concurrent refreshes with this token, one wins.
      if (refreshTokenRepository.revokeIfActive(presentedHash) != 1) {
        throw rejectRefresh();
      }
      storeRefreshToken(user, refreshToken);
    });
    monitoringService.recordTokenRefresh(true);
    return ResponseBuilder.success("Token refreshed successfully",
        authMapper.newTokenToTokenResponseDto(accessToken, refreshToken));
  }

  @Override
  public ResponseEntity<?> forgotPassword(String rawEmail) {
    String email = EmailNormalizer.normalize(rawEmail);
    throttleByEmail(email);
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE)
        .ifPresent(user -> sendOtp(email, OTP_PURPOSE_PASSWORD_RESET));
    return ResponseBuilder.success("If your email is registered, you will receive an OTP shortly");
  }

  @Override
  public ResponseEntity<?> resetPassword(ResetPasswordRequestDto dto) {
    String email = EmailNormalizer.normalize(dto.email());

    // Unknown e-mail, non-ACTIVE account and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> active = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE);
    if (!otpValid || active.isEmpty()) {
      throw rejectOtp(email, OTP_PURPOSE_PASSWORD_RESET, !otpValid);
    }

    User user = active.get();
    String encodedPassword = passwordEncoder.encode(dto.newPassword()); // BCrypt outside the transaction
    transactionTemplate.executeWithoutResult(status -> {
      user.setPassword(encodedPassword);
      userRepository.save(user);
      refreshTokenRepository.revokeAllByUser(user);
    });
    // Access tokens are stateless: refuse every one issued before this moment.
    tokenRevocationService.revokeAccessTokens(user.getId());
    otpFunction.removeOtp(email);
    otpAttemptService.reset(email, OTP_PURPOSE_PASSWORD_RESET);
    return ResponseBuilder.success("Password reset successfully");
  }

  @Override
  public TokenResponseDto issueTokensForOAuth2Login(String rawEmail) {
    // Re-checked here even though CustomOAuth2UserService already refused
    // non-ACTIVE accounts: the status may have changed in between.
    User user = Optional.ofNullable(EmailNormalizer.normalize(rawEmail))
        .flatMap(userRepository::findByEmail)
        .filter(candidate -> candidate.getStatus() == EUserStatus.ACTIVE)
        .orElseThrow(() -> new UserNotActiveException(ACCOUNT_NOT_ACTIVE));

    String accessToken = jwtService.generateToken(user);
    String refreshToken = createAndStoreRefreshToken(user);
    monitoringService.recordOAuth2Login(true);
    log.info("OAuth2 login for user {}", user.getId());
    return authMapper.newTokenToTokenResponseDto(accessToken, refreshToken);
  }

  /** Parses a bearer token for logout. Anything unusable is a 401, never a 500. */
  private Claims parseAccessToken(String token) {
    Claims claims;
    try {
      claims = jwtService.parseClaims(token);
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    if (!JwtServiceInterface.ACCESS_TOKEN_TYPE.equals(
        claims.get(JwtServiceInterface.TOKEN_TYPE_CLAIM, String.class))) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    return claims;
  }

  /**
   * The uniform INVALID_OTP answer. A wrong OTP also counts towards the
   * per-OTP cap; the guess that reaches it discards the OTP, so the response
   * never changes but further guesses cannot succeed.
   */
  private BadRequestException rejectOtp(String email, String purpose, boolean wrongOtp) {
    if (wrongOtp && otpAttemptService.recordFailure(email, purpose)) {
      otpFunction.removeOtp(email);
    }
    monitoringService.recordOtpFailure(purpose);
    return new BadRequestException(INVALID_OTP);
  }

  private InvalidTokenException rejectRefresh() {
    monitoringService.recordTokenRefresh(false);
    return new InvalidTokenException(INVALID_REFRESH_TOKEN);
  }

  private User authenticate(String email, String password, String clientIp) {
    try {
      Authentication authenticated = authenticationManager
          .authenticate(new UsernamePasswordAuthenticationToken(email, password));
      return (User) authenticated.getPrincipal();
    } catch (UserNotActiveException ex) {
      // Thrown only after the password matched (ApplicationConfig).
      monitoringService.recordLoginFailure("not_active");
      throw ex;
    } catch (InternalAuthenticationServiceException ex) {
      // The provider could not even look the user up (e.g. database down). That
      // is an outage, not a wrong guess: no failure count, no bad_credentials.
      throw ex;
    } catch (AuthenticationException ex) {
      // Unknown e-mail, DELETED account and wrong password look the same.
      loginAttemptService.recordFailure(email, clientIp);
      monitoringService.recordLoginFailure("bad_credentials");
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }
  }

  private String createAndStoreRefreshToken(User user) {
    String refreshToken = jwtService.generateRefreshToken(user);
    storeRefreshToken(user, refreshToken);
    return refreshToken;
  }

  /** The client gets the JWT; the database only ever sees its hash. */
  private void storeRefreshToken(User user, String refreshToken) {
    refreshTokenRepository.save(authMapper.userToRefreshToken(user, hashRefreshToken(refreshToken),
        Instant.now().plusMillis(jwtProperties.getRefreshExpiration())));
  }

  /**
   * SHA-256 (64 hex chars) of a refresh JWT: the value stored in, and looked
   * up from, {@code refresh_tokens.token}. The JWT carries the e-mail as its
   * subject, so for long addresses it outgrows the VARCHAR(512) column; the
   * hash always fits, and a leaked table holds no usable tokens.
   */
  private static String hashRefreshToken(String refreshToken) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
