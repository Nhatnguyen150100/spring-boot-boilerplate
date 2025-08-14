package com.spring.app.filter;

import com.spring.app.constants.WhitelistUrlConstant;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.services.RateLimitManagerService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

  private final AntPathMatcher pathMatcher;
  private final RateLimitManagerService rateLimitManagerService;
  private final HandlerExceptionResolver handlerExceptionResolver;

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    String clientIp = getClientIpAddress(request);
    String requestPath = request.getRequestURI();

    try {
      if (isAuthEndpoint(requestPath)) {
        rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, clientIp);
      } else if (isUploadEndpoint(requestPath)) {
        rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.UPLOAD, clientIp);
      } else if (isApiEndpoint(requestPath)) {
        rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.API, clientIp);
      } else {
        rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.GLOBAL, clientIp);
      }

      filterChain.doFilter(request, response);

    } catch (RateLimitExceededException e) {
      handlerExceptionResolver.resolveException(request, response, null, e);
      return;
    } catch (Exception e) {
      filterChain.doFilter(request, response);
    }
  }

  private boolean isAuthEndpoint(String requestPath) {
    return isRouteApplyRateLimit(requestPath, WhitelistUrlConstant.AUTH_ENDPOINTS_RATELIMIT);
  }

  private boolean isUploadEndpoint(String requestPath) {
    return isRouteApplyRateLimit(requestPath, WhitelistUrlConstant.UPLOAD_ENDPOINTS_RATELIMIT);
  }

  private boolean isApiEndpoint(String requestPath) {
    return isRouteApplyRateLimit(requestPath, WhitelistUrlConstant.API_ENDPOINTS_RATELIMIT);
  }

  private boolean isRouteApplyRateLimit(String requestPath, String[] rateLimitPatterns) {
    for (String pattern : rateLimitPatterns) {
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
}
