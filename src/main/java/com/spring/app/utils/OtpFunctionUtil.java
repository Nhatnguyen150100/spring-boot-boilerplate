package com.spring.app.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;

/**
 * Generates, stores and checks one-time passwords.
 *
 * <p>
 * OTPs are stored in Redis under {@code otp:<lower-cased, trimmed e-mail>}:
 * namespaced so they cannot collide with other keys, and case-insensitive like
 * the MySQL e-mail lookup. Every public method takes the e-mail address, never
 * a raw Redis key.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OtpFunctionUtil {

  public static final int OTP_LENGTH = 6;
  static final String KEY_PREFIX = "otp:";

  private static final Pattern OTP_PATTERN = Pattern.compile("\\d{" + OTP_LENGTH + "}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final RedisServiceInterface redisService;

  /**
   * Generates a random numeric OTP of the given length using a CSPRNG.
   *
   * @param length number of digits, at least 1
   * @return the OTP
   */
  public String generateOtp(int length) {
    if (length < 1) {
      throw new IllegalArgumentException("OTP length must be at least 1");
    }
    StringBuilder otp = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      otp.append(RANDOM.nextInt(10));
    }
    return otp.toString();
  }

  /** Generates a {@value #OTP_LENGTH}-digit OTP. */
  public String generateOtp() {
    return generateOtp(OTP_LENGTH);
  }

  /** Redis key holding the OTP for {@code email}. */
  static String otpKey(String email) {
    Objects.requireNonNull(email, "Email must not be null");
    return KEY_PREFIX + email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Stores the OTP for {@code email}, replacing any previous one.
   *
   * @param email                   the address the OTP was sent to
   * @param otp                     the OTP
   * @param expirationTimeInSeconds TTL, must be positive
   */
  public void storeOtp(String email, String otp, long expirationTimeInSeconds) {
    Objects.requireNonNull(otp, "OTP must not be null");
    if (expirationTimeInSeconds <= 0) {
      throw new IllegalArgumentException("Expiration time must be positive");
    }
    redisService.setValue(otpKey(email), otp, expirationTimeInSeconds, TimeUnit.SECONDS);
  }

  /** @return the stored OTP for {@code email}, or {@code null} if none / expired */
  public String retrieveOtp(String email) {
    Object stored = redisService.getValue(otpKey(email));
    return stored instanceof String otp ? otp : null;
  }

  /** Deletes the OTP for {@code email}. */
  public void deleteOtp(String email) {
    redisService.delete(otpKey(email));
  }

  /**
   * Checks {@code otp} against the stored OTP for {@code email}. Malformed input
   * is rejected before Redis is read; the comparison is constant-time.
   */
  public boolean validateOtp(String email, String otp) {
    if (otp == null || !OTP_PATTERN.matcher(otp).matches()) {
      return false;
    }
    String stored = retrieveOtp(email);
    return stored != null
        && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), otp.getBytes(StandardCharsets.UTF_8));
  }

  /** Removes the OTP after successful activation / password reset. */
  public void removeOtp(String email) {
    deleteOtp(email);
  }
}
