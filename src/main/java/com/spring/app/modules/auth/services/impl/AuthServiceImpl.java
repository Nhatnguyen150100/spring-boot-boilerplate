package com.spring.app.modules.auth.services.impl;

import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ConflictException;
import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

  static final String INVALID_CREDENTIALS = "Invalid email or password";
  static final String INVALID_OTP = "Invalid or expired OTP";
  static final String INVALID_ACCESS_TOKEN = "Invalid or expired token";
  static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";
  static final String REGISTRATION_REJECTED = "This email cannot be registered";

  @Value("${spring.profiles.active}")
  private String activeProfile;

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

  /**
   * Per-email throttle for sensitive auth actions (OTP send/verify, login).
   * Complements the per-IP limit in {@link com.spring.app.filter.RateLimitFilter}
   * so a single account cannot be brute-forced / email-bombed from many IPs.
   */
  private void throttleByEmail(String email) {
    if (email != null && !email.isBlank()) {
      rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, "email:" + email.toLowerCase());
    }
  }

  @Override
  @Transactional
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();
    try {
      String emailRegister = dto.email();
      throttleByEmail(emailRegister);

      // uk_users_email covers every row, DELETED included, so any existing row
      // owns the address. Checking only non-DELETED rows let the INSERT hit the
      // constraint (500) after the OTP had already been sent.
      if (userRepository.existsByEmail(emailRegister)) {
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      boolean isDevMode = activeProfile.equals("dev");
      User newUser = User.builder().email(emailRegister).password(passwordEncoder.encode(dto.password()))
          .status(isDevMode ? EUserStatus.ACTIVE : EUserStatus.PENDING).fullName(dto.fullName()).build();
      try {
        userRepository.saveAndFlush(newUser);
      } catch (DataIntegrityViolationException e) {
        // Lost a race with a concurrent registration of the same address.
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      // Only once the row exists: an OTP for an account that failed to save is useless.
      if (!isDevMode) {
        otpEmailService.sendOtpEmailAsync(emailRegister);
      }

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();
      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> resendOtp(String email) {
    throttleByEmail(email);
    // Same answer whether the account is unknown, ACTIVE or PENDING.
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If the account is awaiting activation, a new OTP has been sent");
  }

  @Override
  @Transactional
  public ResponseEntity<?> activeAccount(ActiveAccountRequestDto dto) {
    String email = dto.email();
    throttleByEmail(email);

    // Unknown e-mail, wrong status and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> pending = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING);
    if (!otpValid || pending.isEmpty()) {
      throw new BadRequestException(INVALID_OTP);
    }

    User user = pending.get();
    user.setStatus(EUserStatus.ACTIVE);
    userRepository.save(user);
    otpFunction.removeOtp(email);
    authCacheService.updateCachedUser(user);
    return ResponseBuilder.success("Account activated successfully");
  }

  @Override
  @Transactional
  public ResponseEntity<?> login(LoginRequestDto dto) {
    Timer.Sample timer = monitoringService.startLoginTimer();
    try {
      throttleByEmail(dto.email());
      User user = authenticate(dto.email(), dto.password());

      String accessToken = jwtService.generateToken(user);
      String refreshToken = createAndStoreRefreshToken(user);

      var response = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);

      monitoringService.incrementLoginAttempts();
      return ResponseBuilder.success("Login successful", response);

    } finally {
      monitoringService.stopLoginTimer(timer);
    }
  }

  @Override
  @Transactional
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = jwtFunction.extractTokenFromHeader(request);
    if (token == null) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    Claims claims = parseAccessToken(token);
    User user = userRepository.findByEmail(claims.getSubject())
        .orElseThrow(() -> new InvalidTokenException(INVALID_ACCESS_TOKEN));

    long remainingSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
    if (remainingSeconds > 0) {
      redisService.blacklistToken(token, remainingSeconds);
    }
    refreshTokenRepository.revokeAllByUser(user);

    SecurityContextHolder.clearContext();
    log.info("User {} logged out", user.getId());
    return ResponseBuilder.success("User logged out successfully");
  }

  @Override
  @Transactional
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    String presented = dto.refreshToken();
    if (!jwtService.isRefreshToken(presented)) {
      throw new InvalidTokenException(INVALID_REFRESH_TOKEN);
    }
    RefreshToken oldToken = refreshTokenRepository.findByToken(presented)
        .filter(token -> !token.isRevoked())
        .filter(token -> token.getExpiryDate() != null && token.getExpiryDate().isAfter(Instant.now()))
        .orElseThrow(() -> new InvalidTokenException(INVALID_REFRESH_TOKEN));

    User user = oldToken.getUser();
    // A DELETED / INACTIVE / PENDING account must not keep minting tokens.
    if (user.getStatus() != EUserStatus.ACTIVE) {
      throw new InvalidTokenException(INVALID_REFRESH_TOKEN);
    }

    oldToken.setRevoked(true);
    refreshTokenRepository.save(oldToken);

    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = createAndStoreRefreshToken(user);

    var response = authMapper.newTokenToTokenResponseDto(accessToken, newRefreshToken);
    return ResponseBuilder.success("Token refreshed successfully", response);
  }

  @Override
  public ResponseEntity<?> forgotPassword(String email) {
    throttleByEmail(email);
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If your email is registered, you will receive an OTP shortly");
  }

  @Override
  @Transactional
  public ResponseEntity<?> resetPassword(ResetPasswordRequestDto dto) {
    String email = dto.email();
    throttleByEmail(email);

    // Unknown e-mail, non-ACTIVE account and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> active = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE);
    if (!otpValid || active.isEmpty()) {
      throw new BadRequestException(INVALID_OTP);
    }

    User user = active.get();
    user.setPassword(passwordEncoder.encode(dto.newPassword()));
    userRepository.save(user);
    otpFunction.removeOtp(email);
    refreshTokenRepository.revokeAllByUser(user);
    return ResponseBuilder.success("Password reset successfully");
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

  private User authenticate(String email, String password) {
    try {
      Authentication authenticated = authenticationManager
          .authenticate(new UsernamePasswordAuthenticationToken(email, password));
      return (User) authenticated.getPrincipal();
    } catch (AuthenticationException ex) {
      // Unknown e-mail, DELETED account and wrong password look the same.
      // UserNotActiveException is not an AuthenticationException: it is thrown
      // only after the password matched and propagates unchanged.
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }
  }

  private String createAndStoreRefreshToken(User user) {
    String refreshToken = jwtService.generateRefreshToken(user);

    RefreshToken tokenEntity = authMapper.userToRefreshToken(user, refreshToken,
        Instant.now().plusMillis(jwtProperties.getRefreshExpiration()));

    refreshTokenRepository.save(tokenEntity);
    return refreshToken;
  }
}
