package com.spring.app.modules.auth.dto.response;

import java.util.UUID;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserResponseDto {
  UUID id;
  String email;
  String fullName;
  String phone;
  String avatarUrl;
  String address;
  String dateOfBirth;
  String status;
  String description;
  String role;
}
