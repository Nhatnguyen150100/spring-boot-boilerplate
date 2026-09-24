package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.ValidEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActiveAccountRequestDto(
  @ValidEmail
  @Schema(description = "Email address of the user", example = "user1@gmail.com")
  String email,

  @Schema(description = "One-Time Password (OTP) for account activation", example = "123456")
  @NotBlank(message = "OTP is required")
  @Size(min = 6, max = 6, message = "OTP must be exactly 6 characters")
  String otp
) {
}
