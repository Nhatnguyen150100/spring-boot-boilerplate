package com.spring.app.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.spring.app.utils.JwtFunctionUtil;

import io.jsonwebtoken.ExpiredJwtException;

class JwtAuthenticatorFilterTest {

  private final JwtFunctionUtil jwtFunction = mock(JwtFunctionUtil.class);
  private final JwtAuthenticatorFilter filter = new JwtAuthenticatorFilter(jwtFunction);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockFilterChain run(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain;
  }

  @Test
  void expiredTokenFallsThroughAsAnonymousInsteadOfFailingTheRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    when(jwtFunction.extractTokenFromHeader(request)).thenReturn("expired");
    when(jwtFunction.isAuthenticationNotSet()).thenReturn(true);
    doThrow(new ExpiredJwtException(null, null, "expired"))
        .when(jwtFunction).authenticateAccessToken("expired", request);

    MockFilterChain chain = run(request);

    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void unknownSubjectFallsThroughAsAnonymous() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    when(jwtFunction.extractTokenFromHeader(request)).thenReturn("token");
    when(jwtFunction.isAuthenticationNotSet()).thenReturn(true);
    doThrow(new UsernameNotFoundException("gone"))
        .when(jwtFunction).authenticateAccessToken("token", request);

    assertThat(run(request).getRequest()).isSameAs(request);
  }

  @Test
  void requestWithoutABearerTokenSkipsAuthentication() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

    assertThat(run(request).getRequest()).isSameAs(request);
    verify(jwtFunction, never()).authenticateAccessToken(any(), any());
  }
}
