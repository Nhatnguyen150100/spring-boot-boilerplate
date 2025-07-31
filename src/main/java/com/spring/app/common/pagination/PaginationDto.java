package com.spring.app.common.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PaginationDto {
  private int page;
  private int limit;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String search;

  /**
   * Calculates the skip value for pagination queries.
   *
   * @return The skip value.
   */
  public int skip() {
    return (this.page - 1) * this.limit;
  }
}
