package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.StrongPassword;
import com.spring.app.common.validation.annotations.ValidEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record ResetPasswordRequestDto(
  @ValidEmail
  @Schema(description = "Registered email address", example = "user@example.com")
  String email,

  @NotBlank(message = "OTP is required")
  @Size(min = 6, max = 6, message = "OTP must be exactly 6 characters")
  @Schema(description = "OTP received via email", example = "123456")
  String otp,

  @NotBlank(message = "New password is required")
  @StrongPassword
  @Schema(description = "New password", example = "NewStrongP@ss123")
  String newPassword
) {}
