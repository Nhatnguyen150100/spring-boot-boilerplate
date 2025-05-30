package com.spring.app.modules.auth.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponseDto {
  private String accessToken;
  private String refreshToken;
  private UserResponseDto userResponseDto;
}
