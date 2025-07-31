package com.spring.app.modules.auth.services.impl;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.spring.app.common.response.BaseResponse;
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

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

  private static final int TIME_OTP_EXPIRATION = 3 * 60 * 1000; // 3 minutes

  @Value("${application.security.jwt.refresh-token.expiration}")
  private long refreshExpiration;

  @Value("${spring.profiles.active}")
  private String activeProfile;

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtServiceInterface jwtService;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final AuthMapper authMapper;
  private final JwtFunctionUtil jwtFunction;
  private final OtpFunctionUtil otpFunction;
  private final MailServiceInterface mailService;

  @Override
  public ResponseEntity<BaseResponse> register(RegisterRequestDto dto) {
    String emailRegister = dto.getEmail();
    validateEmailUniqueness(emailRegister);

    Boolean isDevMode = activeProfile.equals("dev");

    if (!isDevMode) {
      String otp = otpFunction.generateOtp();
      otpFunction.storeOtp(emailRegister, otp, TIME_OTP_EXPIRATION);
      try {
        mailService.sendOtpEmail(emailRegister, otp);
      } catch (Exception e) {
        log.error("Failed to send OTP email", e);
        throw new BadRequestException("Failed to send OTP email");
      }
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

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(BaseResponse.success("User registered successfully", HttpStatus.CREATED, response));
  }

  @Override
  public ResponseEntity<?> resendOtp(String email) {
    User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

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

    return ResponseEntity.ok(BaseResponse.success("OTP resent successfully"));
  }

  @Override
  public ResponseEntity<?> activeAccount(ActiveAccountRequestDto activeAccountRequestDto) {
    String email = activeAccountRequestDto.getEmail();
    String otp = activeAccountRequestDto.getOtp();
    User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));

    if (user.getStatus() != EUserStatus.PENDING) {
      throw new BadRequestException("User is not in pending status, cannot activate account");
    }

    if (!otpFunction.validateOtp(email, otp)) {
      throw new BadRequestException("Invalid or expired OTP");
    }

    user.setStatus(EUserStatus.ACTIVE);
    userRepository.save(user);

    return ResponseEntity.ok(BaseResponse.success("Account activated successfully"));
  }

  @Override
  public ResponseEntity<?> login(LoginRequestDto dto) {
    User user = authenticate(dto.getEmail(), dto.getPassword());

    String accessToken = jwtService.generateToken(user);
    String refreshToken = createAndStoreRefreshToken(user);

    var response = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);

    return ResponseEntity.ok(BaseResponse.success("User logged in successfully", response));
  }

  @Override
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = jwtFunction.extractTokenFromHeader(request);
    if (token == null) {
      throw new BadRequestException("Authorization header is missing or invalid");
    }

    String email = jwtService.extractUsername(token);
    User user = userRepository.findByEmailAndStatus(email, EUserStatus.ACTIVE)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));

    revokeAllTokensForUser(user);

    SecurityContextHolder.clearContext();
    return ResponseEntity.ok(BaseResponse.success("User logged out successfully"));
  }

  @Override
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    RefreshToken oldToken = refreshTokenRepository.findByToken(dto.getRefreshToken())
        .orElseThrow(() -> new BadRequestException("Invalid refresh token"));

    if (oldToken.isRevoked() || oldToken.getExpiryDate().isBefore(Instant.now())) {
      throw new BadRequestException("Token is revoked or expired");
    }

    oldToken.setRevoked(true);
    refreshTokenRepository.save(oldToken);

    User user = oldToken.getUser();
    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = createAndStoreRefreshToken(user);

    var response = authMapper.newTokenToTokenResponseDto(accessToken, newRefreshToken);

    return ResponseEntity.ok(BaseResponse.success("Token refreshed successfully", response));
  }

  private void validateEmailUniqueness(String email) {
    if (userRepository.existsByEmailAndStatus(email, EUserStatus.ACTIVE)) {
      throw new ConflictException("Email already exists");
    }
  }

  private User authenticate(String email, String password) {
    Authentication authentication = new UsernamePasswordAuthenticationToken(email, password);
    Authentication authenticated = authenticationManager.authenticate(authentication);
    return (User) authenticated.getPrincipal();
  }

  private String createAndStoreRefreshToken(User user) {
    String refreshToken = jwtService.generateRefreshToken(user);

    RefreshToken tokenEntity = authMapper.userToRefreshToken(user, refreshToken,
        Instant.now().plusMillis(refreshExpiration));

    refreshTokenRepository.save(tokenEntity);
    return refreshToken;
  }

  private void revokeAllTokensForUser(User user) {
    List<RefreshToken> tokens = refreshTokenRepository.findAllByUserAndIsRevokedFalse(user);
    tokens.forEach(t -> t.setRevoked(true));
    refreshTokenRepository.saveAll(tokens);
  }

}
