package com.spring.app.modules.auth.controller;

import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResendOtpRequestDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


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

  @Operation(summary = "Resend OTP", description = "Resend OTP to the user's email")
  @PostMapping("/resend-otp")
  public ResponseEntity<?> resendOtp(
      @Valid @RequestBody ResendOtpRequestDto resendOtpRequestDto) {
    return authService.resendOtp(resendOtpRequestDto.getEmail());
  }

  @Operation(summary = "Activate user account", description = "Activate user account with email and OTP")
  @PostMapping("/activate")
  public ResponseEntity<?> activateAccount(@Valid @RequestBody ActiveAccountRequestDto activeAccountRequestDto) {
    return authService.activeAccount(activeAccountRequestDto);
  }

  @Operation(summary = "Login your account", description = "Login your account")
  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto dto) {
    return authService.login(dto);
  }
  
  // @Operation(summary = "Login with Google callback (redirect)", description = "Login with Google callback (This endpoint is only available for client side)")
  // @GetMapping("/google/callback")
  // public ResponseEntity<?> loginByGoogle(@AuthenticationPrincipal OAuth2User principal) {
  //   return authService.loginByGoogle(principal);
  // }

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
