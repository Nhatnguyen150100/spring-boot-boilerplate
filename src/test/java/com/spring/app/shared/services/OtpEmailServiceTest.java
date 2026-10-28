package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;

import com.spring.app.modules.auth.services.OtpAttemptService;
import com.spring.app.shared.interfaces.MailServiceInterface;
import com.spring.app.utils.OtpFunctionUtil;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.MessagingException;

/** Called directly (no Spring proxy), so the "async" method runs synchronously. */
@ExtendWith(MockitoExtension.class)
class OtpEmailServiceTest {

  @Mock
  private OtpFunctionUtil otpFunction;

  @Mock
  private MailServiceInterface mailService;

  @Mock
  private OtpAttemptService otpAttemptService;

  private OtpEmailService service;

  private final Logger logger = (Logger) LoggerFactory.getLogger(OtpEmailService.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    service = new OtpEmailService(otpFunction, mailService, otpAttemptService);
    // capture instead of printing: keeps test output clean and lets us inspect it
    originalLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logger.setAdditive(false);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
    logger.setAdditive(true);
    logger.setLevel(originalLevel);
  }

  @Test
  void storesTheOtpThenMailsTheSameOtp() throws Exception {
    when(otpFunction.generateOtp()).thenReturn("123456");

    service.sendOtpEmailAsync("alice@example.com");

    InOrder order = inOrder(otpFunction, mailService);
    order.verify(otpFunction).storeOtp("alice@example.com", "123456", OtpEmailService.OTP_EXPIRATION_SECONDS);
    order.verify(mailService).sendOtpEmail("alice@example.com", "123456");
  }

  /** The new OTP replaces any older one, so its wrong-guess budget starts over - but only once it is stored. */
  @Test
  void resetsTheWrongGuessCountersOnlyAfterTheNewOtpIsStored() throws Exception {
    when(otpFunction.generateOtp()).thenReturn("123456");

    service.sendOtpEmailAsync("alice@example.com");

    InOrder order = inOrder(otpFunction, otpAttemptService, mailService);
    order.verify(otpFunction).storeOtp("alice@example.com", "123456", OtpEmailService.OTP_EXPIRATION_SECONDS);
    order.verify(otpAttemptService).resetAll("alice@example.com");
    order.verify(mailService).sendOtpEmail("alice@example.com", "123456");
  }

  @Test
  void aFailedStoreLeavesTheWrongGuessCountersAlone() {
    when(otpFunction.generateOtp()).thenReturn("123456");
    doThrow(new IllegalStateException("redis down")).when(otpFunction).storeOtp(anyString(), anyString(), anyLong());

    service.sendOtpEmailAsync("alice@example.com");

    verify(otpAttemptService, never()).resetAll(anyString());
  }

  @Test
  void mailFailureDoesNotPropagate() throws Exception {
    when(otpFunction.generateOtp()).thenReturn("123456");
    doThrow(new MessagingException("smtp down")).when(mailService).sendOtpEmail(anyString(), anyString());

    assertThatCode(() -> service.sendOtpEmailAsync("alice@example.com")).doesNotThrowAnyException();
  }

  @Test
  void failureIsLoggedAtErrorWithoutTheRawAddress() throws Exception {
    when(otpFunction.generateOtp()).thenReturn("123456");
    doThrow(new MailSendException("550 5.1.1 <alice@example.com>: Recipient address rejected"))
        .when(mailService).sendOtpEmail(anyString(), anyString());

    service.sendOtpEmailAsync("alice@example.com");

    List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    assertThat(errors).hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getFormattedMessage())
        .contains("a***@example.com")
        .contains("MailSendException")
        .doesNotContain("alice@example.com");
    // no throwable at ERROR: its message would carry the raw address
    assertThat(error.getThrowableProxy()).isNull();
    // the full exception is only available at DEBUG
    assertThat(logs.list).anySatisfy(e -> {
      assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
      assertThat(e.getThrowableProxy()).isNotNull();
    });
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
