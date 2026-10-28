package com.spring.app.modules.auth.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Canonical form of an e-mail address for every auth entry point, so that
 * lookups, OTP keys and throttle keys agree whatever casing the client sent.
 */
public final class EmailNormalizer {

  private EmailNormalizer() {
  }

  /** {@code null} stays {@code null}; otherwise trimmed and lower-cased (Locale.ROOT). */
  public static String normalize(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * SHA-256 (hex) of the normalised address. Used wherever an e-mail would
   * otherwise end up in a Redis key or a log line.
   */
  public static String fingerprint(String email) {
    String normalized = normalize(email);
    if (normalized == null) {
      return "none";
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
