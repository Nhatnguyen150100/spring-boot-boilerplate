package com.spring.app.modules.auth.services;

import org.apache.coyote.BadRequestException;
import org.springframework.http.ResponseEntity;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;

import jakarta.servlet.http.HttpServletRequest;

public interface AuthServiceInterface {
  ResponseEntity<BaseResponse> register(RegisterRequestDto dto);

  ResponseEntity<BaseResponse> login(LoginRequestDto dto);

  ResponseEntity<BaseResponse> logout(HttpServletRequest request);

  ResponseEntity<BaseResponse> refreshToken(RefreshTokenDto dto) throws BadRequestException;
}