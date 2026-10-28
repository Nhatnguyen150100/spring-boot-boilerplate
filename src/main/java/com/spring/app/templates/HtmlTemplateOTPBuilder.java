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

  private final String template;

  public HtmlTemplateOTPBuilder() {
    this(new ClassPathResource(TEMPLATE_LOCATION));
  }

  HtmlTemplateOTPBuilder(Resource resource) {
    this.template = load(resource);
  }

  public String buildOtpHtml(String otp) {
    Objects.requireNonNull(otp, "otp must not be null");
    return template.replace(OTP_PLACEHOLDER, HtmlUtils.htmlEscape(otp));
  }

  private static String load(Resource resource) {
    String content;
    try {
      content = resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read OTP e-mail template " + resource.getDescription(), e);
    }
    if (!content.contains(OTP_PLACEHOLDER)) {
      throw new IllegalStateException(
          "OTP e-mail template " + resource.getDescription() + " has no " + OTP_PLACEHOLDER + " placeholder");
    }
    return content;
  }
}
