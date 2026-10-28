package com.spring.app.shared.services;

import com.spring.app.configs.properties.RateLimitProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitManagerService {

  private final RedisServiceInterface redisService;
  private final RateLimitProperties rateLimitProperties;

  // Fixed-window lengths, in seconds, for the three quotas of every bucket.
  private static final int MINUTE_WINDOW_SECONDS = 60;
  private static final int HOUR_WINDOW_SECONDS = 3600;
  private static final int DAY_WINDOW_SECONDS = 86400;

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

      return checkRateLimit(minuteKey, config.getRequestsPerMinute(), MINUTE_WINDOW_SECONDS) &&
          checkRateLimit(hourKey, config.getRequestsPerHour(), HOUR_WINDOW_SECONDS) &&
          checkRateLimit(dayKey, config.getRequestsPerDay(), DAY_WINDOW_SECONDS);

    } catch (Exception e) {
      log.error("Error checking rate limit for {} with identifier: {}", endpointType, maskIdentifier(identifier), e);
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
      if (!"minute".equals(time) && !"hour".equals(time) && !"day".equals(time)) {
        log.warn("Invalid time period: {}. Defaulting to minute.", time);
        time = "minute"; // Default to minute if invalid
      }

      RateLimitProperties.BaseRateLimitConfig config = getConfigForType(endpointType);
      if (config == null)
        return -1;

      int maxRequests;
      if ("minute".equals(time)) {
        maxRequests = config.getRequestsPerMinute();
      } else if ("hour".equals(time)) {
        maxRequests = config.getRequestsPerHour();
      } else {
        maxRequests = config.getRequestsPerDay();
      }

      String timeKey = String.format("rate_limit:%s:%s:%s", endpointType, time, identifier);
      String currentCount = (String) redisService.getRateLimitValue(timeKey);

      if (currentCount == null) {
        return maxRequests;
      }

      int count = Integer.parseInt(currentCount);
      return Math.max(0, maxRequests - count);

    } catch (Exception e) {
      log.error("Error getting remaining requests for {} in {} with identifier: {}", endpointType, time,
          maskIdentifier(identifier), e);
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

      log.info("Rate limit reset for {} with identifier: {}", endpointType, maskIdentifier(identifier));
    } catch (Exception e) {
      log.error("Error resetting rate limit for {} with identifier: {}", endpointType, maskIdentifier(identifier), e);
    }
  }

  /**
   * Hides the local part of an e-mail inside a rate-limit identifier or key
   * ({@code rate_limit:AUTH:minute:email:john@x.com} becomes
   * {@code rate_limit:AUTH:minute:email:j***@x.com}). Values without an
   * {@code @} (IP addresses, hashed identifiers) are returned unchanged. Use it
   * for every log line that prints an identifier or key.
   */
  static String maskIdentifier(String identifier) {
    if (identifier == null) {
      return null;
    }
    int at = identifier.indexOf('@');
    if (at < 0) {
      return identifier;
    }
    int localStart = identifier.lastIndexOf(':', at) + 1;
    String local = identifier.substring(localStart, at);
    String visible = local.isEmpty() ? "" : local.substring(0, 1);
    return identifier.substring(0, localStart) + visible + "***" + identifier.substring(at);
  }

  private boolean checkRateLimit(String key, int maxRequests, int windowSeconds) {
    try {
      // Atomic increment avoids the read-then-write race under concurrency, and the
      // window TTL is set only when the counter is first created (fixed window).
      long count = redisService.incrementRateLimit(key, windowSeconds);

      if (count > maxRequests) {
        // DEBUG: the resulting 429 is already logged at WARN by GlobalExceptionHandler.
        log.debug("Rate limit exceeded for key: {} (count: {}, max: {})", maskIdentifier(key), count, maxRequests);
        return false;
      }
      return true;

    } catch (Exception e) {
      log.error("Error checking rate limit for key: {}", maskIdentifier(key), e);
      return true; // Allow request on error
    }
  }

  private RateLimitProperties.BaseRateLimitConfig getConfigForType(ERateLimitEndpoint endpointType) {
    return switch (endpointType) {
      case AUTH -> rateLimitProperties.getAuth();
      case UPLOAD -> rateLimitProperties.getUpload();
      case API -> rateLimitProperties.getApi();
      case GLOBAL -> rateLimitProperties.getGlobal();
    };
  }
}
