package com.spring.app.utils;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class OtpFunctionUtil {

  private final RedisServiceInterface redisService;

  /**
   * Generates a random OTP (One Time Password) of the specified length.
   *
   * @param length The length of the OTP to be generated.
   * @return A string representing the generated OTP.
   */
  public String generateOtp(int length) {
    StringBuilder otp = new StringBuilder();
    for (int i = 0; i < length; i++) {
      otp.append((int) (Math.random() * 10));
    }
    return otp.toString();
  }

  /**
   * Generates a random OTP (One Time Password) of length 6 digits.
   * 
   * @return A string representing the generated OTP.
   */
  public String generateOtp() {
    return generateOtp(6); // Default OTP length is 6 digits
  }

  /**
   * Stores the OTP in Redis with a specified key and expiration time.
   *
   * @param key                     The key under which the OTP will be stored.
   * @param otp                     The OTP to be stored.
   * @param expirationTimeInSeconds The expiration time for the OTP in seconds.
   */
  public void storeOtp(String key, String otp, long expirationTimeInSeconds) {
    Objects.requireNonNull(key, "Key must not be null");
    Objects.requireNonNull(otp, "OTP must not be null");
    if (expirationTimeInSeconds <= 0) {
      throw new IllegalArgumentException("Expiration time must be positive");
    }
    redisService.setValue(key, otp, expirationTimeInSeconds, TimeUnit.SECONDS);
  }

  /**
   * Retrieves the OTP from Redis using the specified key.
   *
   * @param key The key under which the OTP is stored.
   * @return The OTP if found, null otherwise.
   */
  public String retrieveOtp(String key) {
    return (String) redisService.getValue(key);
  }

  /**
   * Deletes the OTP from Redis using the specified key.
   *
   * @param key The key under which the OTP is stored.
   */
  public void deleteOtp(String key) {
    redisService.delete(key);
  }

  /**
   * Validates the OTP against the stored value in Redis.
   *
   * @param key The key under which the OTP is stored.
   * @param otp The OTP to be validated.
   * @return True if the OTP matches the stored value, false otherwise.
   */
  public boolean validateOtp(String key, String otp) {
    String storedOtp = retrieveOtp(key);
    return storedOtp != null && storedOtp.equals(otp) && validateOtp(otp);
  }

  /**
   * Validates the OTP against a predefined pattern.
   *
   * @param otp The OTP to be validated.
   * @return True if the OTP is valid, false otherwise.
   */
  private boolean validateOtp(String otp) {
    return otp.matches("\\d{6}");
  }
}
