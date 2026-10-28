package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;

import io.jsonwebtoken.security.WeakKeyException;

class JwtServiceTest {

  static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  private final JwtService jwtService = new JwtService(properties(SECRET));

  static JwtProperties properties(String secretKey) {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(secretKey);
    return properties;
  }

  static User user(String email) {
    User user = User.builder().email(email).password("irrelevant").status(EUserStatus.ACTIVE).build();
    user.setId(UUID.randomUUID());
    return user;
  }

  @Test
  void signsAndReadsBackTheSubject() {
    String token = jwtService.generateToken(user("a@example.com"));

    assertThat(jwtService.extractUsername(token)).isEqualTo("a@example.com");
  }

  @Test
  void refusesToStartWithAKeyShorterThan256Bits() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new JwtService(properties(shortKey))).isInstanceOf(WeakKeyException.class);
  }

  @Test
  void decodesTheKeyOnceAtConstruction() {
    JwtProperties properties = properties(SECRET);
    JwtService service = new JwtService(properties);

    // Changing the property afterwards must not change the key in use.
    properties.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));
    String token = service.generateToken(user("a@example.com"));

    assertThat(service.extractUsername(token)).isEqualTo("a@example.com");
    // Verifiable with the ORIGINAL key => it was signed with the original key.
    assertThat(jwtService.extractUsername(token)).isEqualTo("a@example.com");
  }

  @Test
  void accessAndRefreshTokensCarryDifferentTypes() {
    User user = user("a@example.com");
    String access = jwtService.generateToken(user);
    String refresh = jwtService.generateRefreshToken(user);

    assertThat(jwtService.isAccessToken(access)).isTrue();
    assertThat(jwtService.isRefreshToken(access)).isFalse();
    assertThat(jwtService.isRefreshToken(refresh)).isTrue();
    assertThat(jwtService.isAccessToken(refresh)).isFalse();
    assertThat(jwtService.parseClaims(access).get("token_type", String.class)).isEqualTo("access");
  }

  @Test
  void typeChecksReturnFalseInsteadOfThrowing() {
    assertThat(jwtService.isAccessToken("not-a-jwt")).isFalse();
    assertThat(jwtService.isRefreshToken("")).isFalse();
    assertThat(jwtService.isAccessToken(null)).isFalse();
  }

  @Test
  void tokensMintedBackToBackAreDistinct() {
    User user = user("a@example.com");

    assertThat(jwtService.generateRefreshToken(user)).isNotEqualTo(jwtService.generateRefreshToken(user));
  }

  @Test
  void tokensCarryNoEmailOrStatusClaims() {
    User user = user("a@example.com");

    for (String token : List.of(jwtService.generateToken(user), jwtService.generateRefreshToken(user))) {
      assertThat(jwtService.parseClaims(token))
          .as("the e-mail is already the subject; the status is read from the database")
          .doesNotContainKeys("email", "status")
          .containsKeys("id", "role", "sub", "token_type");
    }
  }
}
