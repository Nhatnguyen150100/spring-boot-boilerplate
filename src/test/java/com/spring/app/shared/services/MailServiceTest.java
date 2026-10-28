package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import com.spring.app.configs.properties.MailProperties;
import com.spring.app.templates.HtmlTemplateOTPBuilder;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/** No SMTP server: the sender is a mock, the message is inspected in memory. */
class MailServiceTest {

  @Test
  void sendsOtpMailRenderedFromClasspathTemplate() throws Exception {
    MailProperties props = new MailProperties();
    props.setApp("TEST_APP");
    props.setFrom("noreply@example.com");
    JavaMailSender sender = mock(JavaMailSender.class);
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);

    new MailService(props, sender, new HtmlTemplateOTPBuilder()).sendOtpEmail("user@example.com", "123456");

    verify(sender).send(message);
    assertThat(message.getSubject()).isEqualTo("Your OTP Code from TEST_APP");
    assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("user@example.com");
    assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
    assertThat(htmlBody(message.getContent()))
        .contains("123456")
        // OtpEmailService.OTP_EXPIRATION_SECONDS = 180
        .contains("3 minutes")
        .doesNotContain("{{");
  }

  /** Walks the mixed/related multipart MimeMessageHelper builds and returns the HTML part. */
  private static String htmlBody(Object content) throws Exception {
    if (content instanceof String text) {
      return text;
    }
    if (content instanceof Multipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        String found = htmlBody(multipart.getBodyPart(i).getContent());
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
