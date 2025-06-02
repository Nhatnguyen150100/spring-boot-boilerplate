package com.spring.app.modules.auth.services.impl;

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
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.response.LoginResponseDto;
import com.spring.app.modules.auth.dto.response.RegisterResponseDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.mapper.UserMapper;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.services.JwtService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

  private final UserRepository userRepository;
  private final JwtService jwtService;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final UserMapper userMapper;

  @Override
  public ResponseEntity<BaseResponse> register(RegisterRequestDto dto) {
    if (this.userRepository.existsByEmail(dto.getEmail())) {
      throw new ConflictException("Email already exists");
    }

    User user = this.userMapper.registerRequestDtoToUser(dto);
    user.setPassword(passwordEncoder.encode(user.getPassword()));
    this.userRepository.save(user);

    RegisterResponseDto response = RegisterResponseDto.builder().email(user.getEmail()).build();

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(BaseResponse.success("User registered successfully", HttpStatus.CREATED, response));

  }

  @Override
  public ResponseEntity<BaseResponse> login(LoginRequestDto dto) {
    Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(dto.getEmail(), dto.getPassword())
    );

    User user = (User) authentication.getPrincipal();

    String accessToken = jwtService.generateToken(user);
    String refreshToken = jwtService.generateRefreshToken(user);

    LoginResponseDto response = LoginResponseDto.builder()
        .userResponseDto(this.userMapper.userToUserResponseDto(user))
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .build();

    return ResponseEntity.ok(BaseResponse.success("User logged in successfully", response));
}


}
