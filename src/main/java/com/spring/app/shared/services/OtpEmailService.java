package com.spring.app.shared.services;

import com.spring.app.modules.auth.services.OtpAttemptService;
import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.utils.OtpFunctionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpEmailService {

  public static final int OTP_EXPIRATION_SECONDS = 3 * 60;

  private final OtpFunctionUtil otpFunction;
  private final MailServiceInterface mailService;
  private final OtpAttemptService otpAttemptService;

  /**
   * Generates and stores a fresh OTP, resets the wrong-guess counters (the new
   * OTP replaces any older one), then mails it. Runs on the bounded
   * {@code emailExecutor}; never throws - a failure is logged (address masked,
   * exception type only at ERROR, full exception at DEBUG) and the user can
   * request a new OTP.
   */
  @Async("emailExecutor")
  public void sendOtpEmailAsync(String email) {
    try {
      String otp = otpFunction.generateOtp();
      otpFunction.storeOtp(email, otp, OTP_EXPIRATION_SECONDS);
      // Only after the store: if it failed (or the task was dropped), the old
      // OTP is still live and must keep its count. Fails open by itself.
      otpAttemptService.resetAll(email);
      mailService.sendOtpEmail(email, otp);
    } catch (Exception e) {
      // Exception messages can embed the raw address (e.g. an SMTP
      // "550 5.1.1 <user@host>" reply), so ERROR gets the type only.
      log.error("Failed to send OTP e-mail to {} ({})", maskEmail(email), e.getClass().getSimpleName());
      log.debug("OTP e-mail failure details for {}", maskEmail(email), e);
    }
  }

  /** {@code alice@example.com} -> {@code a***@example.com}; anything else -> {@code ***}. */
  static String maskEmail(String email) {
    if (email == null) {
      return "***";
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
