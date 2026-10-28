package com.spring.app.common.validation.annotations;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

import com.spring.app.common.validation.validators.MaxUtf8BytesValidator;

/**
 * Caps a string by its UTF-8 byte length rather than its character count.
 * BCrypt only accepts 72 bytes, and one character can take up to 4 bytes, so
 * {@code @Size(max = 72)} would let a 40-character password with accents
 * through and make the encoder throw (500). {@code null} is valid.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

  int value();

  String message() default "must be at most {value} bytes when UTF-8 encoded";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
