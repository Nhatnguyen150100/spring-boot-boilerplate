package com.spring.app.modules.auth.controller;

import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;

/** Login throttling must key on the same client identity as RateLimitFilter. */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

  @Mock
  private AuthServiceInterface authService;

  private final ApplicationProperties applicationProperties = new ApplicationProperties();

  private final LoginRequestDto dto = new LoginRequestDto("user@example.com", "secret");

  private AuthController controller() {
    return new AuthController(authService, applicationProperties);
  }

  private static MockHttpServletRequest loginRequest(String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setRemoteAddr(remoteAddr);
    return request;
  }

  @Test
  void loginBehindATrustedProxyUsesTheForwardedClient() {
    applicationProperties.setTrustedProxies(List.of("10.0.0.1"));
    MockHttpServletRequest request = loginRequest("10.0.0.1");
    request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");

    controller().login(dto, request);

    verify(authService).login(dto, "203.0.113.9");
  }

  @Test
  void loginFromAnUntrustedPeerUsesThePeerAsItsSlash64() {
    MockHttpServletRequest request = loginRequest("2001:db8:1:2::1");
    request.addHeader("X-Forwarded-For", "203.0.113.9");

    controller().login(dto, request);

    verify(authService).login(dto, "2001:db8:1:2::/64");
  }
}
