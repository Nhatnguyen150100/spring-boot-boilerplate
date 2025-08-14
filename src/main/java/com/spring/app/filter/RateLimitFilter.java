package com.spring.app.filter;

import com.spring.app.configs.properties.RateLimitProperties;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

  private final RedisServiceInterface redisService;
  private final AntPathMatcher pathMatcher;
  private final RateLimitProperties rateLimitProperties;

  private static final String[] AUTH_ENDPOINTS = {
      "/auth/login",
      "/auth/register",
      "/auth/resend-otp",
      "/auth/activate"
  };

  private static final String[] UPLOAD_ENDPOINTS = {
      "/upload/**"
  };

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    String clientIp = getClientIpAddress(request);
    String requestPath = request.getRequestURI();

    try {
      if (rateLimitProperties.isGlobalEnabled() && !checkGlobalRateLimit(clientIp)) {
        handleRateLimitExceeded(response, "Global rate limit exceeded");
        return;
      }

      if (rateLimitProperties.isAuthEnabled() && isAuthEndpoint(requestPath) &&
          !checkAuthRateLimit(clientIp)) {
        handleRateLimitExceeded(response, "Authentication rate limit exceeded");
        return;
      }

      if (rateLimitProperties.isUploadEnabled() && isUploadEndpoint(requestPath) &&
          !checkUploadRateLimit(clientIp)) {
        handleRateLimitExceeded(response, "Upload rate limit exceeded");
        return;
      }

      filterChain.doFilter(request, response);

    } catch (Exception e) {
      log.error("Error in rate limit filter", e);
      filterChain.doFilter(request, response);
    }
  }

  private boolean checkGlobalRateLimit(String clientIp) {
    RateLimitProperties.Global global = rateLimitProperties.getGlobal();

    return checkRateLimit("rate_limit:global:minute:" + clientIp, global.getRequestsPerMinute(), 60) &&
        checkRateLimit("rate_limit:global:hour:" + clientIp, global.getRequestsPerHour(), 3600) &&
        checkRateLimit("rate_limit:global:day:" + clientIp, global.getRequestsPerDay(), 86400);
  }

  private boolean checkAuthRateLimit(String clientIp) {
    RateLimitProperties.Auth auth = rateLimitProperties.getAuth();

    return checkRateLimit("rate_limit:auth:minute:" + clientIp, auth.getRequestsPerMinute(), 60) &&
        checkRateLimit("rate_limit:auth:hour:" + clientIp, auth.getRequestsPerHour(), 3600) &&
        checkRateLimit("rate_limit:auth:day:" + clientIp, auth.getRequestsPerDay(), 86400);
  }

  private boolean checkUploadRateLimit(String clientIp) {
    RateLimitProperties.Upload upload = rateLimitProperties.getUpload();

    return checkRateLimit("rate_limit:upload:minute:" + clientIp, upload.getRequestsPerMinute(), 60) &&
        checkRateLimit("rate_limit:upload:hour:" + clientIp, upload.getRequestsPerHour(), 3600) &&
        checkRateLimit("rate_limit:upload:day:" + clientIp, upload.getRequestsPerDay(), 86400);
  }

  private boolean checkRateLimit(String key, int maxRequests, int windowSeconds) {
    try {
      String currentCount = (String) redisService.getValue(key);
      int count = currentCount == null ? 0 : Integer.parseInt(currentCount);

      if (count >= maxRequests) {
        log.warn("Rate limit exceeded for key: {} (count: {}, max: {})", key, count, maxRequests);
        return false;
      }

      redisService.setValue(key, String.valueOf(count + 1), windowSeconds, TimeUnit.SECONDS);
      return true;

    } catch (Exception e) {
      log.error("Error checking rate limit for key: {}", key, e);
      return true;
    }
  }

  private boolean isAuthEndpoint(String requestPath) {
    for (String pattern : AUTH_ENDPOINTS) {
      if (pathMatcher.match(pattern, requestPath)) {
        return true;
      }
    }
    return false;
  }

  private boolean isUploadEndpoint(String requestPath) {
    for (String pattern : UPLOAD_ENDPOINTS) {
      if (pathMatcher.match(pattern, requestPath)) {
        return true;
      }
    }
    return false;
  }

  private String getClientIpAddress(HttpServletRequest request) {
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

  private void handleRateLimitExceeded(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");

    String errorResponse = String.format(
        "{\"error\": \"Rate limit exceeded\", \"message\": \"%s\", \"retryAfter\": 60}",
        message);

    response.getWriter().write(errorResponse);
    log.warn("Rate limit exceeded: {}", message);
  }
}
