package com.spring.app.modules.auth.controller;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "APIs for user authentication and registration")
@RequiredArgsConstructor
public class AuthController {

  private final AuthServiceInterface authService;

  @Operation(summary = "Register a new user", description = "Registers a new user")
  @PostMapping("/register")
  public ResponseEntity<BaseResponse> register(@Valid @RequestBody RegisterRequestDto dto) {
    return authService.register(dto);
  }

  @Operation(summary = "Login your account", description = "Login your account")
  @PostMapping("/login")
  public ResponseEntity<BaseResponse> login(@Valid @RequestBody LoginRequestDto dto) {
    return authService.login(dto);
  }
}
