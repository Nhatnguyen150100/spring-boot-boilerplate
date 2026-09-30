package com.spring.app.common.validation.annotations;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.annotation.*;

@Documented
@NotBlank(message = "Email is required")
@Email(message = "Email format is not valid")
@Size(max = 255, message = "Email must be less than 255 characters")
@Constraint(validatedBy = {})
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmail {

  /** Unused unless {@code @ReportAsSingleViolation} is added -- see above. */
  String message() default "Email is not valid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
