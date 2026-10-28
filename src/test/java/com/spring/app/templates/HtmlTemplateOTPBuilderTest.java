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
    String html = new HtmlTemplateOTPBuilder().buildOtpHtml("123456");

    assertThat(html)
        .contains("<html")
        .contains("123456")
        // every placeholder in the shipped template must be filled
        .doesNotContain("{{");
  }

  @Test
  void escapesTheInsertedValue() {
    HtmlTemplateOTPBuilder builder = new HtmlTemplateOTPBuilder(resource("<p>{{OTP}}</p>"));

    assertThat(builder.buildOtpHtml("<b>&")).isEqualTo("<p>&lt;b&gt;&amp;</p>");
  }

  @Test
  void readsTheTemplateOnlyOnce() {
    AtomicInteger reads = new AtomicInteger();
    Resource counting = new ByteArrayResource("<p>{{OTP}}</p>".getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getContentAsString(Charset charset) {
        reads.incrementAndGet();
        return "<p>{{OTP}}</p>";
      }
    };
    HtmlTemplateOTPBuilder builder = new HtmlTemplateOTPBuilder(counting);

    builder.buildOtpHtml("111111");
    builder.buildOtpHtml("222222");

    assertThat(reads).hasValue(1);
  }

  @Test
  void missingTemplateFailsFast() {
    assertThatThrownBy(() -> new HtmlTemplateOTPBuilder(new ClassPathResource("templates/does-not-exist.html")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does-not-exist.html");
  }

  @Test
  void templateWithoutPlaceholderIsRejected() {
    assertThatThrownBy(() -> new HtmlTemplateOTPBuilder(resource("<p>no placeholder</p>")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("{{OTP}}");
  }
}
