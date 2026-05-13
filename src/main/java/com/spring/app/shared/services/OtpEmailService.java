package com.spring.app.shared.services;

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

  @Async("emailExecutor")
  public void sendOtpEmailAsync(String email) {
    try {
      String otp = otpFunction.generateOtp();
      otpFunction.storeOtp(email, otp, OTP_EXPIRATION_SECONDS);
      mailService.sendOtpEmail(email, otp);
    } catch (Exception e) {
      log.error("Failed to send OTP email to: {}", email, e);
    }
  }
}
