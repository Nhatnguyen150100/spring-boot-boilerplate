package com.spring.app.modules.auth.services.impl;

import java.time.Instant;
import java.util.Date;

import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.exceptions.ConflictException;
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
    if (userRepository.existsByEmail(dto.getEmail())) {
      throw new ConflictException("Email already exists");
    }

    User user = userMapper.registerRequestDtoToUser(dto);
    user.setPassword(passwordEncoder.encode(user.getPassword()));
    userRepository.save(user);

    RegisterResponseDto response = RegisterResponseDto.builder().email(user.getEmail()).build();

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(BaseResponse.success("User registered successfully", HttpStatus.CREATED, response));

  }

  @Override
  public ResponseEntity<BaseResponse> login(LoginRequestDto dto) {
    Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(dto.getEmail(), dto.getPassword()));

    User user = (User) authentication.getPrincipal();

    String accessToken = jwtService.generateToken(user);
    String refreshToken = jwtService.generateRefreshToken(user);
    refreshTokenRepository.save(RefreshToken.builder().user(user).token(refreshToken)
        .expiryDate(new Date(System.currentTimeMillis() + refreshExpiration).toInstant()).build());

    LoginResponseDto response = LoginResponseDto.builder()
        .userResponseDto(userMapper.userToUserResponseDto(user))
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .build();

    return ResponseEntity.ok(BaseResponse.success("User logged in successfully", response));
  }

  @Override
  public ResponseEntity<BaseResponse> logout() {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'logout'");
  }

  @Override
  public ResponseEntity<BaseResponse> refreshToken(RefreshTokenDto dto) throws BadRequestException {
    String oldToken = dto.getRefreshToken();

    RefreshToken refreshToken = refreshTokenRepository.findByToken(oldToken)
        .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

    if (refreshToken.isRevoked() || refreshToken.getExpiryDate().isBefore(Instant.now())) {
      throw new BadRequestException("Token is revoked or expired");
    }

    refreshToken.setRevoked(true);
    refreshTokenRepository.save(refreshToken);

    User user = refreshToken.getUser();

    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = jwtService.generateRefreshToken(user);
    refreshTokenRepository.save(RefreshToken.builder().user(user).token(newRefreshToken)
        .expiryDate(new Date(System.currentTimeMillis() + refreshExpiration).toInstant()).build());

    var tokenResponse = TokenResponseDto.builder().accessToken(accessToken).refreshToken(newRefreshToken).build();

    return ResponseEntity.ok(BaseResponse.success("Token refreshed successfully", tokenResponse));

  }

}
