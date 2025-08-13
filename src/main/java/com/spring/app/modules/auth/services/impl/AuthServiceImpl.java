package com.spring.app.modules.auth.services.impl;

import java.time.Instant;
import java.util.List;

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

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.CacheConfig;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ConflictException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import com.spring.app.shared.services.MonitoringService;
import lombok.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuthServiceImpl implements AuthServiceInterface {

  private static final int TIME_OTP_EXPIRATION = 3 * 60 * 1000; // 3 minutes

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
  private final MailServiceInterface mailService;
  private final MonitoringService monitoringService;

  @Override
  @CacheEvict(value = CacheConfig.USERS_CACHE, key = "#dto.email")
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();

    try {
      String emailRegister = dto.getEmail();
      validateEmailUniqueness(emailRegister);

      Boolean isDevMode = activeProfile.equals("dev");

      if (!isDevMode) {
        sendOtpEmailAsync(emailRegister);
      }

      User newUser = User.builder()
          .email(emailRegister)
          .password(dto.getPassword())
          .status(isDevMode ? EUserStatus.ACTIVE : EUserStatus.PENDING)
          .fullName(dto.getFullName())
          .build();
      newUser.setPassword(passwordEncoder.encode(dto.getPassword()));
      userRepository.save(newUser);

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();

      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> resendOtp(String email) {
    User user = getUserByEmail(email);

    if (user.getStatus() != EUserStatus.PENDING) {
      throw new BadRequestException("User is not in pending status, cannot resend OTP");
    }

    String otp = otpFunction.generateOtp();
    otpFunction.storeOtp(email, otp, TIME_OTP_EXPIRATION);

    try {
      mailService.sendOtpEmail(email, otp);
    } catch (Exception e) {
      log.error("Failed to send OTP email", e);
      throw new BadRequestException("Failed to send OTP email");
    }

    return ResponseBuilder.success("OTP resent successfully");
  }

  @Override
  public ResponseEntity<?> activeAccount(ActiveAccountRequestDto activeAccountRequestDto) {
    String email = activeAccountRequestDto.getEmail();
    String otp = activeAccountRequestDto.getOtp();
    User user = getUserByEmail(email);

    if (user.getStatus() != EUserStatus.PENDING) {
      throw new BadRequestException("User is not in pending status, cannot activate account");
    }

    if (!otpFunction.validateOtp(email, otp)) {
      throw new BadRequestException("Invalid or expired OTP");
    }

    user.setStatus(EUserStatus.ACTIVE);
    userRepository.save(user);

    otpFunction.removeOtp(email); // Clear OTP after successful activation

    updateCachedUser(user); // Update cache with new user status

    return ResponseBuilder.success("Account activated successfully");
  }

  @Override
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
      monitoringService.stopRegistrationTimer(timer);
    }
  }

  @Override
  @CacheEvict(value = CacheConfig.USERS_CACHE, key = "#user.id")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = jwtFunction.extractTokenFromHeader(request);
    if (token == null) {
      throw new BadRequestException("Authorization header is missing or invalid");
    }

    String email = jwtService.extractUsername(token);
    User user = getUserByEmail(email);

    revokeAllTokensForUser(user);

    SecurityContextHolder.clearContext();
    log.info("User {} logged out successfully", user.getEmail());
    return ResponseBuilder.success("User logged out successfully");
  }

  @Override
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    RefreshToken oldToken = getRefreshToken(dto.getRefreshToken());

    if (oldToken.isRevoked() || oldToken.getExpiryDate().isBefore(Instant.now())) {
      throw new BadRequestException("Token is revoked or expired");
    }

    oldToken.setRevoked(true);
    refreshTokenRepository.save(oldToken);

    evictCachedToken(dto.getRefreshToken());

    User user = oldToken.getUser();
    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = createAndStoreRefreshToken(user);

    var response = authMapper.newTokenToTokenResponseDto(accessToken, newRefreshToken);
    return ResponseBuilder.success("Token refreshed successfully", response);
  }

  private void validateEmailUniqueness(String email) {
    if (userRepository.existsByEmailAndStatus(email, EUserStatus.ACTIVE)) {
      throw new ConflictException("Email already exists");
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

  private void revokeAllTokensForUser(User user) {
    List<RefreshToken> tokens = refreshTokenRepository.findAllByUserAndIsRevokedFalse(user);
    tokens.forEach(t -> t.setRevoked(true));
    refreshTokenRepository.saveAll(tokens);
  }

  @Async("emailExecutor")
  private void sendOtpEmailAsync(String email) {
    try {
      String otp = otpFunction.generateOtp();
      otpFunction.storeOtp(email, otp, TIME_OTP_EXPIRATION);
      mailService.sendOtpEmail(email, otp);
    } catch (Exception e) {
      log.error("Failed to send OTP email to: {}", email, e);
    }
  }

  @Cacheable(value = CacheConfig.USERS_CACHE, key = "#email", unless = "#result == null")
  private User getUserByEmail(String email) {
    return userRepository.findByEmail(email)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
  }

  @CachePut(value = CacheConfig.USERS_CACHE, key = "#user.email")
  public User updateCachedUser(User user) {
    return user;
  }

  @Cacheable(value = CacheConfig.TOKENS_CACHE, key = "#token", unless = "#result == null")
  public RefreshToken getRefreshToken(String token) {
    return refreshTokenRepository.findByToken(token)
        .orElseThrow(() -> new BadRequestException("Invalid refresh token"));
  }

  @CacheEvict(value = CacheConfig.TOKENS_CACHE, key = "#token")
  public void evictCachedToken(String token) {
    log.info("Evicting cached token: {}", token);
  }
}
