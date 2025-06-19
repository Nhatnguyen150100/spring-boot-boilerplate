package com.spring.app.modules.auth.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class RefreshTokenDto {
  @NotEmpty(message = "Refresh token is required")
  private String refreshToken;
}
