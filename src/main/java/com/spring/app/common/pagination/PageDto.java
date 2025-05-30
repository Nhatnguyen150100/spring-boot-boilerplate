package com.spring.app.common.pagination;

import java.util.List;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PageDto<T> {
  private List<T> items;
  private PaginationMetaDataDto meta;
}