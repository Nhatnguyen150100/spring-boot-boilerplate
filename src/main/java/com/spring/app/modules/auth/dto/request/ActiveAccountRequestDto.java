package com.spring.app.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ActiveAccountRequestDto {
  @Schema(description = "Email address of the user", example = "user1@gmail.com")
  @Email(message = "Email is not valid", regexp = "^[a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$")
  @NotBlank(message = "Email is required")
  @NotEmpty(message = "Email cannot be empty")
  private String email;

  @Schema(description = "One-Time Password (OTP) for account activation", example = "123456")
  @NotBlank(message = "OTP is required")
  @NotEmpty(message = "OTP cannot be empty")
  @Size(min = 6, max = 6, message = "OTP must be exactly 6 characters")
  private String otp;
}
