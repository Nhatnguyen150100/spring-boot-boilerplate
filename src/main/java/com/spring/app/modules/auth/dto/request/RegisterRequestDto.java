package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequestDto(

    @Email(message = "Email format is not valid") @Size(max = 255, message = "Email must be less than 255 characters") @NotBlank(message = "Email is required") @Schema(description = "Email address of the user", example = "user1@gmail.com")
    String email,

    @NotBlank(message = "Password is required") @StrongPassword @Schema(description = "Password of the user", example = "StrongP@ss123")
    String password,

    @NotBlank(message = "Full name is required") @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters") @Pattern(regexp = "^[a-zA-Z\\s]+$", message = "Full name can only contain letters and spaces") @Schema(description = "Full name of the user", example = "John Doe")
    String fullName,

    @Size(max = 15, message = "Phone number must be less than 15 characters") @Pattern(regexp = "^[+]?[0-9\\s\\-()]+$", message = "Invalid phone number format") @Schema(description = "Phone number of the user", example = "+1234567890")
    String phone,

    @Size(max = 255, message = "Address must be less than 255 characters") @Schema(description = "Address of the user", example = "123 Main St, City, Country")
    String address

) {
}