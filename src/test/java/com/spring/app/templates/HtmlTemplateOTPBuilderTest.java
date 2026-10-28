package com.spring.app.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * The template must come from the classpath (it is not on disk inside the
 * jar / Docker image), be read once, and have the OTP HTML-escaped.
 */
class HtmlTemplateOTPBuilderTest {

  private static Resource resource(String content) {
    return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rendersPackagedTemplateFromClasspath() {
    String html = new HtmlTemplateOTPBuilder().buildOtpHtml("123456", 3);

    assertThat(html)
        .contains("<html")
        .contains("123456")
        .contains("3 minutes")
        // every placeholder in the shipped template must be filled
        .doesNotContain("{{");
  }

  @Test
  void escapesTheInsertedValue() {
    HtmlTemplateOTPBuilder builder = new HtmlTemplateOTPBuilder(resource("<p>{{OTP}} / {{EXPIRY_MINUTES}}</p>"));

    assertThat(builder.buildOtpHtml("<b>&", 5)).isEqualTo("<p>&lt;b&gt;&amp; / 5</p>");
  }

  @Test
  void readsTheTemplateOnlyOnce() {
    AtomicInteger reads = new AtomicInteger();
    String content = "<p>{{OTP}} {{EXPIRY_MINUTES}}</p>";
    Resource counting = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getContentAsString(Charset charset) {
        reads.incrementAndGet();
        return content;
      }
    };
    HtmlTemplateOTPBuilder builder = new HtmlTemplateOTPBuilder(counting);

    builder.buildOtpHtml("111111", 3);
    builder.buildOtpHtml("222222", 3);

    assertThat(reads).hasValue(1);
  }

  @Test
  void missingTemplateFailsFast() {
    assertThatThrownBy(() -> new HtmlTemplateOTPBuilder(new ClassPathResource("templates/does-not-exist.html")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does-not-exist.html");
  }

  @Test
  void templateWithoutOtpPlaceholderIsRejected() {
    assertThatThrownBy(() -> new HtmlTemplateOTPBuilder(resource("<p>valid for {{EXPIRY_MINUTES}} minutes</p>")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("{{OTP}}");
  }

  @Test
  void templateWithoutExpiryPlaceholderIsRejected() {
    assertThatThrownBy(() -> new HtmlTemplateOTPBuilder(resource("<p>{{OTP}}</p>")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("{{EXPIRY_MINUTES}}");
  }
}
