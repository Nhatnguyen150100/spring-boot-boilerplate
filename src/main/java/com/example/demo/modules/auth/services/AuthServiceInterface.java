package com.example.demo.modules.auth.services;

import org.springframework.http.ResponseEntity;

import com.example.demo.common.response.BaseResponse;
import com.example.demo.modules.auth.dto.request.LoginRequestDto;
import com.example.demo.modules.auth.dto.request.RegisterRequestDto;

public interface AuthServiceInterface {
  ResponseEntity<BaseResponse> register(RegisterRequestDto dto);

  ResponseEntity<BaseResponse> login(LoginRequestDto dto);
}