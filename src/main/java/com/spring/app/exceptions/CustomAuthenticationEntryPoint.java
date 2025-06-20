package com.spring.app.exceptions;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.app.common.response.BaseResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

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
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");

    BaseResponse<Void> body = BaseResponse.error(HttpStatus.UNAUTHORIZED, "Unauthorized access");

    ObjectMapper mapper = new ObjectMapper();
    response.getWriter().write(mapper.writeValueAsString(body));
  }

}
