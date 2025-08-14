package com.spring.app.shared.services;

import com.spring.app.configs.properties.RateLimitProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitManagerService {

  private final RedisServiceInterface redisService;
  private final RateLimitProperties rateLimitProperties;

  private static final int DEFAULT_REQUESTS_PER_MINUTE = 60;
  private static final int DEFAULT_REQUESTS_PER_HOUR = 3600;
  private static final int DEFAULT_REQUESTS_PER_DAY = 86400;

  /**
   * Check rate limit for a specific endpoint type
   * 
   * @param endpointType Type of endpoint (auth, upload, api, etc.)
   * @param identifier   Unique identifier (IP, user ID, email, etc.)
   * @return true if allowed, false if rate limit exceeded
   */
  public boolean checkRateLimit(ERateLimitEndpoint endpointType, String identifier) {
    try {
      RateLimitProperties.BaseRateLimitConfig config = getConfigForType(endpointType);
      if (config == null || !config.isEnabled()) {
        return true; // Rate limiting disabled for this type
      }

      String minuteKey = String.format("rate_limit:%s:minute:%s", endpointType, identifier);
      String hourKey = String.format("rate_limit:%s:hour:%s", endpointType, identifier);
      String dayKey = String.format("rate_limit:%s:day:%s", endpointType, identifier);

      return checkRateLimit(minuteKey, config.getRequestsPerMinute(), DEFAULT_REQUESTS_PER_MINUTE) &&
          checkRateLimit(hourKey, config.getRequestsPerHour(), DEFAULT_REQUESTS_PER_HOUR) &&
          checkRateLimit(dayKey, config.getRequestsPerDay(), DEFAULT_REQUESTS_PER_DAY);

    } catch (Exception e) {
      log.error("Error checking rate limit for {} with identifier: {}", endpointType, identifier, e);
      return true; // Allow request on error
    }
  }

  /**
   * Check rate limit and throw exception if exceeded
   * 
   * @param endpointType Type of endpoint
   * @param identifier   Unique identifier
   * @throws RateLimitExceededException if rate limit exceeded
   */
  public void checkRateLimitAndThrow(ERateLimitEndpoint endpointType, String identifier) {
    if (!checkRateLimit(endpointType, identifier)) {
      RateLimitProperties.BaseRateLimitConfig config = getConfigForType(endpointType);
      String message = String.format("Rate limit exceeded for %s. Max %d requests per minute, %d per hour, %d per day",
          endpointType, config.getRequestsPerMinute(), config.getRequestsPerHour(), config.getRequestsPerDay());
      throw new RateLimitExceededException(message);
    }
  }

  /**
   * Get remaining requests for a specific endpoint type
   * 
   * @param endpointType Type of endpoint
   * @param time         Time period (minute, hour, day)
   * @param identifier   Unique identifier
   * @return Number of remaining requests for the current time
   */
  public long getRemainingRequests(ERateLimitEndpoint endpointType, String time, String identifier) {
    try {
      if (time != "minute" && time != "hour" && time != "day") {
        log.warn("Invalid time period: {}. Defaulting to minute.", time);
        time = "minute"; // Default to minute if invalid
      }

      RateLimitProperties.BaseRateLimitConfig config = getConfigForType(endpointType);
      if (config == null)
        return -1;

      int windowSeconds;
      if (time == "minute") {
        windowSeconds = config.getRequestsPerMinute();
      } else if (time == "hour") {
        windowSeconds = config.getRequestsPerHour();
      } else {
        windowSeconds = config.getRequestsPerDay();
      }

      String timeKey = String.format("rate_limit:%s:%s:%s", endpointType, time, identifier);
      String currentCount = (String) redisService.getRateLimitValue(timeKey);

      if (currentCount == null) {
        return windowSeconds;
      }

      int count = Integer.parseInt(currentCount);
      return Math.max(0, windowSeconds - count);

    } catch (Exception e) {
      log.error("Error getting remaining requests for {} in {} with identifier: {}", endpointType, time, identifier, e);
      return -1;
    }
  }

  /**
   * Reset rate limit for a specific identifier
   * 
   * @param endpointType Type of endpoint
   * @param identifier   Unique identifier
   */
  public void resetRateLimit(String endpointType, String identifier) {
    try {
      String minuteKey = String.format("rate_limit:%s:minute:%s", endpointType, identifier);
      String hourKey = String.format("rate_limit:%s:hour:%s", endpointType, identifier);
      String dayKey = String.format("rate_limit:%s:day:%s", endpointType, identifier);

      redisService.deleteRateLimitKey(minuteKey);
      redisService.deleteRateLimitKey(hourKey);
      redisService.deleteRateLimitKey(dayKey);

      log.info("Rate limit reset for {} with identifier: {}", endpointType, identifier);
    } catch (Exception e) {
      log.error("Error resetting rate limit for {} with identifier: {}", endpointType, identifier, e);
    }
  }

  private boolean checkRateLimit(String key, int maxRequests, int windowSeconds) {
    try {
      String currentCount = (String) redisService.getRateLimitValue(key);
      int count = currentCount == null ? 0 : Integer.parseInt(currentCount);

      if (count >= maxRequests) {
        log.warn("Rate limit exceeded for key: {} (count: {}, max: {})", key, count, maxRequests);
        return false;
      }

      // Increment counter
      redisService.setRateLimitValue(key, String.valueOf(count + 1), windowSeconds, TimeUnit.SECONDS);
      return true;

    } catch (Exception e) {
      log.error("Error checking rate limit for key: {}", key, e);
      return true; // Allow request on error
    }
  }

  private RateLimitProperties.BaseRateLimitConfig getConfigForType(ERateLimitEndpoint endpointType) {
    switch (endpointType) {
      case AUTH:
        return rateLimitProperties.getAuth();
      case UPLOAD:
        return rateLimitProperties.getUpload();
      case API:
        return rateLimitProperties.getApi();
      case GLOBAL:
        return rateLimitProperties.getGlobal();
      default:
        log.warn("Unknown endpoint type: {}", endpointType);
        return null;
    }
  }
}
