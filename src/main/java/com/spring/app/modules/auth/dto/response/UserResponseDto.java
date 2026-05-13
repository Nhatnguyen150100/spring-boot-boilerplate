package com.spring.app.modules.auth.dto.response;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

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
  @JsonFormat(pattern = "yyyy-MM-dd")
  LocalDate dateOfBirth;
  String status;
  String description;
  String role;
  String createdAt;
  String updatedAt;
}
