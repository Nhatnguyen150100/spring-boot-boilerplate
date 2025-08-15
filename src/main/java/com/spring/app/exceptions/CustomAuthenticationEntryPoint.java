package com.spring.app.exceptions;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.app.common.response.ResponseBuilder;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  /**
   * Handles the commencement of an authentication scheme by sending an
   * unauthorized error response in JSON format.
   *
   * <p>
   * This method is triggered when an unauthenticated user tries to access a
   * resource that requires authentication. It sets the response status to
   * {@link HttpServletResponse#SC_UNAUTHORIZED}, specifies the content type as
   * "application/json", and writes a JSON error message indicating unauthorized
   * access.
   *
   * @param request       the HTTP request
   * @param response      the HTTP response
   * @param authException the authentication exception encountered
   * @throws IOException      if an I/O error occurs
   * @throws ServletException if an error occurs during the handling process
   */
  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
      throws IOException, ServletException {

    var res = ResponseBuilder.unauthorized("Unauthorized access. Please login to continue.");

    int status = res.getStatusCode().value();

    response.setStatus(status);
    response.setContentType("application/json");

    response.getWriter().write(objectMapper.writeValueAsString(res.getBody()));
  }

}
