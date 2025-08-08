package com.spring.app.configs;

import java.util.Properties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.spring.app.configs.properties.MailProperties;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class MailConfig {

  private final MailProperties mailProperties;

  /**
   * Creates a JavaMailSender bean that is used by the application for sending
   * e-mails.
   * The configuration is done using the properties from the
   * application.properties/yml file.
   * 
   * @return the configured JavaMailSender object
   */
  @Bean
  JavaMailSender javaMailSender() {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(mailProperties.getHost());
    sender.setPort(mailProperties.getPort());
    sender.setUsername(mailProperties.getUsername());
    sender.setPassword(mailProperties.getPassword());

    Properties props = sender.getJavaMailProperties();
    props.put("mail.smtp.auth", mailProperties.isSmtpAuth());
    props.put("mail.smtp.starttls.enable", mailProperties.isStartTls());

    return sender;
  }
}
