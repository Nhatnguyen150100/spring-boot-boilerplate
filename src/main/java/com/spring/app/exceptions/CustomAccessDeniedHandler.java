package com.spring.app.exceptions;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring.app.common.response.BaseResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

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

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json");

    BaseResponse<Void> body = BaseResponse.error(HttpStatus.FORBIDDEN,
        "You do not have permission to access this resource");

    ObjectMapper mapper = new ObjectMapper();
    response.getWriter().write(mapper.writeValueAsString(body));
  }
}