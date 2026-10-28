package com.spring.app.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum EPermission {
  ADMIN_READ("PERMISSION_ADMIN_READ"),
  ADMIN_UPDATE("PERMISSION_ADMIN_UPDATE"),
  ADMIN_CREATE("PERMISSION_ADMIN_CREATE"),
  ADMIN_DELETE("PERMISSION_ADMIN_DELETE"),
  ;

  @Getter
  private final String permission;
}
