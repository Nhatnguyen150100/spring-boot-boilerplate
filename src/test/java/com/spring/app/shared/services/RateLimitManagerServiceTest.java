package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.spring.app.configs.properties.RateLimitProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.interfaces.RedisServiceInterface;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class RateLimitManagerServiceTest {

  @Mock
  private RedisServiceInterface redisService;

  private final RateLimitProperties properties = new RateLimitProperties();
  private RateLimitManagerService service;

  private final Logger logger = (Logger) LoggerFactory.getLogger(RateLimitManagerService.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Level originalLevel;

  @BeforeEach
  void setUp() {
    service = new RateLimitManagerService(redisService, properties);
    // capture instead of printing, at DEBUG whatever the global logging config is
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

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      email:john.doe@example.com                    | email:j***@example.com
      rate_limit:AUTH:minute:email:john@example.com | rate_limit:AUTH:minute:email:j***@example.com
      @example.com                                  | ***@example.com
      203.0.113.9                                   | 203.0.113.9
      rate_limit:API:day:203.0.113.9                | rate_limit:API:day:203.0.113.9
      """)
  void masksTheLocalPartOfEmailIdentifiers(String raw, String masked) {
    assertThat(RateLimitManagerService.maskIdentifier(raw)).isEqualTo(masked);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', textBlock = """
      email:3f2a9c4be1d07a5566f0c1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6
      2001:db8:1234:5678::/64
      """)
  void leavesHashedAndIpv6IdentifiersUntouched(String identifier) {
    assertThat(RateLimitManagerService.maskIdentifier(identifier)).isEqualTo(identifier);
  }

  @Test
  void maskingNullIsNull() {
    assertThat(RateLimitManagerService.maskIdentifier(null)).isNull();
  }

  @Test
  void eachQuotaUsesItsOwnWindowLength() {
    when(redisService.incrementRateLimit(anyString(), anyLong())).thenReturn(1L);

    assertThat(service.checkRateLimit(ERateLimitEndpoint.API, "203.0.113.9")).isTrue();

    verify(redisService).incrementRateLimit("rate_limit:API:minute:203.0.113.9", 60L);
    verify(redisService).incrementRateLimit("rate_limit:API:hour:203.0.113.9", 3600L);
    verify(redisService).incrementRateLimit("rate_limit:API:day:203.0.113.9", 86400L);
  }

  @Test
  void exceededLimitThrowsAndLogsOnlyTheMaskedEmailAtDebug() {
    properties.getAuth().setRequestsPerMinute(1);
    when(redisService.incrementRateLimit("rate_limit:AUTH:minute:email:john@example.com", 60L)).thenReturn(2L);

    assertThatThrownBy(() -> service.checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, "email:john@example.com"))
        .isInstanceOf(RateLimitExceededException.class);

    // The 429 itself is logged at WARN by GlobalExceptionHandler; one WARN per rejection is enough.
    assertThat(logs.list)
        .filteredOn(event -> event.getFormattedMessage().startsWith("Rate limit exceeded"))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
          assertThat(event.getFormattedMessage())
              .contains("email:j***@example.com")
              .doesNotContain("john@example.com");
        });
  }
}
