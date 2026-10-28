package com.spring.app.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.RateLimitManagerService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  private static final String CLIENT_IP = "198.51.100.7";

  @Mock
  private RateLimitManagerService rateLimitManagerService;
  @Mock
  private HandlerExceptionResolver handlerExceptionResolver;
  @Mock
  private FilterChain filterChain;
  @Mock
  private MonitoringService monitoringService;

  private final ApplicationProperties applicationProperties = new ApplicationProperties();
  private RateLimitFilter filter;

  @BeforeEach
  void setUp() {
    filter = new RateLimitFilter(new AntPathMatcher(), rateLimitManagerService, handlerExceptionResolver,
        applicationProperties, monitoringService);
  }

  private static MockHttpServletRequest request(String path, String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(remoteAddr);
    return request;
  }

  // ---------------------------------------------------------------- the chain runs exactly once

  @Test
  void downstreamServletExceptionPropagatesAndChainRunsOnce() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    ServletException downstream = new ServletException("downstream failure");
    doThrow(downstream).when(filterChain).doFilter(request, response);

    assertThatThrownBy(() -> filter.doFilter(request, response, filterChain)).isSameAs(downstream);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void downstreamRuntimeExceptionPropagatesAndChainRunsOnce() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    IllegalStateException downstream = new IllegalStateException("expired token further down");
    doThrow(downstream).when(filterChain).doFilter(request, response);

    assertThatThrownBy(() -> filter.doFilter(request, response, filterChain)).isSameAs(downstream);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void rejectedRequestIsResolvedAndNeverReachesTheChain() throws Exception {
    MockHttpServletRequest request = request("/api/v1/auth/login", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    RateLimitExceededException limit = new RateLimitExceededException("limit");
    doThrow(limit).when(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, CLIENT_IP);

    filter.doFilter(request, response, filterChain);

    verify(handlerExceptionResolver).resolveException(request, response, null, limit);
    verify(monitoringService).recordRateLimitRejection("auth");
    verifyNoInteractions(filterChain);
  }

  @Test
  void rejectionCarriesRetryAfter() throws Exception {
    MockHttpServletRequest request = request("/api/v1/auth/login", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    RateLimitExceededException limit = new RateLimitExceededException("limit");
    doThrow(limit).when(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, CLIENT_IP);
    when(handlerExceptionResolver.resolveException(request, response, null, limit)).thenReturn(new ModelAndView());

    filter.doFilter(request, response, filterChain);

    assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    assertThat(response.getStatus()).as("left to the resolver").isEqualTo(200);
  }

  @Test
  void unresolvedRejectionIsStillA429() throws Exception {
    MockHttpServletRequest request = request("/api/v1/auth/login", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    RateLimitExceededException limit = new RateLimitExceededException("limit");
    doThrow(limit).when(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, CLIENT_IP);
    when(handlerExceptionResolver.resolveException(request, response, null, limit)).thenReturn(null);

    filter.doFilter(request, response, filterChain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    verifyNoInteractions(filterChain);
  }

  @Test
  void brokenLimiterFailsOpenAndChainRunsOnce() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    doThrow(new IllegalStateException("redis down"))
        .when(rateLimitManagerService).checkRateLimitAndThrow(any(), anyString());

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verifyNoInteractions(handlerExceptionResolver);
  }

  // ---------------------------------------------------------------- bucket selection

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "/api/v1/auth/login, AUTH",
      "/api/v1/auth/oauth2/authorization/google, AUTH",
      "/api/v1/auth/oauth2/code/google, AUTH",
      "/api/v1/%61uth/login, AUTH",
      "/api/v1/auth;x=1/login, AUTH",
      "/api/v1/files/upload, UPLOAD",
      "/api/v1/fil%65s/upload, UPLOAD",
      "/api/v1/users/me, API",
      "/api/v2/anything, API",
      "/actuator/health, GLOBAL",
      "/swagger-ui/index.html, GLOBAL"
  })
  void picksBucketByPath(String path, ERateLimitEndpoint expected) throws Exception {
    MockHttpServletRequest request = request(path, CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(expected, CLIENT_IP);
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void undecodablePathFallsBackToTheGlobalBucket() throws Exception {
    MockHttpServletRequest request = request("/api/%zz", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.GLOBAL, CLIENT_IP);
    verify(filterChain).doFilter(request, response);
  }

  // ---------------------------------------------------------------- client IP resolution

  // Resolution details are covered by ClientKeysTest; these prove the filter uses it.

  @Test
  void forwardedHeadersAreIgnoredFromUntrustedPeers() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    request.addHeader("X-Forwarded-For", "203.0.113.9");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.API, CLIENT_IP);
  }

  @Test
  void trustedProxyYieldsRightMostUntrustedHop() throws Exception {
    applicationProperties.setTrustedProxies(List.of("10.0.0.1", "10.0.0.2"));
    MockHttpServletRequest request = request("/api/v1/users/me", "10.0.0.1");
    request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9, 10.0.0.2");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.API, "203.0.113.9");
  }

  @Test
  void ipv6ClientsInOneSlash64ShareTheIdentifier() throws Exception {
    filter.doFilter(request("/api/v1/users/me", "2001:db8:1:2:aaaa:bbbb:cccc:dddd"),
        new MockHttpServletResponse(), filterChain);
    filter.doFilter(request("/api/v1/users/me", "2001:db8:1:2::1"),
        new MockHttpServletResponse(), filterChain);
    filter.doFilter(request("/api/v1/users/me", "2001:db8:1:3::1"),
        new MockHttpServletResponse(), filterChain);

    verify(rateLimitManagerService, times(2)).checkRateLimitAndThrow(ERateLimitEndpoint.API, "2001:db8:1:2::/64");
    verify(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.API, "2001:db8:1:3::/64");
  }
}
