package com.example.demo.modules.auth.dto.request;

import lombok.*;

@Getter
public class RegisterRequestDto extends AuthRequestDto {
  private String fullName;
}
