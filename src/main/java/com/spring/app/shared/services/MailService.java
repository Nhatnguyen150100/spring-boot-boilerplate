package com.spring.app.shared.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.MailProperties;
import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.templates.HtmlTemplateOTPBuilder;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MailService implements MailServiceInterface {

  private final MailProperties mailProperties;

  @Autowired
  private JavaMailSender mailSender;

  public void sendOtpEmail(String to, String otp) throws MessagingException {
    String htmlContent = HtmlTemplateOTPBuilder.buildOtpHtml(otp);

    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setTo(to);
    helper.setSubject(String.format("Your OTP Code from %s", mailProperties.getApp()));
    helper.setText(htmlContent, true);
    helper.setFrom(mailProperties.getFrom());

    mailSender.send(message);
  }

  public void sendOtpEmail(String to, String subject, String content) throws MessagingException {
    boolean isHtml = isHtmlContent(content);

    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setTo(to);
    helper.setSubject(subject);
    helper.setText(content, isHtml);
    helper.setFrom(mailProperties.getFrom());

    mailSender.send(message);
  }

  private boolean isHtmlContent(String content) {
    try {
      Document doc = Jsoup.parse(content);
      return doc.body().children().size() > 0;
    } catch (Exception e) {
      return false;
    }
  }

}
