package com.example.demo.common.pagination;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaginationMetaDataDto {
    private int currentPage;
    private int totalPages;
    private int totalItems;
    private int limit;

    public PaginationMetaDataDto(long totalItems, PaginationDto paginationDto) {
        this.totalItems = (int) totalItems;
        this.limit = paginationDto.getLimit();
        this.currentPage = paginationDto.getPage();
        this.totalPages = (int) Math.ceil((double) totalItems / paginationDto.getLimit());
    }
}