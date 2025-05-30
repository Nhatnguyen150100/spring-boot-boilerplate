package com.spring.app.modules.auth.services;

import org.springframework.http.ResponseEntity;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;

public interface AuthServiceInterface {
  ResponseEntity<BaseResponse> register(RegisterRequestDto dto);

  ResponseEntity<BaseResponse> login(LoginRequestDto dto);
}