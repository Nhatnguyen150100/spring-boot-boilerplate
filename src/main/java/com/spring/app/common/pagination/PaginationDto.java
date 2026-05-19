package com.spring.app.common.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;

@Builder
public record PaginationDto(
  int page,
  int limit,
  @JsonInclude(JsonInclude.Include.NON_NULL)
  String search
) {
  /**
   * Calculates the skip value for pagination queries.
   *
   * @return The skip value.
   */
  public int skip() {
    return (this.page - 1) * this.limit;
  }
}
