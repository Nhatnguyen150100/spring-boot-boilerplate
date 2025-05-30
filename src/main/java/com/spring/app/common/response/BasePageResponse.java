package com.spring.app.common.response;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.spring.app.common.pagination.PageDto;
import com.spring.app.common.pagination.PaginationDto;
import com.spring.app.common.pagination.PaginationMetaDataDto;

import lombok.*;

@Getter
@Setter
public class BasePageResponse<T> extends BaseResponse<PageDto<T>> {

  public BasePageResponse(String message, List<T> data, PaginationDto paginationDto, long totalItems) {
    super(
        HttpStatus.OK.value(),
        message,
        PageDto.<T>builder()
            .items(data)
            .meta(new PaginationMetaDataDto(totalItems, paginationDto))
            .build(),
        Instant.now().toString());
  }

  /**
   * Creates a new {@link BasePageResponse} instance with the provided data,
   * pagination details,
   * total item count, and message.
   *
   * @param data          the list of items to include in the response
   * @param paginationDto the pagination details for the response
   * @param totalItems    the total number of items available
   * @param message       the message to include in the response
   * @return a new {@link BasePageResponse} with the specified parameters
   */
  public static <T> BasePageResponse<T> of(List<T> data, PaginationDto paginationDto, long totalItems, String message) {
    return new BasePageResponse<T>(message, data, paginationDto, totalItems);
  }
}