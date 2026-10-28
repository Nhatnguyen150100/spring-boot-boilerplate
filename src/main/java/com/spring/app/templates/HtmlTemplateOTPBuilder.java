package com.spring.app.templates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders the OTP e-mail body from {@code classpath:templates/otp-email.html}.
 *
 * <p>
 * The template is read once, when the bean is created, and always from the
 * classpath - never from the working directory - so it works from the
 * packaged jar and the Docker image. A missing or broken template stops the
 * application from starting instead of silently failing every OTP send.
 * Inserted values are HTML-escaped.
 * </p>
 */
@Component
public class HtmlTemplateOTPBuilder {

  static final String TEMPLATE_LOCATION = "templates/otp-email.html";
  static final String OTP_PLACEHOLDER = "{{OTP}}";
  static final String EXPIRY_MINUTES_PLACEHOLDER = "{{EXPIRY_MINUTES}}";

  private final String template;

  public HtmlTemplateOTPBuilder() {
    this(new ClassPathResource(TEMPLATE_LOCATION));
  }

  HtmlTemplateOTPBuilder(Resource resource) {
    this.template = load(resource);
  }

  /**
   * @param otp           the one-time password
   * @param expiryMinutes how long the OTP stays valid, shown in the e-mail
   */
  public String buildOtpHtml(String otp, long expiryMinutes) {
    Objects.requireNonNull(otp, "otp must not be null");
    return template
        .replace(OTP_PLACEHOLDER, HtmlUtils.htmlEscape(otp))
        .replace(EXPIRY_MINUTES_PLACEHOLDER, Long.toString(expiryMinutes));
  }

  private static String load(Resource resource) {
    String content;
    try {
      content = resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read OTP e-mail template " + resource.getDescription(), e);
    }
    for (String placeholder : new String[] { OTP_PLACEHOLDER, EXPIRY_MINUTES_PLACEHOLDER }) {
      if (!content.contains(placeholder)) {
        throw new IllegalStateException(
            "OTP e-mail template " + resource.getDescription() + " has no " + placeholder + " placeholder");
      }
    }
    return content;
  }
}
