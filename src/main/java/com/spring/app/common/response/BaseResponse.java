package com.spring.app.common.response;

import lombok.*;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BaseResponse<T> {
  private int statusCode;
  private String message;
  private T data;
  private String timestamp;

  /**
   * Creates a new {@link BaseResponse} from the given parameters.
   *
   * @param status  the HTTP status to use
   * @param message the message to include in the response
   * @param data    the data to include in the response, if any
   * @return a new {@link BaseResponse} with the given parameters
   */
  public static <T> BaseResponse<T> of(HttpStatusCode status, String message, T data) {
    return BaseResponse.<T>builder()
        .statusCode(status.value())
        .message(message)
        .data(data)
        .timestamp(Instant.now().toString())
        .build();
  }

  /**
   * Creates a new {@link BaseResponse} with a success status and the given
   * message
   * and data.
   *
   * @param status  the HTTP status to use
   * @param message the message to include in the response
   * @param data    the data to include in the response, if any
   * @return a new {@link BaseResponse} with the given parameters
   */
  public static <T> BaseResponse<T> success(HttpStatusCode status, String message, T data) {
    return of(status, message, data);
  }

  /**
   * Creates a new {@link BaseResponse} with a success status and the given
   * message
   * and data.
   *
   * @param message the message to include in the response
   * @param data    the data to include in the response, if any
   * @return a new {@link BaseResponse} with the given parameters
   */
  public static <T> BaseResponse<T> success(String message, T data) {
    return of(HttpStatus.OK, message, data);
  }

  /**
   * Creates a new {@link BaseResponse} with a success status and the given
   * message
   * and data.
   *
   * @param message the message to include in the response
   * @param data    the data to include in the response, if any
   * @return a new {@link BaseResponse} with the given parameters
   */
  public static <T> BaseResponse<T> success(String message, HttpStatus status, T data) {
    return of(status, message, data);
  }

  /**
   * Creates a new {@link BaseResponse} with a success status and the given
   * message
   *
   * @param message the message to include in the response
   * @return a new {@link BaseResponse} with the given parameters
   */
  public static <T> BaseResponse<T> success(String message) {
    return of(HttpStatus.OK, message, null);
  }

  /**
   * Creates a new {@link BaseResponse} with the given HTTP status and
   * error message.
   *
   * @param status  the HTTP status to use
   * @param message the error message to include in the response
   * @return a new {@link BaseResponse} with the given parameters
   */
  public static <T> BaseResponse<T> error(HttpStatusCode status, String message) {
    return of(status, message, null);
  }
}
