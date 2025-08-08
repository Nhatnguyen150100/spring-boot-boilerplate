package com.spring.app.common.validation.validators;

import com.spring.app.common.validation.annotations.StrongPassword;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

  @Override
  public boolean isValid(String password, ConstraintValidatorContext context) {
    if (password == null) {
      return false;
    }

    // At least 8 characters
    if (password.length() < 8) {
      return false;
    }

    // Contains at least one uppercase letter
    if (!password.matches(".*[A-Z].*")) {
      return false;
    }

    // Contains at least one lowercase letter
    if (!password.matches(".*[a-z].*")) {
      return false;
    }

    // Contains at least one digit
    if (!password.matches(".*\\d.*")) {
      return false;
    }

    // Contains at least one special character
    if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
      return false;
    }

    return true;
  }
}