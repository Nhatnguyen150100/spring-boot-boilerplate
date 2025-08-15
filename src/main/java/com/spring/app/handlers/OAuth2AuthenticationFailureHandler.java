package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.http.HttpStatus;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.app.common.response.ResponseBuilder;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final ObjectMapper objectMapper;

  /**
   * Handles an authentication exception by writing an appropriate error response.
   *
   * <p>
   * This method writes a JSON response with a status of
   * {@link HttpStatus#UNAUTHORIZED} and a message indicating that the
   * authentication failed.
   *
   * @param request   the HTTP request
   * @param response  the HTTP response
   * @param exception the exception to handle
   * @throws IOException      if an I/O error occurs
   * @throws ServletException if an error occurs during the handling process
   */
  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException, ServletException {        
    var res = ResponseBuilder.badRequest("Authentication failed: " + exception.getMessage());

    int status = res.getStatusCode().value();
    response.setStatus(status);
    response.setContentType("application/json");

    response.getWriter().write(objectMapper.writeValueAsString(res.getBody()));
  }

}
