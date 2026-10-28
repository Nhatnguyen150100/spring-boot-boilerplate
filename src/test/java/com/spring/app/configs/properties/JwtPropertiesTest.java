package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class JwtPropertiesTest {

  private static final String STRONG_KEY_MESSAGE =
      "application.security.jwt.secret-key must be Base64 and decode to at least 256 bits (32 bytes)";

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

  private Set<String> violations(String secretKey) {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(secretKey);
    return validator.validate(properties).stream()
        .map(ConstraintViolation::getMessage)
        .collect(Collectors.toSet());
  }

  @Test
  void acceptsTheTestProfileKey() {
    assertThat(violations("dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==")).isEmpty();
  }

  @Test
  void acceptsExactly256Bits() {
    assertThat(violations(Base64.getEncoder().encodeToString(new byte[32]))).isEmpty();
  }

  @Test
  void rejectsAKeyThatIsNotBase64() {
    assertThat(violations("not base64 !!")).containsExactly(STRONG_KEY_MESSAGE);
  }

  @Test
  void rejectsAKeyShorterThan256Bits() {
    assertThat(violations(Base64.getEncoder().encodeToString(new byte[31]))).containsExactly(STRONG_KEY_MESSAGE);
  }

  @Test
  void blankKeyReportsOnlyTheRequiredMessage() {
    assertThat(violations("   ")).containsExactly("application.security.jwt.secret-key is required");
  }
}
