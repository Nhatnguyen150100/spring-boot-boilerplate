package com.spring.app.shared.interfaces;

import jakarta.mail.MessagingException;

public interface MailServiceInterface {
  void sendOtpEmail(String to, String otp) throws MessagingException;
}
