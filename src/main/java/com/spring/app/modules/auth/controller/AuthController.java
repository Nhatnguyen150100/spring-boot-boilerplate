package com.spring.app.modules.auth.controller;

import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.ForgotPasswordRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResendOtpRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.constants.ApplicationConstants;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApplicationConstants.AUTH_BASE_PATH)
@Tag(name = "Authentication", description = "APIs for user authentication and registration")
@RequiredArgsConstructor
public class AuthController {

  private final AuthServiceInterface authService;

  @Operation(summary = "Register a new user")
  @PostMapping("/register")
  public ResponseEntity<?> register(@Valid @RequestBody RegisterRequestDto dto) {
    return authService.register(dto);
  }

  @Operation(summary = "Resend OTP to the user's email")
  @PostMapping("/resend-otp")
  public ResponseEntity<?> resendOtp(@Valid @RequestBody ResendOtpRequestDto resendOtpRequestDto) {
    return authService.resendOtp(resendOtpRequestDto.email());
  }

  @Operation(summary = "Activate user account with email and OTP")
  @PostMapping("/activate")
  public ResponseEntity<?> activateAccount(@Valid @RequestBody ActiveAccountRequestDto activeAccountRequestDto) {
    return authService.activeAccount(activeAccountRequestDto);
  }

  @Operation(summary = "Login")
  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto dto) {
    return authService.login(dto);
  }

  @Operation(summary = "Refresh access token")
  @PostMapping("/refresh-token")
  public ResponseEntity<?> refresh(@Valid @RequestBody RefreshTokenDto dto) {
    return authService.refreshToken(dto);
  }

  @Operation(summary = "Logout and invalidate tokens")
  @DeleteMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    return authService.logout(request);
  }

  @Operation(summary = "Request a password reset OTP")
  @PostMapping("/forgot-password")
  public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto dto) {
    return authService.forgotPassword(dto.email());
  }

  @Operation(summary = "Reset password using OTP")
  @PostMapping("/reset-password")
  public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequestDto dto) {
    return authService.resetPassword(dto);
  }
}
