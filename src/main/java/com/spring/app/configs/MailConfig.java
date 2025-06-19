package com.spring.app.configs;

import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class MailConfig {

  @Value("${spring.mail.host}")
  private String mailHost;

  @Value("${spring.mail.port}")
  private int mailPort;

  @Value("${spring.mail.username}")
  private String mailUsername;

  @Value("${spring.mail.password}")
  private String mailPassword;

  @Value("${spring.mail.properties.mail.smtp.auth}")
  private boolean smtpAuth;

  @Value("${spring.mail.properties.mail.smtp.starttls.enable}")
  private boolean starttls;

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
    sender.setHost(mailHost);
    sender.setPort(mailPort);
    sender.setUsername(mailUsername);
    sender.setPassword(mailPassword);

    Properties props = sender.getJavaMailProperties();
    props.put("mail.smtp.auth", smtpAuth);
    props.put("mail.smtp.starttls.enable", starttls);

    return sender;
  }
}
