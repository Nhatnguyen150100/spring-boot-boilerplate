package com.spring.app.common.validation.validators;

import java.nio.charset.StandardCharsets;

import com.spring.app.common.validation.annotations.MaxUtf8Bytes;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

  private int maxBytes;

  @Override
  public void initialize(MaxUtf8Bytes annotation) {
    this.maxBytes = annotation.value();
  }

  @Override
  public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
    return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
  }
}
