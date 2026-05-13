package com.spring.app.modules.auth.services.impl;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ConflictException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.AuthCacheService;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

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

  @Override
  @Transactional
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();

    try {
      String emailRegister = dto.getEmail();
      validateEmailUniqueness(emailRegister);

      boolean isDevMode = activeProfile.equals("dev");

      if (!isDevMode) {
        otpEmailService.sendOtpEmailAsync(emailRegister);
      }

      User newUser = User.builder()
          .email(emailRegister)
          .password(passwordEncoder.encode(dto.getPassword()))
          .status(isDevMode ? EUserStatus.ACTIVE : EUserStatus.PENDING)
          .fullName(dto.getFullName())
          .build();
      userRepository.save(newUser);

      authCacheService.evictCachedUser(emailRegister);

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();

      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> resendOtp(String email) {
    User user = authCacheService.getUserByEmail(email);

    if (user.getStatus() != EUserStatus.PENDING) {
      throw new BadRequestException("User is not in pending status, cannot resend OTP");
    }

    otpEmailService.sendOtpEmailAsync(email);

    return ResponseBuilder.success("OTP resent successfully");
  }

  @Override
  @Transactional
  public ResponseEntity<?> activeAccount(ActiveAccountRequestDto dto) {
    String email = dto.getEmail();
    String otp = dto.getOtp();
    User user = authCacheService.getUserByEmail(email);

    if (user.getStatus() != EUserStatus.PENDING) {
      throw new BadRequestException("User is not in pending status, cannot activate account");
    }

    if (!otpFunction.validateOtp(email, otp)) {
      throw new BadRequestException("Invalid or expired OTP");
    }

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
      User user = authenticate(dto.getEmail(), dto.getPassword());

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
      throw new BadRequestException("Authorization header is missing or invalid");
    }

    String email = jwtService.extractUsername(token);
    User user = authCacheService.getUserByEmail(email);

    long remainingTtl = jwtService.getRemainingExpirationSeconds(token);
    if (remainingTtl > 0) {
      redisService.blacklistToken(token, remainingTtl);
    }

    refreshTokenRepository.revokeAllByUser(user);
    authCacheService.evictCachedUser(email);

    SecurityContextHolder.clearContext();
    log.info("User {} logged out successfully", user.getEmail());
    return ResponseBuilder.success("User logged out successfully");
  }

  @Override
  @Transactional
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    RefreshToken oldToken = authCacheService.getRefreshToken(dto.getRefreshToken());

    if (oldToken.isRevoked() || oldToken.getExpiryDate().isBefore(Instant.now())) {
      throw new BadRequestException("Token is revoked or expired");
    }

    oldToken.setRevoked(true);
    refreshTokenRepository.save(oldToken);

    authCacheService.evictCachedToken(dto.getRefreshToken());

    User user = oldToken.getUser();
    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = createAndStoreRefreshToken(user);

    var response = authMapper.newTokenToTokenResponseDto(accessToken, newRefreshToken);
    return ResponseBuilder.success("Token refreshed successfully", response);
  }

  @Override
  public ResponseEntity<?> forgotPassword(String email) {
    try {
      User user = authCacheService.getUserByEmail(email);
      if (user.getStatus() == EUserStatus.ACTIVE) {
        otpEmailService.sendOtpEmailAsync(email);
      }
    } catch (ResourceNotFoundException e) {
      log.debug("Forgot password requested for non-existent email: {}", email);
    }
    return ResponseBuilder.success("If your email is registered, you will receive an OTP shortly");
  }

  @Override
  @Transactional
  public ResponseEntity<?> resetPassword(ResetPasswordRequestDto dto) {
    User user = authCacheService.getUserByEmail(dto.getEmail());

    if (user.getStatus() != EUserStatus.ACTIVE) {
      throw new BadRequestException("Account is not active");
    }

    if (!otpFunction.validateOtp(dto.getEmail(), dto.getOtp())) {
      throw new BadRequestException("Invalid or expired OTP");
    }

    user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
    userRepository.save(user);

    otpFunction.removeOtp(dto.getEmail());
    authCacheService.updateCachedUser(user);
    refreshTokenRepository.revokeAllByUser(user);

    return ResponseBuilder.success("Password reset successfully");
  }

  private void validateEmailUniqueness(String email) {
    if (userRepository.existsByEmailAndStatusNot(email, EUserStatus.DELETED)) {
      throw new ConflictException("Email already registered");
    }
  }

  private User authenticate(String email, String password) {
    try {
      Authentication authentication = new UsernamePasswordAuthenticationToken(email, password);
      Authentication authenticated = authenticationManager.authenticate(authentication);
      return (User) authenticated.getPrincipal();
    } catch (BadCredentialsException ex) {
      throw new UsernameNotFoundException("Invalid email or password");
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
