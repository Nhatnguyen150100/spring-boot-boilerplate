package com.spring.app.shared.services;

import com.spring.app.configs.properties.RateLimitProperties;
import com.spring.app.exceptions.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitManagerService {

  private final RedisTemplate<String, String> rateLimitRedisTemplate;
  private final RateLimitProperties rateLimitProperties;

  /**
   * Check rate limit for a specific endpoint type
   * 
   * @param endpointType Type of endpoint (auth, upload, api, etc.)
   * @param identifier   Unique identifier (IP, user ID, email, etc.)
   * @return true if allowed, false if rate limit exceeded
   */
  public boolean checkRateLimit(String endpointType, String identifier) {
    try {
      RateLimitProperties.RateLimitConfig config = getConfigForType(endpointType);
      if (config == null || !config.isEnabled()) {
        return true; // Rate limiting disabled for this type
      }

      String minuteKey = String.format("rate_limit:%s:minute:%s", endpointType, identifier);
      String hourKey = String.format("rate_limit:%s:hour:%s", endpointType, identifier);
      String dayKey = String.format("rate_limit:%s:day:%s", endpointType, identifier);

      return checkRateLimit(minuteKey, config.getRequestsPerMinute(), 60) &&
          checkRateLimit(hourKey, config.getRequestsPerHour(), 3600) &&
          checkRateLimit(dayKey, config.getRequestsPerDay(), 86400);

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
  public void checkRateLimitAndThrow(String endpointType, String identifier) {
    if (!checkRateLimit(endpointType, identifier)) {
      RateLimitProperties.RateLimitConfig config = getConfigForType(endpointType);
      String message = String.format("Rate limit exceeded for %s. Max %d requests per minute, %d per hour, %d per day",
          endpointType, config.getRequestsPerMinute(), config.getRequestsPerHour(), config.getRequestsPerDay());
      throw new RateLimitExceededException(message);
    }
  }

  /**
   * Get remaining requests for a specific endpoint type
   * 
   * @param endpointType Type of endpoint
   * @param identifier   Unique identifier
   * @return Number of remaining requests for the current minute
   */
  public long getRemainingRequests(String endpointType, String identifier) {
    try {
      RateLimitProperties.RateLimitConfig config = getConfigForType(endpointType);
      if (config == null)
        return -1;

      String minuteKey = String.format("rate_limit:%s:minute:%s", endpointType, identifier);
      String currentCount = rateLimitRedisTemplate.opsForValue().get(minuteKey);

      if (currentCount == null) {
        return config.getRequestsPerMinute();
      }

      int count = Integer.parseInt(currentCount);
      return Math.max(0, config.getRequestsPerMinute() - count);

    } catch (Exception e) {
      log.error("Error getting remaining requests for {} with identifier: {}", endpointType, identifier, e);
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

      rateLimitRedisTemplate.delete(minuteKey);
      rateLimitRedisTemplate.delete(hourKey);
      rateLimitRedisTemplate.delete(dayKey);

      log.info("Rate limit reset for {} with identifier: {}", endpointType, identifier);
    } catch (Exception e) {
      log.error("Error resetting rate limit for {} with identifier: {}", endpointType, identifier, e);
    }
  }

  /**
   * Get client IP address from current request
   * 
   * @return Client IP address
   */
  public String getClientIpAddress() {
    try {
      ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (attributes != null) {
        HttpServletRequest request = attributes.getRequest();

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
          return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
          return xRealIp;
        }

        return request.getRemoteAddr();
      }
    } catch (Exception e) {
      log.error("Error getting client IP address", e);
    }
    return "unknown";
  }

  private boolean checkRateLimit(String key, int maxRequests, int windowSeconds) {
    try {
      String currentCount = rateLimitRedisTemplate.opsForValue().get(key);
      int count = currentCount == null ? 0 : Integer.parseInt(currentCount);

      if (count >= maxRequests) {
        log.warn("Rate limit exceeded for key: {} (count: {}, max: {})", key, count, maxRequests);
        return false;
      }

      // Increment counter
      rateLimitRedisTemplate.opsForValue().set(key, String.valueOf(count + 1), windowSeconds, TimeUnit.SECONDS);
      return true;

    } catch (Exception e) {
      log.error("Error checking rate limit for key: {}", key, e);
      return true; // Allow request on error
    }
  }

  private RateLimitProperties.RateLimitConfig getConfigForType(String endpointType) {
    switch (endpointType.toLowerCase()) {
      case "auth":
        return rateLimitProperties.getAuth();
      case "upload":
        return rateLimitProperties.getUpload();
      case "api":
        return rateLimitProperties.getApi();
      case "global":
        return rateLimitProperties.getGlobal();
      default:
        log.warn("Unknown endpoint type: {}", endpointType);
        return null;
    }
  }
}
