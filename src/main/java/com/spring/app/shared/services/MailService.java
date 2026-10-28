package com.spring.app.shared.services;

import java.util.concurrent.Semaphore;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.MailProperties;
import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.templates.HtmlTemplateOTPBuilder;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class MailService implements MailServiceInterface {

  private final MailProperties mailProperties;
  private final JavaMailSender mailSender;
  private final Semaphore sendPermits;

  public MailService(MailProperties mailProperties, JavaMailSender mailSender) {
    this.mailProperties = mailProperties;
    this.mailSender = mailSender;
    this.sendPermits = new Semaphore(mailProperties.getMaxConcurrentSends());
  }

  public void sendOtpEmail(String to, String otp) throws MessagingException {
    String htmlContent = HtmlTemplateOTPBuilder.buildOtpHtml(otp);

    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setTo(to);
    helper.setSubject(String.format("Your OTP Code from %s", mailProperties.getApp()));
    helper.setText(htmlContent, true);
    helper.setFrom(mailProperties.getFrom());

    send(message);
  }

  public void sendContentEmail(String to, String subject, String content) throws MessagingException {
    boolean isHtml = isHtmlContent(content);

    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setTo(to);
    helper.setSubject(subject);
    helper.setText(content, isHtml);
    helper.setFrom(mailProperties.getFrom());

    send(message);
  }

  // Callers run on virtual threads (emailExecutor), so waiting for a permit
  // costs no OS thread - the semaphore, not a thread pool, is what bounds the
  // number of open SMTP sessions.
  private void send(MimeMessage message) {
    try {
      sendPermits.acquire();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new MailSendException("Interrupted while waiting to send e-mail", ex);
    }
    try {
      mailSender.send(message);
    } finally {
      sendPermits.release();
    }
  }

  private boolean isHtmlContent(String content) {
    try {
      Document doc = Jsoup.parse(content);
      return !doc.body().children().isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

}
