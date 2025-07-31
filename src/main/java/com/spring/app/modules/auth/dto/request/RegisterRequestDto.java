package com.spring.app.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Getter
public class RegisterRequestDto extends AuthRequestDto {
  @Schema(description = "Full name of the user", example = "John Doe")
  private String fullName;
}
