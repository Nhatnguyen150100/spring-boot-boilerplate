package com.spring.app.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequestDto extends AuthRequestDto {

  @NotBlank(message = "Full name is required")
  @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
  @Pattern(regexp = "^[a-zA-Z\\s]+$", message = "Full name can only contain letters and spaces")
  @Schema(description = "Full name of the user", example = "John Doe")
  private String fullName;

  @Size(max = 15, message = "Phone number must be less than 15 characters")
  @Pattern(regexp = "^[+]?[0-9\\s\\-()]+$", message = "Invalid phone number format")
  @Schema(description = "Phone number of the user", example = "+1234567890")
  private String phone;

  @Size(max = 255, message = "Address must be less than 255 characters")
  @Schema(description = "Address of the user", example = "123 Main St, City, Country")
  private String address;
}
