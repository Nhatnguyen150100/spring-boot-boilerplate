package com.spring.app.modules.auth.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

  @Test
  void trimsAndLowerCases() {
    assertThat(EmailNormalizer.normalize("  User.Name@Example.COM \t")).isEqualTo("user.name@example.com");
  }

  @Test
  void keepsNull() {
    assertThat(EmailNormalizer.normalize(null)).isNull();
  }

  @Test
  void fingerprintIsStableAcrossCasingAndHidesTheAddress() {
    String a = EmailNormalizer.fingerprint("User@Example.com");
    String b = EmailNormalizer.fingerprint(" user@example.com ");

    assertThat(a).isEqualTo(b).matches("[0-9a-f]{64}").doesNotContain("user");
    assertThat(EmailNormalizer.fingerprint("other@example.com")).isNotEqualTo(a);
  }
}
