package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.spring.app.configs.properties.MailProperties;

class MailConfigTest {

  @Test
  void appliesSmtpTimeoutsInMilliseconds() {
    MailProperties props = new MailProperties();
    props.setConnectionTimeout(Duration.ofSeconds(3));
    props.setReadTimeout(Duration.ofSeconds(4));
    props.setWriteTimeout(Duration.ofMillis(5500));

    JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig(props).javaMailSender();
    Properties mail = sender.getJavaMailProperties();

    // getProperty only returns String values - jakarta.mail parses them as int millis
    assertThat(mail.getProperty("mail.smtp.connectiontimeout")).isEqualTo("3000");
    assertThat(mail.getProperty("mail.smtp.timeout")).isEqualTo("4000");
    assertThat(mail.getProperty("mail.smtp.writetimeout")).isEqualTo("5500");
  }

  @Test
  void defaultsAreTenSeconds() {
    JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig(new MailProperties()).javaMailSender();
    Properties mail = sender.getJavaMailProperties();

    assertThat(mail.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
    assertThat(mail.getProperty("mail.smtp.timeout")).isEqualTo("10000");
    assertThat(mail.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
  }
}
