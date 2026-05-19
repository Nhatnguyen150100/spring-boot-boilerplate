package com.spring.app.common.pagination;

public record PaginationMetaDataDto(
    int currentPage,
    int totalPages,
    int totalItems,
    int limit
) {
    public PaginationMetaDataDto(long totalItems, PaginationDto paginationDto) {
        this(
            paginationDto.page(),
            (int) Math.ceil((double) totalItems / paginationDto.limit()),
            (int) totalItems,
            paginationDto.limit()
        );
    }
}