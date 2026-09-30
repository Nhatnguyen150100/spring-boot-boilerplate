package com.spring.app.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.spring.app.modules.auth.dto.request.ForgotPasswordRequestDto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Proves the composed {@code @ValidEmail} constraint really fires -- a
 * composed annotation compiles fine even when nothing picks it up, so the
 * only meaningful check is running a validator against it.
 *
 * <p>
 * Uses a standalone {@code Validator} rather than {@code @SpringBootTest}:
 * the constraint has no Spring dependency, so booting a context would only
 * make the test slower.
 * </p>
 */
class ValidEmailTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private Set<ConstraintViolation<ForgotPasswordRequestDto>> validate(String email) {
    return validator.validate(ForgotPasswordRequestDto.builder().email(email).build());
  }

  @Test
  void acceptsAWellFormedAddress() {
    assertThat(validate("user1@gmail.com")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = { "not-an-email", "missing-at.example.com", "spaces in@example.com" })
  void rejectsAMalformedAddress(String email) {
    assertThat(validate(email))
        .extracting(ConstraintViolation::getMessage)
        .contains("Email format is not valid");
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   " })
  void rejectsABlankAddress(String email) {
    assertThat(validate(email))
        .extracting(ConstraintViolation::getMessage)
        .contains("Email is required");
  }

  @Test
  void rejectsNull() {
    assertThat(validate(null))
        .extracting(ConstraintViolation::getMessage)
        .contains("Email is required");
  }

  /** 256 characters: one past the {@code users.email VARCHAR(255)} column. */
  @Test
  void rejectsAnAddressLongerThanTheColumn() {
    String tooLong = "a".repeat(245) + "@gmail.com";
    assertThat(tooLong).hasSize(255);

    assertThat(validate(tooLong + "x"))
        .extracting(ConstraintViolation::getMessage)
        .contains("Email must be less than 255 characters");
  }

  /**
   * Each composing constraint reports separately, so a blank value fails
   * {@code @NotBlank} and {@code @Email} at once. Collapsing these into one
   * message is what {@code @ReportAsSingleViolation} would do -- this test
   * pins the current, deliberate behaviour.
   */
  @Test
  void reportsEachComposingConstraintSeparately() {
    assertThat(validate("   "))
        .extracting(ConstraintViolation::getMessage)
        .containsExactlyInAnyOrder("Email is required", "Email format is not valid");
  }
}
