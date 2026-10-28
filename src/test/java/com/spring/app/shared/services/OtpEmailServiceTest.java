package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.utils.OtpFunctionUtil;

import jakarta.mail.MessagingException;

/** Called directly (no Spring proxy), so the "async" method runs synchronously. */
@ExtendWith(MockitoExtension.class)
class OtpEmailServiceTest {

  @Mock
  private OtpFunctionUtil otpFunction;

  @Mock
  private MailServiceInterface mailService;

  private OtpEmailService service;

  @BeforeEach
  void setUp() {
    service = new OtpEmailService(otpFunction, mailService);
  }

  @Test
  void storesTheOtpThenMailsTheSameOtp() throws Exception {
    when(otpFunction.generateOtp()).thenReturn("123456");

    service.sendOtpEmailAsync("alice@example.com");

    InOrder order = inOrder(otpFunction, mailService);
    order.verify(otpFunction).storeOtp("alice@example.com", "123456", OtpEmailService.OTP_EXPIRATION_SECONDS);
    order.verify(mailService).sendOtpEmail("alice@example.com", "123456");
  }

  @Test
  void mailFailureDoesNotPropagate() throws Exception {
    when(otpFunction.generateOtp()).thenReturn("123456");
    doThrow(new MessagingException("smtp down")).when(mailService).sendOtpEmail(anyString(), anyString());

    assertThatCode(() -> service.sendOtpEmailAsync("alice@example.com")).doesNotThrowAnyException();
  }

  @Test
  void storeFailureSkipsTheMail() {
    when(otpFunction.generateOtp()).thenReturn("123456");
    doThrow(new IllegalStateException("redis down")).when(otpFunction).storeOtp(anyString(), anyString(), anyLong());

    assertThatCode(() -> service.sendOtpEmailAsync("alice@example.com")).doesNotThrowAnyException();
    verifyNoInteractions(mailService);
  }

  @ParameterizedTest
  @CsvSource({
      "alice@example.com, a***@example.com",
      "b@x.io, b***@x.io",
      "@example.com, ***",
      "not-an-email, ***"
  })
  void masksEmailForLogs(String email, String expected) {
    assertThat(OtpEmailService.maskEmail(email)).isEqualTo(expected);
  }

  @Test
  void masksNullEmail() {
    assertThat(OtpEmailService.maskEmail(null)).isEqualTo("***");
  }
}
