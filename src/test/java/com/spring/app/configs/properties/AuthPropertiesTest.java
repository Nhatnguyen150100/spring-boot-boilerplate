package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class AuthPropertiesTest {

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

  private Set<String> violations(AuthProperties properties) {
    return validator.validate(properties).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
  }

  @Test
  void defaultsAreValid() {
    assertThat(violations(new AuthProperties())).isEmpty();
  }

  @Test
  void atLeastOneFailureMustBeAllowed() {
    AuthProperties properties = new AuthProperties();
    properties.setLoginMaxFailures(0);

    assertThat(violations(properties)).containsExactly("application.auth.login-max-failures must be >= 1");
  }

  @Test
  void theFailureWindowMustBePositive() {
    AuthProperties properties = new AuthProperties();
    properties.setLoginFailureWindow(Duration.ZERO);

    assertThat(violations(properties)).containsExactly("application.auth.login-failure-window must be at least 1s");
  }

  @Test
  void theOAuth2RedirectPathMustBeAbsolute() {
    AuthProperties properties = new AuthProperties();
    properties.setOauth2RedirectPath("callback");

    assertThat(violations(properties)).containsExactly("application.auth.oauth2-redirect-path must start with '/'");
  }
}
