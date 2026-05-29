package com.spring.app.common.response;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.spring.app.common.pagination.PaginationDto;

public final class ResponseBuilder {

  private ResponseBuilder() {
    throw new IllegalStateException("Utility class");
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> success() {
    return ResponseEntity.ok(BaseResponse.success("Success", null));
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> success(T data) {
    return ResponseEntity.ok(BaseResponse.success("Success", data));
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> success(String message, T data) {
    return ResponseEntity.ok(BaseResponse.success(message, data));
  }

  public static <T> @NonNull ResponseEntity<BasePageResponse<T>> successPageResponse(List<T> data, PaginationDto paginationDto, long totalItems, String message) {
    return ResponseEntity.ok(BasePageResponse.success(data, paginationDto, totalItems, message));
  }

  public static <T> @NonNull ResponseEntity<BasePageResponse<T>> successPageResponse(List<T> data, PaginationDto paginationDto, long totalItems) {
    return ResponseEntity.ok(BasePageResponse.success(data, paginationDto, totalItems));
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> created(T data) {
    return created("Created", data);
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> created(String message, T data) {
    return ResponseEntity.status(HttpStatus.CREATED).body(BaseResponse.success(HttpStatus.CREATED, message, data));
  }

  public static @NonNull ResponseEntity<Void> noContent() {
    return ResponseEntity.noContent().build();
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(BaseResponse.error(status, message));
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> badRequest(String message) {
    return error(HttpStatus.BAD_REQUEST, message);
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> notFound(String message) {
    return error(HttpStatus.NOT_FOUND, message);
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> unauthorized(String message) {
    return error(HttpStatus.UNAUTHORIZED, message);
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> forbidden(String message) {
    return error(HttpStatus.FORBIDDEN, message);
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> conflict(String message) {
    return error(HttpStatus.CONFLICT, message);
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> tooManyRequest(String message) {
    return error(HttpStatus.TOO_MANY_REQUESTS, message);
  }

  public static <T> @NonNull ResponseEntity<BaseResponse<T>> internalServerError(String message) {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, message);
  }
}