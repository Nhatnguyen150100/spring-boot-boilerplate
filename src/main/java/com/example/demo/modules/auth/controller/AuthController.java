package com.example.demo.modules.auth.controller;

import com.example.demo.common.response.BaseResponse;
import com.example.demo.modules.auth.dto.request.LoginRequestDto;
import com.example.demo.modules.auth.dto.request.RegisterRequestDto;
import com.example.demo.modules.auth.services.AuthServiceInterface;

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

  @PostMapping("/register")
  public ResponseEntity<BaseResponse> register(@Valid @RequestBody RegisterRequestDto dto) {
    return this.authService.register(dto);
  }

  @PostMapping("/login")
  public ResponseEntity<BaseResponse> login(@Valid @RequestBody LoginRequestDto dto) {
    return this.authService.login(dto);
  }
}
