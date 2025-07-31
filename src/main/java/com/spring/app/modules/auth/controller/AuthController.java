package com.spring.app.modules.auth.controller;

import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.apache.coyote.BadRequestException;
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
  public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDto dto) {
    return authService.register(dto);
  }

  @Operation(summary = "Login your account", description = "Login your account")
  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto dto) {
    return authService.login(dto);
  }

  @Operation(summary = "Refresh your token", description = "Refresh your token")
  @PostMapping("/refresh-token")
  public ResponseEntity<?> refresh(@Valid @RequestBody RefreshTokenDto dto) throws BadRequestException {
    return authService.refreshToken(dto);
  }

  @Operation(summary = "Logout your account", description = "Logout your account")
  @DeleteMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    return authService.logout(request);
  }
}
