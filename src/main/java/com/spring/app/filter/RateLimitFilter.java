package com.spring.app.filter;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.UrlPathHelper;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.RateLimitManagerService;
import com.spring.app.utils.ClientKeys;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-client-IP fixed-window rate limiting, run inside the Spring Security
 * chain (see {@code SecurityConfig}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String RETRY_AFTER_SECONDS = "60";

  private final AntPathMatcher pathMatcher;
  private final RateLimitManagerService rateLimitManagerService;
  private final HandlerExceptionResolver handlerExceptionResolver;
  private final ApplicationProperties applicationProperties;
  private final MonitoringService monitoringService;

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    ERateLimitEndpoint bucket = resolveBucket(request);
    // Same identity as login throttling; IPv6 clients are keyed per /64 (see ClientKeys).
    String clientIp = ClientKeys.clientKey(request, applicationProperties.getTrustedProxies());

    // Only the rate-limit check is guarded. The downstream chain is called
    // exactly once, outside any try block: calling it again after a failure
    // would execute the request (e.g. a POST) a second time.
    try {
      rateLimitManagerService.checkRateLimitAndThrow(bucket, clientIp);
    } catch (RateLimitExceededException e) {
      monitoringService.recordRateLimitRejection(bucket.getEndpointType());
      // The shortest window; a client over its hourly or daily quota retries in vain, but harmlessly.
      response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
      if (handlerExceptionResolver.resolveException(request, response, null, e) == null
          && !response.isCommitted()) {
        response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
      }
      return;
    } catch (RuntimeException e) {
      // Fail open: a broken limiter must not take the whole API down.
      log.error("Rate limit check failed for bucket {}; allowing the request", bucket, e);
    }

    filterChain.doFilter(request, response);
  }

  /**
   * A path that cannot be decoded (e.g. "/api/%zz") falls in GLOBAL: the
   * request is still throttled, and whatever handles it later rejects it.
   */
  private ERateLimitEndpoint resolveBucket(HttpServletRequest request) {
    String path;
    try {
      path = requestPath(request);
    } catch (IllegalArgumentException e) {
      log.debug("Undecodable request path; using the {} bucket", ERateLimitEndpoint.GLOBAL);
      return ERateLimitEndpoint.GLOBAL;
    }
    return resolveBucket(path);
  }

  /**
   * Decoded, context-relative path without ;params, so that "/api/v1/%61uth/login"
   * or "/api/v1/auth;x=1/login" cannot dodge the bucket their real target falls in.
   */
  private static String requestPath(HttpServletRequest request) {
    return StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
  }

  ERateLimitEndpoint resolveBucket(String requestPath) {
    for (ERateLimitEndpoint bucket : ERateLimitEndpoint.values()) {
      if (bucket.matches(requestPath, pathMatcher)) {
        return bucket;
      }
    }
    return ERateLimitEndpoint.GLOBAL;
  }
}
