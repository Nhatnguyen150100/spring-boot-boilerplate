package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthRequestDto {
    
    @Email(message = "Email format is not valid")
    @Size(max = 255, message = "Email must be less than 255 characters")
    @NotBlank(message = "Email is required")
    @Schema(description = "Email address of the user", example = "user1@gmail.com")
    private String email;

    @NotBlank(message = "Password is required")
    @StrongPassword
    @Schema(description = "Password of the user", example = "StrongP@ss123")
    private String password;
}
