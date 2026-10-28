package com.spring.app.constants;

/** Base paths shared by the controllers, the security whitelist and the rate-limit filter. */
public final class ApplicationConstants {

  public static final String API_BASE_PATH = "/api/v1";
  public static final String AUTH_BASE_PATH = API_BASE_PATH + "/auth";
  public static final String USER_BASE_PATH = API_BASE_PATH + "/users";
  public static final String FILES_BASE_PATH = API_BASE_PATH + "/files";

  private ApplicationConstants() {
  }
}
