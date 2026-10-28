package com.spring.app.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.spring.app.shared.interfaces.RedisServiceInterface;

@ExtendWith(MockitoExtension.class)
class OtpFunctionUtilTest {

  @Mock
  private RedisServiceInterface redis;

  private OtpFunctionUtil util;

  @BeforeEach
  void setUp() {
    util = new OtpFunctionUtil(redis);
  }

  @Test
  void generatesSixDigitOtps() {
    for (int i = 0; i < 200; i++) {
      assertThat(util.generateOtp()).matches("\\d{6}");
    }
  }

  @Test
  void generatedOtpsVary() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      seen.add(util.generateOtp());
    }
    assertThat(seen).hasSizeGreaterThan(150);
  }

  @Test
  void rejectsNonPositiveLength() {
    assertThatThrownBy(() -> util.generateOtp(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storesUnderNamespacedLowerCaseKey() {
    util.storeOtp(" Alice@Example.COM ", "123456", 180);

    verify(redis).setValue("otp:alice@example.com", "123456", 180, TimeUnit.SECONDS);
  }

  @Test
  void rejectsNonPositiveTtl() {
    assertThatThrownBy(() -> util.storeOtp("alice@example.com", "123456", 0))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(redis);
  }

  @Test
  void validatesAgainstNamespacedKeyCaseInsensitively() {
    when(redis.getValue("otp:alice@example.com")).thenReturn("123456");

    assertThat(util.validateOtp("ALICE@example.com", "123456")).isTrue();
    assertThat(util.validateOtp("alice@example.com", "654321")).isFalse();
  }

  @Test
  void missingOtpIsInvalid() {
    when(redis.getValue("otp:alice@example.com")).thenReturn(null);

    assertThat(util.validateOtp("alice@example.com", "123456")).isFalse();
  }

  @Test
  void malformedOtpIsRejectedWithoutTouchingRedis() {
    assertThat(util.validateOtp("alice@example.com", "12ab56")).isFalse();
    assertThat(util.validateOtp("alice@example.com", null)).isFalse();
    verifyNoInteractions(redis);
  }

  @Test
  void removeDeletesNamespacedKey() {
    util.removeOtp("Alice@Example.com");

    verify(redis).delete("otp:alice@example.com");
  }
}
