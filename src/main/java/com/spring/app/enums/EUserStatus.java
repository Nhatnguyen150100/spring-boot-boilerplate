package com.spring.app.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum EUserStatus {
  ACTIVE("STATUS_ACTIVE"),
  INACTIVE("STATUS_INACTIVE"),
  PENDING("STATUS_PENDING"),
  DELETED("STATUS_DELETED");

  @Getter
  private final String status;
}
