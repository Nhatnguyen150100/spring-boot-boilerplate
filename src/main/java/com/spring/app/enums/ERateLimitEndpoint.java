package com.spring.app.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ERateLimitEndpoint {
  GLOBAL("global"),
  AUTH("auth"),
  UPLOAD("upload"),
  API("api");

  private final String endpointType;

}
