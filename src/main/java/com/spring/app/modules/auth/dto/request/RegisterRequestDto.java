package com.spring.app.modules.auth.dto.request;

import lombok.*;

@Getter
public class RegisterRequestDto extends AuthRequestDto {
  private String fullName;
}
