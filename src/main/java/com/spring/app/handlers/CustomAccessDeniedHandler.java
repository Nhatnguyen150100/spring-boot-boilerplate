package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
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
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  /**
   * Handles an access denied exception by writing an appropriate error response.
   *
   * <p>
   * This method writes a JSON response with a status of
   * {@link HttpStatus#FORBIDDEN} and a message indicating that permission is
   * denied.
   *
   * @param request  the HTTP request
   * @param response the HTTP response
   * @param ex       the exception to handle
   * @throws IOException      if an I/O error occurs
   * @throws ServletException if an error occurs during the handling process
   */
  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {

    var res = ResponseBuilder
        .forbidden("You do not have permission to access this resource. Please use a other account.");

    int status = res.getStatusCode().value();

    response.setStatus(status);
    response.setContentType("application/json");

    response.getWriter().write(objectMapper.writeValueAsString(res.getBody()));
  }
}