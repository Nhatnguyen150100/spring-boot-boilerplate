package com.spring.app.common.validation.annotations;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.*;

/**
 * A person's name: starts with a letter, then any Unicode letters (and
 * combining marks), spaces, apostrophes (' and U+2019), hyphens and dots.
 * Accepts "Nguyễn Văn A", "O'Brien", "Jean-Luc", "J. R. R. Tolkien".
 * {@code null} is valid; add {@code @NotBlank} where the name is required.
 */
@Documented
@Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
@Pattern(regexp = "^[\\p{L}\\p{M}][\\p{L}\\p{M} '\\u2019.\\-]*$",
    message = "Full name may only contain letters, spaces, apostrophes, hyphens and dots")
@Constraint(validatedBy = {})
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFullName {

  String message() default "Full name is not valid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
