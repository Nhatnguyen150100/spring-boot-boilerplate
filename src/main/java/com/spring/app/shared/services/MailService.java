package com.spring.app.shared.services;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.MailProperties;
import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.templates.HtmlTemplateOTPBuilder;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

/**
 * Builds and sends application e-mails.
 *
 * <p>
 * Callers run on the bounded {@code emailExecutor} pool (see AsyncConfig),
 * whose max-size is what caps the number of concurrent SMTP sessions; there is
 * no second limiter here. SMTP timeouts are set on the JavaMailSender
 * (MailConfig).
 * </p>
 */
@Service
@RequiredArgsConstructor
public class MailService implements MailServiceInterface {

  private final MailProperties mailProperties;
  private final JavaMailSender mailSender;
  private final HtmlTemplateOTPBuilder otpTemplate;

  @Override
  public void sendOtpEmail(String to, String otp) throws MessagingException {
    MimeMessage message = mailSender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setTo(to);
    helper.setSubject(String.format("Your OTP Code from %s", mailProperties.getApp()));
    helper.setText(otpTemplate.buildOtpHtml(otp), true);
    helper.setFrom(mailProperties.getFrom());

    mailSender.send(message);
  }
}
