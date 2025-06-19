package com.spring.app.shared.services.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.spring.app.shared.services.MailServiceInterface;
import com.spring.app.utils.HtmlTemplateBuilder;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class MailService implements MailServiceInterface{

  @Value("${spring.mail.app}")
  private String mailApp;

  @Value("${spring.mail.from}")
  private String mailFrom;

  @Autowired
  private JavaMailSender mailSender;

  public void sendOtpEmail(String to, String otp) throws MessagingException {
    String htmlContent = HtmlTemplateBuilder.buildOtpHtml(otp);

    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setTo(to);
    helper.setSubject(String.format("Your OTP Code from %s", mailApp));
    helper.setText(htmlContent, true);
    helper.setFrom(mailFrom);

    mailSender.send(message);
  }
}
