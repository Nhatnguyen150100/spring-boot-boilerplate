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
import com.spring.app.exceptions.ConflictException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.response.LoginResponseDto;
import com.spring.app.modules.auth.dto.response.RegisterResponseDto;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.mapper.UserMapper;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.services.JwtServiceInterface;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

  @Value("${application.security.jwt.refresh-token.expiration}")
  private long refreshExpiration;

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtServiceInterface jwtService;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final UserMapper userMapper;

  @Override
  public ResponseEntity<BaseResponse> register(RegisterRequestDto dto) {
    validateEmailUniqueness(dto.getEmail());

    User newUser = userMapper.registerRequestDtoToUser(dto);
    newUser.setPassword(passwordEncoder.encode(dto.getPassword()));
    userRepository.save(newUser);

    var response = RegisterResponseDto.builder()
        .email(newUser.getEmail())
        .build();

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(BaseResponse.success("User registered successfully", HttpStatus.CREATED, response));
  }

  @Override
  public ResponseEntity<BaseResponse> login(LoginRequestDto dto) {
    User user = authenticate(dto.getEmail(), dto.getPassword());

    String accessToken = jwtService.generateToken(user);
    String refreshToken = createAndStoreRefreshToken(user);

    var response = LoginResponseDto.builder()
        .userResponseDto(userMapper.userToUserResponseDto(user))
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .build();

    return ResponseEntity.ok(BaseResponse.success("User logged in successfully", response));
  }

  @Override
  public ResponseEntity<BaseResponse> logout(HttpServletRequest request) {
    String token = extractTokenFromHeader(request);

    String username = jwtService.extractUsername(token);
    User user = userRepository.findByEmail(username)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));

    revokeAllTokensForUser(user);

    SecurityContextHolder.clearContext();
    return ResponseEntity.ok(BaseResponse.success("User logged out successfully", null));
  }

  @Override
  public ResponseEntity<BaseResponse> refreshToken(RefreshTokenDto dto) {
    RefreshToken oldToken = refreshTokenRepository.findByToken(dto.getRefreshToken())
        .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

    if (oldToken.isRevoked() || oldToken.getExpiryDate().isBefore(Instant.now())) {
      throw new BadCredentialsException("Token is revoked or expired");
    }

    oldToken.setRevoked(true);
    refreshTokenRepository.save(oldToken);

    User user = oldToken.getUser();
    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = createAndStoreRefreshToken(user);

    var tokenResponse = TokenResponseDto.builder()
        .accessToken(accessToken)
        .refreshToken(newRefreshToken)
        .build();

    return ResponseEntity.ok(BaseResponse.success("Token refreshed successfully", tokenResponse));
  }

  private void validateEmailUniqueness(String email) {
    if (userRepository.existsByEmail(email)) {
      throw new ConflictException("Email already exists");
    }
  }

  private User authenticate(String email, String password) {
    Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(email, password));
    return (User) authentication.getPrincipal();
  }

  private String createAndStoreRefreshToken(User user) {
    String refreshToken = jwtService.generateRefreshToken(user);

    RefreshToken tokenEntity = RefreshToken.builder()
        .user(user)
        .token(refreshToken)
        .expiryDate(Instant.now().plusMillis(refreshExpiration))
        .isRevoked(false)
        .build();

    refreshTokenRepository.save(tokenEntity);
    return refreshToken;
  }

  private String extractTokenFromHeader(HttpServletRequest request) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      throw new BadCredentialsException("Access token not found");
    }
    return header.substring(7);
  }

  private void revokeAllTokensForUser(User user) {
    List<RefreshToken> tokens = refreshTokenRepository.findAllByUserAndIsRevokedFalse(user);
    tokens.forEach(t -> t.setRevoked(true));
    refreshTokenRepository.saveAll(tokens);
  }
}
