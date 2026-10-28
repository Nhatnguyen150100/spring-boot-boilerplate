package com.spring.app.filter;

import java.io.IOException;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

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
 * chain (see {@code SecurityConfig}). Servlet auto-registration is disabled in
 * {@code FilterRegistrationConfig}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

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

    ERateLimitEndpoint bucket = resolveBucket(request.getRequestURI());
    // IPv6 clients are throttled per /64, not per address (see ClientKeys).
    String clientIp = ClientKeys.clientKey(getClientIpAddress(request));

    // Only the rate-limit check is guarded. The downstream chain is called
    // exactly once, outside any try block: calling it again after a failure
    // would execute the request (e.g. a POST) a second time.
    try {
      rateLimitManagerService.checkRateLimitAndThrow(bucket, clientIp);
    } catch (RateLimitExceededException e) {
      monitoringService.recordRateLimitRejection(bucket.getEndpointType());
      handlerExceptionResolver.resolveException(request, response, null, e);
      return;
    } catch (RuntimeException e) {
      // Fail open: a broken limiter must not take the whole API down.
      log.error("Rate limit check failed for bucket {}; allowing the request", bucket, e);
    }

    filterChain.doFilter(request, response);
  }

  ERateLimitEndpoint resolveBucket(String requestPath) {
    for (ERateLimitEndpoint bucket : ERateLimitEndpoint.values()) {
      if (bucket.matches(requestPath, pathMatcher)) {
        return bucket;
      }
    }
    return ERateLimitEndpoint.GLOBAL;
  }

  /**
   * The peer address, unless the peer is one of {@code application.trusted-proxies}.
   * Then X-Forwarded-For is walked right to left: entries appended by our own
   * proxies are skipped and the first untrusted one is the client. Anything
   * further left was sent by the client itself and can be forged.
   */
  String getClientIpAddress(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (!isTrustedProxy(remoteAddr)) {
      return remoteAddr;
    }

    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (StringUtils.hasText(xForwardedFor)) {
      String[] hops = xForwardedFor.split(",");
      for (int i = hops.length - 1; i >= 0; i--) {
        String hop = hops[i].trim();
        if (hop.isEmpty() || "unknown".equalsIgnoreCase(hop)) {
          return remoteAddr;
        }
        if (!isTrustedProxy(hop)) {
          return hop;
        }
      }
      return remoteAddr;
    }

    String xRealIp = request.getHeader("X-Real-IP");
    if (StringUtils.hasText(xRealIp) && !"unknown".equalsIgnoreCase(xRealIp.trim())) {
      return xRealIp.trim();
    }
    return remoteAddr;
  }

  private boolean isTrustedProxy(String address) {
    List<String> proxies = applicationProperties.getTrustedProxies();
    if (proxies == null || proxies.isEmpty()) {
      return false;
    }
    return proxies.stream()
        .map(String::trim)
        .anyMatch(address::equals);
  }
}
