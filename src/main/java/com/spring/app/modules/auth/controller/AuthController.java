package com.spring.app.modules.auth.controller;

import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResendOtpRequestDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.services.RateLimitManagerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
  private final RateLimitManagerService rateLimitManager;

  @Operation(summary = "Register a new user", description = "Registers a new user")
  @PostMapping("/register")
  public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDto dto) {
    rateLimitManager.checkRateLimitAndThrow("auth", dto.getEmail());
    return authService.register(dto);
  }

  @Operation(summary = "Resend OTP", description = "Resend OTP to the user's email")
  @PostMapping("/resend-otp")
  public ResponseEntity<?> resendOtp(
      @Valid @RequestBody ResendOtpRequestDto resendOtpRequestDto) {
    rateLimitManager.checkRateLimitAndThrow("auth", resendOtpRequestDto.getEmail());
    return authService.resendOtp(resendOtpRequestDto.getEmail());
  }

  @Operation(summary = "Activate user account", description = "Activate user account with email and OTP")
  @PostMapping("/activate")
  public ResponseEntity<?> activateAccount(@Valid @RequestBody ActiveAccountRequestDto activeAccountRequestDto) {
    rateLimitManager.checkRateLimitAndThrow("auth", activeAccountRequestDto.getEmail());
    return authService.activeAccount(activeAccountRequestDto);
  }

  @Operation(summary = "Login your account", description = "Login your account")
  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto dto) {
    rateLimitManager.checkRateLimitAndThrow("auth", dto.getEmail());
    return authService.login(dto);
  }

  @Operation(summary = "Refresh your token", description = "Refresh your token")
  @PostMapping("/refresh-token")
  public ResponseEntity<?> refresh(@Valid @RequestBody RefreshTokenDto dto) {
    return authService.refreshToken(dto);
  }

  @Operation(summary = "Logout your account", description = "Logout your account")
  @DeleteMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    return authService.logout(request);
  }
}
