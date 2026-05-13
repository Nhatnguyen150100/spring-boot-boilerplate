package com.spring.app.common.response;

import lombok.*;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@AllArgsConstructor
@Builder
public class BaseResponse<T> {
  private int statusCode;
  private String message;
  private T data;
  private String timestamp;

  public static <T> BaseResponse<T> of(HttpStatusCode status, String message, T data) {
    return BaseResponse.<T>builder()
        .statusCode(status.value())
        .message(message)
        .data(data)
        .timestamp(Instant.now().toString())
        .build();
  }

  public static <T> BaseResponse<T> of(HttpStatusCode status, String message) {
    return BaseResponse.<T>builder()
        .statusCode(status.value())
        .message(message)
        .timestamp(Instant.now().toString())
        .build();
  }

  public static <T> BaseResponse<T> success(HttpStatusCode status, String message, T data) {
    return of(status, message, data);
  }

  public static <T> BaseResponse<T> success(String message, T data) {
    return of(HttpStatus.OK, message, data);
  }

  public static <T> BaseResponse<T> success(String message) {
    return of(HttpStatus.OK, message);
  }

  public static <T> BaseResponse<T> error(HttpStatusCode status, String message) {
    return of(status, message);
  }
}
