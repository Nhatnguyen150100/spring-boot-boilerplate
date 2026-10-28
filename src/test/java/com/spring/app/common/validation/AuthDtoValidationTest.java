package com.spring.app.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class AuthDtoValidationTest {

  private static final String NAME_MESSAGE = "Full name may only contain letters, spaces, apostrophes, hyphens and dots";
  private static final String BYTES_MESSAGE = "Password must be at most 72 bytes";
  private static final String AVATAR_MESSAGE = "Avatar URL must be a valid http(s) URL";

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

  private static <T> Set<String> messages(T dto) {
    return validator.validate(dto).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
  }

  private static RegisterRequestDto register(String fullName, String password) {
    return new RegisterRequestDto("user@example.com", password, fullName, null, null);
  }

  @ParameterizedTest
  @ValueSource(strings = { "Nguyễn Văn A", "O'Brien", "Jean-Luc Picard", "J. R. R. Tolkien", "Zoë Saldaña", "D’Angelo" })
  void acceptsRealWorldNames(String name) {
    assertThat(messages(register(name, "Str0ng!Pass"))).isEmpty();
    assertThat(messages(UpdateUserDto.builder().fullName(name).build())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = { "John123", "<script>", "-John", "Robert'); DROP TABLE users;--" })
  void rejectsNamesWithDigitsOrMarkup(String name) {
    assertThat(messages(register(name, "Str0ng!Pass"))).contains(NAME_MESSAGE);
    assertThat(messages(UpdateUserDto.builder().fullName(name).build())).contains(NAME_MESSAGE);
  }

  @Test
  void passwordsAreCappedAt72BytesNot72Characters() {
    String seventyTwoBytes = "Aa1!" + "a".repeat(68);
    String seventyFourBytes = "Aa1!" + "ä".repeat(35); // 39 characters, 74 UTF-8 bytes
    assertThat(seventyFourBytes).hasSize(39);

    assertThat(messages(register("Valid Name", seventyTwoBytes))).isEmpty();
    assertThat(messages(register("Valid Name", seventyFourBytes))).containsExactly(BYTES_MESSAGE);
    assertThat(messages(new ResetPasswordRequestDto("user@example.com", "123456", seventyFourBytes)))
        .containsExactly(BYTES_MESSAGE);
    assertThat(messages(new LoginRequestDto("user@example.com", "a".repeat(73)))).containsExactly(BYTES_MESSAGE);
  }

  @Test
  void loginDoesNotEnforceThePasswordPolicy() {
    assertThat(messages(new LoginRequestDto("user@example.com", "weak"))).isEmpty();
  }

  @Test
  void profileUpdateAcceptsHttpAvatars() {
    assertThat(messages(UpdateUserDto.builder().avatarUrl("https://cdn.example.com/a.png").build())).isEmpty();
    assertThat(messages(UpdateUserDto.builder().avatarUrl("http://cdn.example.com/a.png").build())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = { "javascript:alert(1)", "ftp://example.com/a.png", "not a url" })
  void profileUpdateRejectsNonHttpAvatarUrls(String url) {
    assertThat(messages(UpdateUserDto.builder().avatarUrl(url).build())).contains(AVATAR_MESSAGE);
  }
}
