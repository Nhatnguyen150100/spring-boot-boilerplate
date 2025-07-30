package com.spring.app.modules.user.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
@Schema(description = "Data Transfer Object for updating user profile information")
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateUserDto {
  @Schema(description = "Full name of user", example = "John Doe")
  String fullName;

  @Schema(description = "Phone number", example = "0987654321")
  String phone;

  @Schema(description = "Avatar URL", example = "https://example.com/avatar.jpg")
  String avatarUrl;

  @Schema(description = "Address", example = "123 Main St, City, Country")
  String address;

  @Schema(description = "Date of birth", example = "1990-01-01")
  String dateOfBirth;

  @Schema(description = "Description", example = "I'm a software engineer")
  String description;
}
