package com.spring.app.common.pagination;

import java.util.List;

import lombok.Builder;

@Builder
public record PageDto<T>(
  List<T> items,
  PaginationMetaDataDto meta
) {}