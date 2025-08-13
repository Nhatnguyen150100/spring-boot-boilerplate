package com.spring.app.modules.auth.services;

import org.springframework.http.ResponseEntity;

import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;

import jakarta.servlet.http.HttpServletRequest;

public interface AuthServiceInterface {
  ResponseEntity<?> register(RegisterRequestDto dto);

  ResponseEntity<?> resendOtp(String email);

  ResponseEntity<?> activeAccount(ActiveAccountRequestDto activeAccountRequestDto);

  ResponseEntity<?> login(LoginRequestDto dto);

  ResponseEntity<?> logout(HttpServletRequest request);

  ResponseEntity<?> refreshToken(RefreshTokenDto dto);
}