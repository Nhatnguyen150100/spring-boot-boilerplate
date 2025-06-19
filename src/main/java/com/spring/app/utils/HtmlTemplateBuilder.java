package com.spring.app.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class HtmlTemplateBuilder {
  public static String buildOtpHtml(String otp) {
    try {
      String template = Files.readString(Paths.get("src/main/resources/templates/otp-email.html"));
      return template.replace("{{OTP}}", otp);
    } catch (IOException e) {
      throw new RuntimeException("Could not read HTML", e);
    }
  }
}
