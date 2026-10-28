package com.spring.app.modules.auth.services;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.modules.auth.utils.EmailNormalizer;
import com.spring.app.shared.services.OtpEmailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Caps wrong guesses per OTP: counts wrong OTP verifications per (e-mail,
 * purpose) in Redis, and reports when the cap is reached so the caller
 * discards the OTP. A 6-digit OTP then survives at most
 * {@code application.auth.otp-max-attempts} guesses, however many IPs they
 * come from, without a per-e-mail throttle that would let a stranger block
 * the owner's own verification.
 *
 * <p>
 * Counters live as long as an OTP ({@link OtpEmailService#OTP_EXPIRATION_SECONDS});
 * the e-mail is hashed so addresses never appear in Redis keys. Redis errors
 * fail open (the guess is simply not counted), like {@link LoginAttemptService}.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpAttemptService {

  /** Counter purpose of account activation OTPs. */
  public static final String PURPOSE_ACTIVATION = "activation";
  /** Counter purpose of password-reset OTPs. */
  public static final String PURPOSE_PASSWORD_RESET = "password_reset";

  private static final String KEY_PREFIX = "auth:otp-failures:";

  /** INCR and arm the TTL in one round trip, so the key can never lose its expiry. */
  private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
      "local count = redis.call('INCR', KEYS[1]) "
          + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
          + "return count",
      Long.class);

  private static final String COUNTER_TTL_MILLIS =
      String.valueOf(TimeUnit.SECONDS.toMillis(OtpEmailService.OTP_EXPIRATION_SECONDS));

  private final StringRedisTemplate redisTemplate;
  private final AuthProperties authProperties;

  /**
   * Counts one wrong OTP. Returns {@code true} when this guess reached the cap:
   * the counter is cleared and the caller must discard the OTP.
   */
  public boolean recordFailure(String email, String purpose) {
    String key = key(email, purpose);
    try {
      Long failures = redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key), COUNTER_TTL_MILLIS);
      if (failures == null || failures < authProperties.getOtpMaxAttempts()) {
        return false;
      }
      redisTemplate.delete(key);
      return true;
    } catch (RuntimeException e) {
      log.warn("OTP attempt counter unavailable ({}); the guess is not counted", e.getClass().getSimpleName());
      return false;
    }
  }

  /** Called after a correct OTP. */
  public void reset(String email, String purpose) {
    try {
      redisTemplate.delete(key(email, purpose));
    } catch (RuntimeException e) {
      log.warn("Could not clear the OTP attempt counter ({})", e.getClass().getSimpleName());
    }
  }

  /**
   * Called once a new OTP has been stored. There is one OTP per e-mail, so the
   * new one replaces any earlier OTP of either purpose: both counters start
   * over. Never called when the store failed, so a still-live old OTP keeps
   * its count.
   */
  public void resetAll(String email) {
    try {
      redisTemplate.delete(List.of(key(email, PURPOSE_ACTIVATION), key(email, PURPOSE_PASSWORD_RESET)));
    } catch (RuntimeException e) {
      log.warn("Could not clear the OTP attempt counters ({})", e.getClass().getSimpleName());
    }
  }

  static String key(String email, String purpose) {
    return KEY_PREFIX + purpose + ":" + EmailNormalizer.fingerprint(email);
  }
}
