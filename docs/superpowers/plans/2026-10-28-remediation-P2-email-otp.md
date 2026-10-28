# P2 email-otp Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make OTP e-mail delivery work from the packaged jar, run it on a bounded platform-thread pool with SMTP timeouts, and harden OTP generation/storage.

**Architecture:** The OTP template becomes a Spring `@Component` that loads `classpath:templates/otp-email.html` once at startup (fail-fast) and HTML-escapes inserted values. `emailExecutor` goes back to a `ThreadPoolTaskExecutor` sized from `application.async.email.*`, with a log-and-drop rejection policy and a bounded wait on shutdown; its `max-size` replaces the `Semaphore` in `MailService` as the cap on concurrent SMTP sessions. `MailConfig` sets jakarta.mail connect/read/write timeouts from `MailProperties`. `OtpFunctionUtil` switches to `SecureRandom`, namespaces Redis keys as `otp:<lower(email)>` internally (so callers in `AuthServiceImpl` do not change), and compares in constant time.

**Tech Stack:** Java 21, Spring Boot 3.5.0 (Spring Framework 6.2), jakarta.mail via `spring-boot-starter-mail`, Hibernate Validator, JUnit 5, AssertJ, Mockito 5, `ApplicationContextRunner`.

**Spec:** `docs/superpowers/specs/2026-10-28-review-remediation-design.md` (section "### P2 email-otp", Decision 2, Global constraints, ownership table)

## Global Constraints

- Java 21, Spring Boot 3.5.x, Maven. `mvn test` must pass with nothing running (no MySQL, no Redis, no SMTP, no Docker).
- Config rules from CLAUDE.md are binding: framework keys under `spring.*`, app keys under `application.*` bound by `configs/properties/*` with `@Validated`; the six leaf properties classes keep `ignoreUnknownFields = false`; every validation-constrained property gets a **literal** override in `src/test/resources/application-test.yml`; every new env var is added to `.env.example`; profile files hold deltas only and never set `spring.profiles.active`.
- Flyway owns the schema (`src/main/resources/db/...`); any schema change is a new versioned migration, never an edit of an applied one. (P2 makes no schema change.)
- TDD: each fix lands with a test that fails before and passes after, unless the change is pure config/docs (then state how it was verified).
- Match surrounding code style: 2-space indent, Lombok, constructor injection, `ResponseBuilder` for HTTP responses.
- Commit per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Decision 2 (binding): restore `ThreadPoolTaskExecutor` for `emailExecutor` with core/max/queue settings back in `AsyncProperties` (`application.async.email.*`, `ASYNC_EMAIL_*`), add SMTP connect/read/write timeouts. HTTP requests stay on virtual threads (`spring.threads.virtual.enabled=true`). Stay on Java 21.
- File ownership (P2 owns): `AsyncConfig`, `AsyncProperties`, `MailConfig`, `MailProperties`, `MailService`, `MailServiceInterface`, `OtpEmailService`, `templates/HtmlTemplateOTPBuilder`, `resources/templates/*`, `utils/OtpFunctionUtil`, `application.yml` (`spring.threads`, `application.async`, `application.mail`), `.env.example` (mail/async). P2 may append to `application-test.yml` and add test classes in its own area. Do not edit any other file.

## Context for the executor

- P1 (persistence-cache-redis) runs before this plan. It owns `RedisService`/`RedisServiceInterface` but does not touch any P2 file. This plan only uses `RedisServiceInterface.setValue(String, Object, long, TimeUnit)`, `getValue(String)` and `delete(String)`. If P1 renamed any of these, adapt the three call sites in `OtpFunctionUtil` (Task 5) and the matching `verify(...)` lines in `OtpFunctionUtilTest` — nothing else.
- Callers that must keep compiling unchanged (owned by P3): `AuthServiceImpl` calls `otpEmailService.sendOtpEmailAsync(String email)`, `otpFunction.validateOtp(String email, String otp)` and `otpFunction.removeOtp(String email)`. All three signatures are preserved.
- `FileChecksumRegistry` (P5) injects `@Qualifier("taskExecutor") Executor`. The `taskExecutor` bean is not changed.
- The history of the restored design: `git show cd64078:src/main/java/com/spring/app/configs/AsyncConfig.java` and `git show cd64078:src/main/java/com/spring/app/configs/properties/AsyncProperties.java` (email pool `new Pool(2, 5, 10)`, removed in `92eac07`).
- Do NOT run `mvn` from several worktrees sharing one `target/` at the same time.
- Mockito on Java 21 prints a "dynamically loading agent" warning; it is harmless (P6 adds the surefire `-javaagent`).

## File Structure

| File | Action | Responsibility after this plan |
|---|---|---|
| `src/main/java/com/spring/app/templates/HtmlTemplateOTPBuilder.java` | Rewrite | `@Component`; loads + caches classpath template at startup; escapes OTP |
| `src/main/resources/templates/otp-email.html` | Modify | Drop the never-filled `{{VERIFICATION_URL}}` button |
| `src/main/resources/templates/OTP.html` | Delete | Unused |
| `src/main/java/com/spring/app/shared/services/MailService.java` | Modify (T1), rewrite (T4) | Build + send OTP mail; no semaphore, no jsoup, no `sendContentEmail` |
| `src/main/java/com/spring/app/configs/properties/AsyncProperties.java` | Modify | `task` + `email` pools; `max-size >= core-size` |
| `src/main/java/com/spring/app/configs/AsyncConfig.java` | Rewrite | `taskExecutor` (unchanged) + bounded platform `emailExecutor` |
| `src/main/java/com/spring/app/configs/properties/MailProperties.java` | Modify | + connect/read/write timeouts; − `maxConcurrentSends` |
| `src/main/java/com/spring/app/configs/MailConfig.java` | Modify | Applies SMTP timeouts to `JavaMailSenderImpl` |
| `src/main/java/com/spring/app/utils/OtpFunctionUtil.java` | Rewrite | `SecureRandom`, `otp:<lower(email)>` keys, constant-time compare |
| `src/main/java/com/spring/app/shared/services/OtpEmailService.java` | Modify | Masks e-mail in failure log |
| `src/main/resources/application.yml` | Modify | `spring.threads` comment, `application.async.email`, `application.mail` timeouts, drop `max-concurrent-sends` |
| `src/test/resources/application-test.yml` | Modify | Literal overrides for new keys; drop `max-concurrent-sends` |
| `.env.example` | Modify | `ASYNC_EMAIL_*`, `MAIL_*_TIMEOUT` |
| `src/test/java/com/spring/app/templates/HtmlTemplateOTPBuilderTest.java` | Create | Template tests |
| `src/test/java/com/spring/app/shared/services/MailServiceTest.java` | Create | OTP message assembly |
| `src/test/java/com/spring/app/configs/AsyncConfigTest.java` | Create | Email pool behaviour |
| `src/test/java/com/spring/app/configs/properties/AsyncPropertiesTest.java` | Create | Binding + validation |
| `src/test/java/com/spring/app/configs/MailConfigTest.java` | Create | Timeouts reach jakarta.mail |
| `src/test/java/com/spring/app/configs/properties/MailPropertiesTest.java` | Create | Binding + validation + removed key |
| `src/test/java/com/spring/app/utils/OtpFunctionUtilTest.java` | Create | OTP generation/keys/validation |
| `src/test/java/com/spring/app/shared/services/OtpEmailServiceTest.java` | Create | Orchestration + masking |

---

### Task 1: Load the OTP template from the classpath (critical delivery bug)

`HtmlTemplateOTPBuilder.java:10` reads `Paths.get("src/main/resources/templates/otp-email.html")`, which only exists when the JVM's working directory is the source checkout. From the jar / Docker image every OTP send throws, `OtpEmailService` swallows it, and accounts stay PENDING. Also, the template's `{{VERIFICATION_URL}}` placeholder is never replaced, so every mail ships a dead "Verify Now" link; there is no verification URL in this API (activation is `POST /auth/active-account` with the OTP), so the button is removed.

**Files:**
- Rewrite: `src/main/java/com/spring/app/templates/HtmlTemplateOTPBuilder.java` (all 16 lines)
- Modify: `src/main/resources/templates/otp-email.html:43-49` (remove the button block)
- Delete: `src/main/resources/templates/OTP.html`
- Modify: `src/main/java/com/spring/app/shared/services/MailService.java:20-33` (inject the builder)
- Create: `src/test/java/com/spring/app/templates/HtmlTemplateOTPBuilderTest.java`
- Create: `src/test/java/com/spring/app/shared/services/MailServiceTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `@Component public class HtmlTemplateOTPBuilder` with `public HtmlTemplateOTPBuilder()` (Spring uses this), package-private `HtmlTemplateOTPBuilder(org.springframework.core.io.Resource)` (tests), `public String buildOtpHtml(String otp)`, constants `static final String TEMPLATE_LOCATION = "templates/otp-email.html"`, `static final String OTP_PLACEHOLDER = "{{OTP}}"`.
  - `MailService` constructor `MailService(MailProperties, JavaMailSender, HtmlTemplateOTPBuilder)` — same parameter order Task 4's `@RequiredArgsConstructor` produces.

- [ ] **Step 1: Write the failing template test**

Create `src/test/java/com/spring/app/templates/HtmlTemplateOTPBuilderTest.java`:

```java
package com.spring.app.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * The template must come from the classpath (it is not on disk inside the
 * jar / Docker image), be read once, and have the OTP HTML-escaped.
 */
class HtmlTemplateOTPBuilderTest {

  private static Resource resource(String content) {
    return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rendersPackagedTemplateFromClasspath() {
    String html = new HtmlTemplateOTPBuilder().buildOtpHtml("123456");

    assertThat(html)
        .contains("<html")
        .contains("123456")
        // every placeholder in the shipped template must be filled
        .doesNotContain("{{");
  }

  @Test
  void escapesTheInsertedValue() {
    HtmlTemplateOTPBuilder builder = new HtmlTemplateOTPBuilder(resource("<p>{{OTP}}</p>"));

    assertThat(builder.buildOtpHtml("<b>&")).isEqualTo("<p>&lt;b&gt;&amp;</p>");
  }

  @Test
  void readsTheTemplateOnlyOnce() {
    AtomicInteger reads = new AtomicInteger();
    Resource counting = new ByteArrayResource("<p>{{OTP}}</p>".getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getContentAsString(Charset charset) {
        reads.incrementAndGet();
        return "<p>{{OTP}}</p>";
      }
    };
    HtmlTemplateOTPBuilder builder = new HtmlTemplateOTPBuilder(counting);

    builder.buildOtpHtml("111111");
    builder.buildOtpHtml("222222");

    assertThat(reads).hasValue(1);
  }

  @Test
  void missingTemplateFailsFast() {
    assertThatThrownBy(() -> new HtmlTemplateOTPBuilder(new ClassPathResource("templates/does-not-exist.html")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does-not-exist.html");
  }

  @Test
  void templateWithoutPlaceholderIsRejected() {
    assertThatThrownBy(() -> new HtmlTemplateOTPBuilder(resource("<p>no placeholder</p>")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("{{OTP}}");
  }
}
```

- [ ] **Step 2: Write the failing MailService test**

Create `src/test/java/com/spring/app/shared/services/MailServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import com.spring.app.configs.properties.MailProperties;
import com.spring.app.templates.HtmlTemplateOTPBuilder;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/** No SMTP server: the sender is a mock, the message is inspected in memory. */
class MailServiceTest {

  @Test
  void sendsOtpMailRenderedFromClasspathTemplate() throws Exception {
    MailProperties props = new MailProperties();
    props.setApp("TEST_APP");
    props.setFrom("noreply@example.com");
    JavaMailSender sender = mock(JavaMailSender.class);
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);

    new MailService(props, sender, new HtmlTemplateOTPBuilder()).sendOtpEmail("user@example.com", "123456");

    verify(sender).send(message);
    assertThat(message.getSubject()).isEqualTo("Your OTP Code from TEST_APP");
    assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("user@example.com");
    assertThat(message.getFrom()[0].toString()).isEqualTo("noreply@example.com");
    assertThat(htmlBody(message.getContent())).contains("123456").doesNotContain("{{");
  }

  /** Walks the mixed/related multipart MimeMessageHelper builds and returns the HTML part. */
  private static String htmlBody(Object content) throws Exception {
    if (content instanceof String text) {
      return text;
    }
    if (content instanceof Multipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        String found = htmlBody(multipart.getBodyPart(i).getContent());
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q test -Dtest='HtmlTemplateOTPBuilderTest,MailServiceTest'`
Expected: FAIL — test compilation error: `constructor HtmlTemplateOTPBuilder in class HtmlTemplateOTPBuilder cannot be applied to given types` (no `Resource` constructor yet) and `constructor MailService in class MailService cannot be applied to given types`.

- [ ] **Step 4: Rewrite `HtmlTemplateOTPBuilder`**

Replace the whole content of `src/main/java/com/spring/app/templates/HtmlTemplateOTPBuilder.java` with:

```java
package com.spring.app.templates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders the OTP e-mail body from {@code classpath:templates/otp-email.html}.
 *
 * <p>
 * The template is read once, when the bean is created, and always from the
 * classpath - never from the working directory - so it works from the
 * packaged jar and the Docker image. A missing or broken template stops the
 * application from starting instead of silently failing every OTP send.
 * Inserted values are HTML-escaped.
 * </p>
 */
@Component
public class HtmlTemplateOTPBuilder {

  static final String TEMPLATE_LOCATION = "templates/otp-email.html";
  static final String OTP_PLACEHOLDER = "{{OTP}}";

  private final String template;

  public HtmlTemplateOTPBuilder() {
    this(new ClassPathResource(TEMPLATE_LOCATION));
  }

  HtmlTemplateOTPBuilder(Resource resource) {
    this.template = load(resource);
  }

  public String buildOtpHtml(String otp) {
    Objects.requireNonNull(otp, "otp must not be null");
    return template.replace(OTP_PLACEHOLDER, HtmlUtils.htmlEscape(otp));
  }

  private static String load(Resource resource) {
    String content;
    try {
      content = resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read OTP e-mail template " + resource.getDescription(), e);
    }
    if (!content.contains(OTP_PLACEHOLDER)) {
      throw new IllegalStateException(
          "OTP e-mail template " + resource.getDescription() + " has no " + OTP_PLACEHOLDER + " placeholder");
    }
    return content;
  }
}
```

(Spring picks the public no-arg constructor: with two constructors and none annotated, it uses the default one.)

- [ ] **Step 5: Remove the dead "Verify Now" button from the template**

In `src/main/resources/templates/otp-email.html`, delete lines 43-49 — exactly this block (the blank line before it plus the `<p>`):

```html

              <p align="center" style="margin: 20px 0;">
                <a href="{{VERIFICATION_URL}}"
                  style="display: inline-block; background-color: #2e6da4; color: white; text-decoration: none; padding: 12px 24px; border-radius: 5px; font-weight: bold;">
                  ✓ Verify Now
                </a>
              </p>
```

After the edit, the `</table>` that closes the OTP box (old line 42) is followed directly by the blank line and `<table width="100%" cellpadding="0" cellspacing="0" style="margin-top: 30px;">` (old line 51).

- [ ] **Step 6: Delete the unused template**

```bash
git rm src/main/resources/templates/OTP.html
grep -rn "OTP.html" src || echo "no references"
```
Expected: `no references`.

- [ ] **Step 7: Inject the builder into `MailService`**

In `src/main/java/com/spring/app/shared/services/MailService.java`, replace lines 22-33:

```java
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
```

with:

```java
  private final MailProperties mailProperties;
  private final JavaMailSender mailSender;
  private final HtmlTemplateOTPBuilder otpTemplate;
  private final Semaphore sendPermits;

  public MailService(MailProperties mailProperties, JavaMailSender mailSender, HtmlTemplateOTPBuilder otpTemplate) {
    this.mailProperties = mailProperties;
    this.mailSender = mailSender;
    this.otpTemplate = otpTemplate;
    this.sendPermits = new Semaphore(mailProperties.getMaxConcurrentSends());
  }

  @Override
  public void sendOtpEmail(String to, String otp) throws MessagingException {
    String htmlContent = otpTemplate.buildOtpHtml(otp);
```

(The semaphore is removed in Task 4, after the bounded pool exists.)

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn -q test -Dtest='HtmlTemplateOTPBuilderTest,MailServiceTest'`
Expected: PASS (6 tests, 0 failures).

Run: `mvn -q test -Dtest=BaseSpringApplicationTests`
Expected: PASS (the new `@Component` loads the template at context start).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/spring/app/templates/HtmlTemplateOTPBuilder.java \
  src/main/resources/templates/otp-email.html \
  src/main/java/com/spring/app/shared/services/MailService.java \
  src/test/java/com/spring/app/templates/HtmlTemplateOTPBuilderTest.java \
  src/test/java/com/spring/app/shared/services/MailServiceTest.java
git commit -m "fix(mail): load OTP template from classpath, cache and escape it

The template was read from src/main/resources on disk, which does not exist
in the jar or Docker image, so every OTP send failed silently. Load it once
via ClassPathResource (fail-fast at startup), HTML-escape the OTP, drop the
never-filled {{VERIFICATION_URL}} button and the unused OTP.html.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`git rm` in Step 6 already staged the deletion.)

---

### Task 2: Bounded platform-thread `emailExecutor` (Decision 2)

jakarta.mail's `SMTPTransport` does socket I/O inside `synchronized` methods; on Java 21 that pins a virtual thread to its carrier, and `emailExecutor` currently shares carriers with Tomcat's request threads. Restore a `ThreadPoolTaskExecutor` sized from `application.async.email.*`, drop (and log) tasks when saturated instead of throwing into the HTTP caller, and wait for running sends on shutdown. Fix the misleading `spring.threads` comment.

**Files:**
- Modify: `src/main/java/com/spring/app/configs/properties/AsyncProperties.java:20-37`
- Rewrite: `src/main/java/com/spring/app/configs/AsyncConfig.java` (all 49 lines)
- Modify: `src/main/resources/application.yml:105-113` (`spring.threads`) and `:179-185` (`application.async`)
- Modify: `src/test/resources/application-test.yml:67-71` (append `email` pool)
- Modify: `.env.example` (add `ASYNC_EMAIL_*`)
- Create: `src/test/java/com/spring/app/configs/properties/AsyncPropertiesTest.java`
- Create: `src/test/java/com/spring/app/configs/AsyncConfigTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `AsyncProperties#getEmail()` / `setEmail(AsyncProperties.Pool)`; default `new Pool(5, 5, 100)`.
  - `AsyncProperties.Pool#isMaxSizeAtLeastCoreSize()` (`@AssertTrue`).
  - `AsyncConfig#emailExecutor()` returns `ThreadPoolTaskExecutor` (bean name `emailExecutor`, thread prefix `EMAIL-TASK-`); constants `static final String EMAIL_THREAD_NAME_PREFIX`, `static final int EMAIL_AWAIT_TERMINATION_SECONDS = 30`.
  - Config keys `application.async.email.core-size|max-size|queue-capacity`, env vars `ASYNC_EMAIL_CORE_SIZE|ASYNC_EMAIL_MAX_SIZE|ASYNC_EMAIL_QUEUE_CAPACITY`.

- [ ] **Step 1: Write the failing properties test**

Create `src/test/java/com/spring/app/configs/properties/AsyncPropertiesTest.java`:

```java
package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link AsyncProperties} the way the application does (strict binding +
 * {@code @Validated}), without application.yml and without a full context.
 */
class AsyncPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AsyncProperties.class)
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Config.class);

  @Test
  void bindsEmailPool() {
    runner.withPropertyValues(
        "application.async.email.core-size=3",
        "application.async.email.max-size=6",
        "application.async.email.queue-capacity=40")
        .run(context -> {
          assertThat(context).hasNotFailed();
          AsyncProperties.Pool email = context.getBean(AsyncProperties.class).getEmail();
          assertThat(email.getCoreSize()).isEqualTo(3);
          assertThat(email.getMaxSize()).isEqualTo(6);
          assertThat(email.getQueueCapacity()).isEqualTo(40);
        });
  }

  @Test
  void defaultsToBoundedEmailPool() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      AsyncProperties.Pool email = context.getBean(AsyncProperties.class).getEmail();
      assertThat(email.getCoreSize()).isEqualTo(5);
      assertThat(email.getMaxSize()).isEqualTo(5);
      assertThat(email.getQueueCapacity()).isEqualTo(100);
    });
  }

  @Test
  void rejectsZeroCoreSize() {
    runner.withPropertyValues("application.async.email.core-size=0")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("core-size must be at least 1");
        });
  }

  @Test
  void rejectsMaxSizeBelowCoreSize() {
    runner.withPropertyValues(
        "application.async.email.core-size=4",
        "application.async.email.max-size=2")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("max-size must be greater than or equal to core-size");
        });
  }
}
```

- [ ] **Step 2: Write the failing executor test**

Create `src/test/java/com/spring/app/configs/AsyncConfigTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.spring.app.configs.properties.AsyncProperties;

/** Exercises the real emailExecutor bean method, without a Spring context. */
class AsyncConfigTest {

  private ThreadPoolTaskExecutor executor;

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.shutdown();
    }
  }

  private ThreadPoolTaskExecutor emailExecutor(int core, int max, int queue) {
    AsyncProperties properties = new AsyncProperties();
    properties.setEmail(new AsyncProperties.Pool(core, max, queue));
    executor = new AsyncConfig(properties).emailExecutor();
    return executor;
  }

  @Test
  void emailExecutorIsSizedFromProperties() {
    ThreadPoolTaskExecutor ex = emailExecutor(2, 4, 7);

    assertThat(ex.getCorePoolSize()).isEqualTo(2);
    assertThat(ex.getMaxPoolSize()).isEqualTo(4);
    assertThat(ex.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(7);
  }

  @Test
  void runsOnNamedPlatformThreadsNotVirtualThreads() throws Exception {
    ThreadPoolTaskExecutor ex = emailExecutor(1, 1, 1);
    Callable<Thread> currentThread = Thread::currentThread;

    Thread worker = ex.submit(currentThread).get(5, TimeUnit.SECONDS);

    assertThat(worker.isVirtual()).isFalse();
    assertThat(worker.getName()).startsWith(AsyncConfig.EMAIL_THREAD_NAME_PREFIX);
  }

  @Test
  void saturatedPoolDropsTheTaskInsteadOfThrowingAtTheCaller() throws Exception {
    ThreadPoolTaskExecutor ex = emailExecutor(1, 1, 1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ex.execute(() -> {
      started.countDown();
      awaitQuietly(release);
    });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    ex.execute(() -> {
    }); // occupies the single queue slot

    AtomicBoolean ran = new AtomicBoolean(false);
    assertThatCode(() -> ex.execute(() -> ran.set(true))).doesNotThrowAnyException();

    release.countDown();
    ex.shutdown();
    assertThat(ran).isFalse();
  }

  @Test
  void shutdownWaitsForRunningSends() throws Exception {
    ThreadPoolTaskExecutor ex = emailExecutor(1, 1, 1);
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean finished = new AtomicBoolean(false);
    ex.execute(() -> {
      started.countDown();
      finished.set(sleptWithoutInterruption(300));
    });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    ex.shutdown();

    assertThat(finished).isTrue();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean sleptWithoutInterruption(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q test -Dtest='AsyncPropertiesTest,AsyncConfigTest'`
Expected: FAIL — test compilation error: `cannot find symbol: method getEmail()` / `setEmail(...)` in `AsyncProperties`, `incompatible types: java.util.concurrent.Executor cannot be converted to ThreadPoolTaskExecutor`, `EMAIL_THREAD_NAME_PREFIX has private access in com.spring.app.configs.AsyncConfig`.

- [ ] **Step 4: Add the `email` pool and the size check to `AsyncProperties`**

In `src/main/java/com/spring/app/configs/properties/AsyncProperties.java`:

Replace the import block line 8:

```java
import jakarta.validation.constraints.Min;
```

with:

```java
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
```

Replace lines 20-37 (from `/** General-purpose pool` to the closing `}` of `Pool`):

```java
  /** General-purpose pool, exposed as the {@code taskExecutor} bean. */
  @Valid
  private Pool task = new Pool(5, 10, 25);

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Pool {

    @Min(value = 1, message = "core-size must be at least 1")
    private int coreSize = 1;

    @Min(value = 1, message = "max-size must be at least 1")
    private int maxSize = 1;

    @Min(value = 1, message = "queue-capacity must be at least 1")
    private int queueCapacity = 1;
  }
```

with:

```java
  /** General-purpose pool, exposed as the {@code taskExecutor} bean. */
  @Valid
  private Pool task = new Pool(5, 10, 25);

  /**
   * Bounded pool of platform threads for outgoing e-mail, exposed as the
   * {@code emailExecutor} bean. Its {@code max-size} is also the cap on
   * concurrent SMTP sessions. Deliberately not virtual threads: jakarta.mail's
   * SMTPTransport does socket I/O inside synchronized methods, which pins the
   * carrier thread on Java 21.
   */
  @Valid
  private Pool email = new Pool(5, 5, 100);

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Pool {

    @Min(value = 1, message = "core-size must be at least 1")
    private int coreSize = 1;

    /**
     * Only used once the queue is full: a ThreadPoolExecutor grows past
     * core-size only when it cannot queue.
     */
    @Min(value = 1, message = "max-size must be at least 1")
    private int maxSize = 1;

    @Min(value = 1, message = "queue-capacity must be at least 1")
    private int queueCapacity = 1;

    /** ThreadPoolExecutor rejects max < core at startup with a less helpful error. */
    @AssertTrue(message = "max-size must be greater than or equal to core-size")
    public boolean isMaxSizeAtLeastCoreSize() {
      return maxSize >= coreSize;
    }
  }
```

- [ ] **Step 5: Rewrite `AsyncConfig`**

Replace the whole content of `src/main/java/com/spring/app/configs/AsyncConfig.java` with:

```java
package com.spring.app.configs;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.spring.app.configs.properties.AsyncProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

  private final AsyncProperties asyncProperties;

  private static final String TASK_THREAD_NAME_PREFIX = "ASYNC-TASK-";
  static final String EMAIL_THREAD_NAME_PREFIX = "EMAIL-TASK-";

  /** Upper bound on how long shutdown waits for queued / running sends. */
  static final int EMAIL_AWAIT_TERMINATION_SECONDS = 30;

  @Bean(name = "taskExecutor")
  Executor taskExecutor() {
    AsyncProperties.Pool pool = asyncProperties.getTask();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.getCoreSize());
    executor.setMaxPoolSize(pool.getMaxSize());
    executor.setQueueCapacity(pool.getQueueCapacity());
    executor.setThreadNamePrefix(TASK_THREAD_NAME_PREFIX);
    executor.initialize();
    return executor;
  }

  /**
   * Bounded pool of PLATFORM threads for outgoing e-mail.
   *
   * <p>
   * Not virtual threads: jakarta.mail's SMTPTransport does its socket I/O
   * inside synchronized methods, which on Java 21 pins a virtual thread to its
   * carrier. HTTP requests run on virtual threads too
   * ({@code spring.threads.virtual.enabled}), so a few slow SMTP servers would
   * starve request handling. A fixed pool keeps that blocking off the carriers;
   * its max-size is also the cap on concurrent SMTP sessions.
   * </p>
   *
   * <p>
   * When pool and queue are full the e-mail is logged and dropped rather than
   * blocking or failing the HTTP request that triggered it - the user can ask
   * for the OTP again. On shutdown, running and queued sends get up to
   * {@value #EMAIL_AWAIT_TERMINATION_SECONDS}s to finish.
   * </p>
   */
  @Bean(name = "emailExecutor")
  ThreadPoolTaskExecutor emailExecutor() {
    AsyncProperties.Pool pool = asyncProperties.getEmail();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.getCoreSize());
    executor.setMaxPoolSize(pool.getMaxSize());
    executor.setQueueCapacity(pool.getQueueCapacity());
    executor.setThreadNamePrefix(EMAIL_THREAD_NAME_PREFIX);
    executor.setRejectedExecutionHandler(logAndDrop());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(EMAIL_AWAIT_TERMINATION_SECONDS);
    executor.initialize();
    return executor;
  }

  private static RejectedExecutionHandler logAndDrop() {
    return (task, pool) -> log.warn(
        "E-mail executor saturated or shutting down (active={}, queued={}); e-mail dropped",
        pool.getActiveCount(), pool.getQueue().size());
  }
}
```

- [ ] **Step 6: Run the unit tests to verify they pass**

Run: `mvn -q test -Dtest='AsyncPropertiesTest,AsyncConfigTest'`
Expected: PASS (8 tests, 0 failures). One WARN line "E-mail executor saturated or shutting down ... e-mail dropped" is printed by `saturatedPoolDropsTheTaskInsteadOfThrowingAtTheCaller`.

- [ ] **Step 7: Fix the `spring.threads` comment in `application.yml`**

In `src/main/resources/application.yml`, replace lines 105-113:

```yaml
  threads:
    # Serve each request (and @Scheduled task) on a virtual thread. Almost every
    # request blocks on JDBC, Redis or SMTP, which is what virtual threads are
    # for. Pinning risk is low on Java 21: the app has no synchronized blocks,
    # and mysql-connector-j 9.x / HikariCP 6.x use java.util.concurrent locks.
    # Request concurrency is no longer capped by Tomcat's thread pool, so
    # hikari.maximum-pool-size is now the real limit on concurrent DB work.
    virtual:
      enabled: true
```

with:

```yaml
  threads:
    # Serve each HTTP request (and @Scheduled task) on a virtual thread; almost
    # every request blocks on JDBC or Redis. Request concurrency is no longer
    # capped by Tomcat's thread pool, so hikari.maximum-pool-size is now the
    # real limit on concurrent DB work.
    # E-mail deliberately does NOT run on virtual threads: jakarta.mail's
    # SMTPTransport does socket I/O inside synchronized methods, which pins the
    # carrier thread on Java 21, so slow SMTP servers would stall request
    # handling. @Async e-mail runs on the bounded platform-thread pool
    # application.async.email instead (see AsyncConfig).
    virtual:
      enabled: true
```

- [ ] **Step 8: Add the `email` pool to `application.async` in `application.yml`**

Replace lines 179-185:

```yaml
  async:
    # E-mail runs on virtual threads (AsyncConfig) and needs no pool settings;
    # see mail.max-concurrent-sends for its limit.
    task:
      core-size: ${ASYNC_TASK_CORE_SIZE:5}
      max-size: ${ASYNC_TASK_MAX_SIZE:10}
      queue-capacity: ${ASYNC_TASK_QUEUE_CAPACITY:25}
```

with:

```yaml
  async:
    task:
      core-size: ${ASYNC_TASK_CORE_SIZE:5}
      max-size: ${ASYNC_TASK_MAX_SIZE:10}
      queue-capacity: ${ASYNC_TASK_QUEUE_CAPACITY:25}
    # Platform threads for outgoing e-mail (emailExecutor). max-size is the cap
    # on concurrent SMTP sessions - keep it at or below your provider's limit.
    # A ThreadPoolExecutor only grows past core-size once the queue is full,
    # hence core-size = max-size by default. Sends beyond queue-capacity are
    # logged and dropped (the user can request a new OTP).
    email:
      core-size: ${ASYNC_EMAIL_CORE_SIZE:5}
      max-size: ${ASYNC_EMAIL_MAX_SIZE:5}
      queue-capacity: ${ASYNC_EMAIL_QUEUE_CAPACITY:100}
```

- [ ] **Step 9: Add literal overrides to `application-test.yml`**

In `src/test/resources/application-test.yml`, replace lines 67-71:

```yaml
  async:
    task:
      core-size: 1
      max-size: 1
      queue-capacity: 1
```

with:

```yaml
  async:
    task:
      core-size: 1
      max-size: 1
      queue-capacity: 1
    email:
      core-size: 1
      max-size: 1
      queue-capacity: 1
```

- [ ] **Step 10: Add `ASYNC_EMAIL_*` to `.env.example`**

Find the existing async task variables:

```bash
grep -n "ASYNC_" .env.example
```

Immediately after the line for `ASYNC_TASK_QUEUE_CAPACITY`, insert the three lines below, written in exactly the same style as the neighbouring `ASYNC_TASK_*` lines (if those are commented out as `# ASYNC_TASK_CORE_SIZE=5`, prefix these with `# ` too). If the file has no `ASYNC_TASK_*` lines, append them at the end under a `# --- Async e-mail pool (application.async.email) ---` header.

```properties
# Platform-thread pool for outgoing e-mail. max-size caps concurrent SMTP sessions.
ASYNC_EMAIL_CORE_SIZE=5
ASYNC_EMAIL_MAX_SIZE=5
ASYNC_EMAIL_QUEUE_CAPACITY=100
```

Verify:

```bash
grep -c "ASYNC_EMAIL_CORE_SIZE\|ASYNC_EMAIL_MAX_SIZE\|ASYNC_EMAIL_QUEUE_CAPACITY" .env.example
```
Expected: `3`.

- [ ] **Step 11: Run the context test**

Run: `mvn -q test -Dtest='AsyncPropertiesTest,AsyncConfigTest,BaseSpringApplicationTests'`
Expected: PASS. (`BaseSpringApplicationTests` proves `application.async.email.*` binds under strict binding with the test overrides.)

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/spring/app/configs/properties/AsyncProperties.java \
  src/main/java/com/spring/app/configs/AsyncConfig.java \
  src/main/resources/application.yml \
  src/test/resources/application-test.yml \
  .env.example \
  src/test/java/com/spring/app/configs/properties/AsyncPropertiesTest.java \
  src/test/java/com/spring/app/configs/AsyncConfigTest.java
git commit -m "fix(async): run e-mail on a bounded platform-thread pool

jakarta.mail SMTPTransport does socket I/O in synchronized methods, which
pins virtual-thread carriers on Java 21 and starves Tomcat's virtual request
threads. Restore ThreadPoolTaskExecutor for emailExecutor, sized from
application.async.email.* (ASYNC_EMAIL_*), validated max >= core, with a
log-and-drop rejection policy and a 30s wait for running sends on shutdown.
HTTP requests stay on virtual threads; fix the misleading yml comment.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: SMTP connect/read/write timeouts

`MailConfig` sets no jakarta.mail timeouts, so a stuck SMTP server blocks an e-mail thread forever; with a bounded pool that eventually blocks every send.

**Files:**
- Modify: `src/main/java/com/spring/app/configs/properties/MailProperties.java` (imports lines 1-10; insert fields after line 58)
- Modify: `src/main/java/com/spring/app/configs/MailConfig.java` (imports lines 3-13; body lines 38-43)
- Modify: `src/main/resources/application.yml` (`application.mail` block, lines 160-171 at HEAD)
- Modify: `src/test/resources/application-test.yml` (`application.mail` block, lines 51-59 at HEAD)
- Modify: `.env.example` (add `MAIL_*_TIMEOUT`)
- Create: `src/test/java/com/spring/app/configs/MailConfigTest.java`
- Create: `src/test/java/com/spring/app/configs/properties/MailPropertiesTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `MailProperties#getConnectionTimeout()`, `getReadTimeout()`, `getWriteTimeout()` (all `java.time.Duration`, default 10s, valid range 1ms..5m) and their setters.
  - Keys `application.mail.connection-timeout|read-timeout|write-timeout`; env vars `MAIL_CONNECTION_TIMEOUT|MAIL_READ_TIMEOUT|MAIL_WRITE_TIMEOUT`.
  - `JavaMailSenderImpl#getJavaMailProperties()` contains String millis under `mail.smtp.connectiontimeout`, `mail.smtp.timeout`, `mail.smtp.writetimeout`.

- [ ] **Step 1: Write the failing `MailConfig` test**

Create `src/test/java/com/spring/app/configs/MailConfigTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.spring.app.configs.properties.MailProperties;

class MailConfigTest {

  @Test
  void appliesSmtpTimeoutsInMilliseconds() {
    MailProperties props = new MailProperties();
    props.setConnectionTimeout(Duration.ofSeconds(3));
    props.setReadTimeout(Duration.ofSeconds(4));
    props.setWriteTimeout(Duration.ofMillis(5500));

    JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig(props).javaMailSender();
    Properties mail = sender.getJavaMailProperties();

    // getProperty only returns String values - jakarta.mail parses them as int millis
    assertThat(mail.getProperty("mail.smtp.connectiontimeout")).isEqualTo("3000");
    assertThat(mail.getProperty("mail.smtp.timeout")).isEqualTo("4000");
    assertThat(mail.getProperty("mail.smtp.writetimeout")).isEqualTo("5500");
  }

  @Test
  void defaultsAreTenSeconds() {
    JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig(new MailProperties()).javaMailSender();
    Properties mail = sender.getJavaMailProperties();

    assertThat(mail.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
    assertThat(mail.getProperty("mail.smtp.timeout")).isEqualTo("10000");
    assertThat(mail.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
  }
}
```

- [ ] **Step 2: Write the failing `MailProperties` test**

Create `src/test/java/com/spring/app/configs/properties/MailPropertiesTest.java`:

```java
package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Binds {@link MailProperties} with strict binding + {@code @Validated}. */
class MailPropertiesTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(MailProperties.class)
  static class Config {
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(Config.class);

  @Test
  void bindsSmtpTimeouts() {
    runner.withPropertyValues(
        "application.mail.connection-timeout=3s",
        "application.mail.read-timeout=4s",
        "application.mail.write-timeout=500ms")
        .run(context -> {
          assertThat(context).hasNotFailed();
          MailProperties props = context.getBean(MailProperties.class);
          assertThat(props.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(3));
          assertThat(props.getReadTimeout()).isEqualTo(Duration.ofSeconds(4));
          assertThat(props.getWriteTimeout()).isEqualTo(Duration.ofMillis(500));
        });
  }

  @Test
  void rejectsZeroTimeout() {
    runner.withPropertyValues("application.mail.connection-timeout=0s")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.mail.connection-timeout must be between 1ms and 5m");
        });
  }

  @Test
  void rejectsTimeoutAboveFiveMinutes() {
    runner.withPropertyValues("application.mail.read-timeout=10m")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.mail.read-timeout must be between 1ms and 5m");
        });
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q test -Dtest='MailConfigTest,MailPropertiesTest'`
Expected: FAIL — test compilation error `cannot find symbol: method setConnectionTimeout(java.time.Duration)` (and `getConnectionTimeout`, `setReadTimeout`, ...) in `MailProperties`.

- [ ] **Step 4: Add the timeout fields to `MailProperties`**

In `src/main/java/com/spring/app/configs/properties/MailProperties.java`, replace lines 1-10:

```java
package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
```

with:

```java
package com.spring.app.configs.properties;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
```

Then, directly after the line `  private String defaultEncoding = "UTF-8";` (line 58 at HEAD), insert:

```java

  /*
   * SMTP socket timeouts. Without them jakarta.mail waits forever on a stuck
   * server, and every thread of the bounded emailExecutor pool eventually hangs.
   * Capped at 5m because jakarta.mail parses them as int milliseconds.
   */

  /** TCP connect timeout ({@code mail.smtp.connectiontimeout}). */
  @NotNull(message = "application.mail.connection-timeout is required")
  @DurationMin(millis = 1, message = "application.mail.connection-timeout must be between 1ms and 5m")
  @DurationMax(minutes = 5, message = "application.mail.connection-timeout must be between 1ms and 5m")
  private Duration connectionTimeout = Duration.ofSeconds(10);

  /** Socket read timeout ({@code mail.smtp.timeout}). */
  @NotNull(message = "application.mail.read-timeout is required")
  @DurationMin(millis = 1, message = "application.mail.read-timeout must be between 1ms and 5m")
  @DurationMax(minutes = 5, message = "application.mail.read-timeout must be between 1ms and 5m")
  private Duration readTimeout = Duration.ofSeconds(10);

  /** Socket write timeout ({@code mail.smtp.writetimeout}). */
  @NotNull(message = "application.mail.write-timeout is required")
  @DurationMin(millis = 1, message = "application.mail.write-timeout must be between 1ms and 5m")
  @DurationMax(minutes = 5, message = "application.mail.write-timeout must be between 1ms and 5m")
  private Duration writeTimeout = Duration.ofSeconds(10);
```

(Leave the `maxConcurrentSends` field below it alone; Task 4 removes it.)

- [ ] **Step 5: Apply the timeouts in `MailConfig`**

In `src/main/java/com/spring/app/configs/MailConfig.java`, replace lines 3-13:

```java
import java.util.Properties;

import lombok.NoArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.spring.app.configs.properties.MailProperties;

import lombok.RequiredArgsConstructor;
```

with (also drops the unused `NoArgsConstructor` import):

```java
import java.time.Duration;
import java.util.Properties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.spring.app.configs.properties.MailProperties;

import lombok.RequiredArgsConstructor;
```

Then replace the end of `javaMailSender()` (lines 38-44 at HEAD):

```java
    Properties props = sender.getJavaMailProperties();
    props.put("mail.smtp.auth", mailProperties.isSmtpAuth());
    props.put("mail.smtp.starttls.enable", mailProperties.isStartTls());
    props.put("mail.smtp.ssl.enable", mailProperties.isSslEnable());

    return sender;
  }
```

with:

```java
    Properties props = sender.getJavaMailProperties();
    props.put("mail.smtp.auth", mailProperties.isSmtpAuth());
    props.put("mail.smtp.starttls.enable", mailProperties.isStartTls());
    props.put("mail.smtp.ssl.enable", mailProperties.isSslEnable());
    // Without these, jakarta.mail blocks forever on an unresponsive server.
    props.put("mail.smtp.connectiontimeout", millis(mailProperties.getConnectionTimeout()));
    props.put("mail.smtp.timeout", millis(mailProperties.getReadTimeout()));
    props.put("mail.smtp.writetimeout", millis(mailProperties.getWriteTimeout()));

    return sender;
  }

  private static String millis(Duration duration) {
    return String.valueOf(duration.toMillis());
  }
```

- [ ] **Step 6: Run the unit tests to verify they pass**

Run: `mvn -q test -Dtest='MailConfigTest,MailPropertiesTest'`
Expected: PASS (5 tests, 0 failures).

- [ ] **Step 7: Add the keys to `application.yml`**

In `src/main/resources/application.yml`, in the `application.mail` block, replace:

```yaml
    default-encoding: UTF-8
    max-concurrent-sends: ${MAIL_MAX_CONCURRENT_SENDS:5}
```

with:

```yaml
    default-encoding: UTF-8
    # SMTP socket timeouts (1ms..5m). Without them a stuck server blocks an
    # e-mail thread forever.
    connection-timeout: ${MAIL_CONNECTION_TIMEOUT:10s}
    read-timeout: ${MAIL_READ_TIMEOUT:10s}
    write-timeout: ${MAIL_WRITE_TIMEOUT:10s}
    max-concurrent-sends: ${MAIL_MAX_CONCURRENT_SENDS:5}
```

(`max-concurrent-sends` is removed in Task 4.)

- [ ] **Step 8: Add literal overrides to `application-test.yml`**

In `src/test/resources/application-test.yml`, in the `application.mail` block, replace:

```yaml
    ssl-enable: false
    max-concurrent-sends: 1
```

with:

```yaml
    ssl-enable: false
    connection-timeout: 2s
    read-timeout: 2s
    write-timeout: 2s
    max-concurrent-sends: 1
```

- [ ] **Step 9: Add `MAIL_*_TIMEOUT` to `.env.example`**

```bash
grep -n "^#\? \?MAIL_" .env.example
```

Immediately after the last `MAIL_*` line shown, insert the following, in the same style (commented or not) as the neighbouring `MAIL_*` lines that have a safe default (e.g. `MAIL_SSL_ENABLED` / `MAIL_PORT`). If there are no `MAIL_*` lines, append them at the end under a `# --- Mail (application.mail) ---` header.

```properties
# SMTP socket timeouts (Spring duration syntax, 1ms..5m).
MAIL_CONNECTION_TIMEOUT=10s
MAIL_READ_TIMEOUT=10s
MAIL_WRITE_TIMEOUT=10s
```

Verify:

```bash
grep -c "MAIL_CONNECTION_TIMEOUT\|MAIL_READ_TIMEOUT\|MAIL_WRITE_TIMEOUT" .env.example
```
Expected: `3`.

- [ ] **Step 10: Run the context test**

Run: `mvn -q test -Dtest='MailConfigTest,MailPropertiesTest,BaseSpringApplicationTests'`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/spring/app/configs/properties/MailProperties.java \
  src/main/java/com/spring/app/configs/MailConfig.java \
  src/main/resources/application.yml \
  src/test/resources/application-test.yml \
  .env.example \
  src/test/java/com/spring/app/configs/MailConfigTest.java \
  src/test/java/com/spring/app/configs/properties/MailPropertiesTest.java
git commit -m "fix(mail): add SMTP connect/read/write timeouts

MailConfig set no jakarta.mail timeouts, so an unresponsive SMTP server
blocked an e-mail thread forever. Bind application.mail.connection-timeout,
read-timeout and write-timeout (MAIL_*_TIMEOUT, default 10s, validated
1ms..5m) and apply them as mail.smtp.connectiontimeout/timeout/writetimeout.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Remove the send semaphore, `max-concurrent-sends` and the dead `sendContentEmail`

With the bounded `emailExecutor` (Task 2) the `Semaphore` in `MailService` is redundant: every send already runs on one of at most `application.async.email.max-size` threads, and its untimed `acquire()` gave no backpressure anyway. Removing the semaphore removes `application.mail.max-concurrent-sends` (`MAIL_MAX_CONCURRENT_SENDS` was never in `.env.example`). Since `MailService` is rewritten, also drop the unused `sendContentEmail` / `isHtmlContent` (the only jsoup user), as the spec allows.

**Files:**
- Rewrite: `src/main/java/com/spring/app/shared/services/MailService.java`
- Modify: `src/main/java/com/spring/app/configs/properties/MailProperties.java` (delete the `maxConcurrentSends` field + javadoc, lines 60-65 at HEAD)
- Modify: `src/main/resources/application.yml` (delete `max-concurrent-sends` line)
- Modify: `src/test/resources/application-test.yml` (delete `max-concurrent-sends: 1`)
- Modify: `src/test/java/com/spring/app/configs/properties/MailPropertiesTest.java` (add one test)

**Interfaces:**
- Consumes: `HtmlTemplateOTPBuilder#buildOtpHtml(String)` (Task 1); bounded `emailExecutor` (Task 2).
- Produces: `MailService` with constructor `(MailProperties, JavaMailSender, HtmlTemplateOTPBuilder)` (via `@RequiredArgsConstructor`, same order as Task 1) and the single method `sendOtpEmail(String to, String otp) throws MessagingException`. `MailProperties#getMaxConcurrentSends()` no longer exists; `application.mail.max-concurrent-sends` is now an unknown key (startup fails if set).

- [ ] **Step 1: Write the failing test**

Append this test method to `src/test/java/com/spring/app/configs/properties/MailPropertiesTest.java` (inside the class, after `rejectsTimeoutAboveFiveMinutes`):

```java
  @Test
  void maxConcurrentSendsIsNoLongerAKey() {
    // Concurrency is bounded by application.async.email.max-size now; strict
    // binding must reject the old key instead of silently ignoring it.
    runner.withPropertyValues("application.mail.max-concurrent-sends=5")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).rootCause()
              .hasMessageContaining("application.mail.max-concurrent-sends");
        });
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=MailPropertiesTest`
Expected: FAIL — `maxConcurrentSendsIsNoLongerAKey`: `Expecting ApplicationContext to have failed but it did not` (the key still binds).

- [ ] **Step 3: Remove `maxConcurrentSends` from `MailProperties`**

In `src/main/java/com/spring/app/configs/properties/MailProperties.java`, delete this block (with the blank line before it):

```java

  /**
   * Upper bound on simultaneous SMTP sends. Providers cap concurrent sessions
   * per account, so extra sends wait for a free slot instead of failing.
   */
  @Min(value = 1, message = "application.mail.max-concurrent-sends must be >= 1")
  private int maxConcurrentSends = 5;
```

(`@Min` stays imported — `port` still uses it.)

- [ ] **Step 4: Rewrite `MailService`**

Replace the whole content of `src/main/java/com/spring/app/shared/services/MailService.java` with:

```java
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
```

- [ ] **Step 5: Remove the key from `application.yml`**

In `src/main/resources/application.yml`, delete the line:

```yaml
    max-concurrent-sends: ${MAIL_MAX_CONCURRENT_SENDS:5}
```

- [ ] **Step 6: Remove the key from `application-test.yml`**

In `src/test/resources/application-test.yml`, delete the line:

```yaml
    max-concurrent-sends: 1
```

(Required: `MailProperties` uses `ignoreUnknownFields = false`, so a leftover key fails every `@SpringBootTest`.)

- [ ] **Step 7: Confirm nothing else references the removed pieces**

```bash
grep -rn "max-concurrent-sends\|MAX_CONCURRENT_SENDS\|getMaxConcurrentSends\|sendContentEmail\|Semaphore\|org.jsoup" src/main src/test/resources docker-compose*.yml .env.example || echo "clean"
```
Expected: `clean`. (If `.env.example` contains `MAIL_MAX_CONCURRENT_SENDS`, delete that line and re-run.)

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn -q test -Dtest='MailPropertiesTest,MailServiceTest,MailConfigTest,BaseSpringApplicationTests'`
Expected: PASS (`MailPropertiesTest` 4 tests, `MailServiceTest` 1, `MailConfigTest` 2, context loads).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/MailService.java \
  src/main/java/com/spring/app/configs/properties/MailProperties.java \
  src/main/resources/application.yml \
  src/test/resources/application-test.yml \
  src/test/java/com/spring/app/configs/properties/MailPropertiesTest.java
git add .env.example 2>/dev/null || true
git commit -m "refactor(mail): drop send semaphore and unused sendContentEmail

The bounded emailExecutor already caps concurrent SMTP sessions, and the
semaphore's untimed acquire() gave no backpressure. Remove it together with
application.mail.max-concurrent-sends, and remove the unused
sendContentEmail/isHtmlContent (last jsoup user).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `OtpFunctionUtil` — `SecureRandom`, namespaced keys, constant-time compare

`generateOtp` uses `Math.random` (predictable PRNG), and OTPs are stored under the raw e-mail as the Redis key (collides with any other key that is an e-mail, and is case-sensitive while the MySQL lookup is not). Keep every public signature so `AuthServiceImpl` (P3) and `OtpEmailService` compile unchanged; the parameter that used to be a "key" is now explicitly the e-mail and the util derives `otp:<lower(trim(email))>` itself.

Deployment note: OTPs issued by the old build (stored under the raw e-mail, TTL 3 minutes) are not found after deploy; affected users press "resend OTP".

**Files:**
- Rewrite: `src/main/java/com/spring/app/utils/OtpFunctionUtil.java` (all 109 lines)
- Create: `src/test/java/com/spring/app/utils/OtpFunctionUtilTest.java`

**Interfaces:**
- Consumes: `RedisServiceInterface#setValue(String, Object, long, TimeUnit)`, `#getValue(String)`, `#delete(String)` (P1-owned; see "Context for the executor").
- Produces (all on `@Component OtpFunctionUtil`):
  - `public static final int OTP_LENGTH = 6`
  - `static final String KEY_PREFIX = "otp:"`, `static String otpKey(String email)`
  - `public String generateOtp(int length)` (throws `IllegalArgumentException` if `length < 1`), `public String generateOtp()`
  - `public void storeOtp(String email, String otp, long expirationTimeInSeconds)`
  - `public String retrieveOtp(String email)`, `public void deleteOtp(String email)`, `public void removeOtp(String email)`
  - `public boolean validateOtp(String email, String otp)`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/utils/OtpFunctionUtilTest.java`:

```java
package com.spring.app.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.spring.app.shared.interfaces.RedisServiceInterface;

@ExtendWith(MockitoExtension.class)
class OtpFunctionUtilTest {

  @Mock
  private RedisServiceInterface redis;

  private OtpFunctionUtil util;

  @BeforeEach
  void setUp() {
    util = new OtpFunctionUtil(redis);
  }

  @Test
  void generatesSixDigitOtps() {
    for (int i = 0; i < 200; i++) {
      assertThat(util.generateOtp()).matches("\\d{6}");
    }
  }

  @Test
  void generatedOtpsVary() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      seen.add(util.generateOtp());
    }
    assertThat(seen).hasSizeGreaterThan(150);
  }

  @Test
  void rejectsNonPositiveLength() {
    assertThatThrownBy(() -> util.generateOtp(0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void storesUnderNamespacedLowerCaseKey() {
    util.storeOtp(" Alice@Example.COM ", "123456", 180);

    verify(redis).setValue("otp:alice@example.com", "123456", 180, TimeUnit.SECONDS);
  }

  @Test
  void rejectsNonPositiveTtl() {
    assertThatThrownBy(() -> util.storeOtp("alice@example.com", "123456", 0))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(redis);
  }

  @Test
  void validatesAgainstNamespacedKeyCaseInsensitively() {
    when(redis.getValue("otp:alice@example.com")).thenReturn("123456");

    assertThat(util.validateOtp("ALICE@example.com", "123456")).isTrue();
    assertThat(util.validateOtp("alice@example.com", "654321")).isFalse();
  }

  @Test
  void missingOtpIsInvalid() {
    when(redis.getValue("otp:alice@example.com")).thenReturn(null);

    assertThat(util.validateOtp("alice@example.com", "123456")).isFalse();
  }

  @Test
  void malformedOtpIsRejectedWithoutTouchingRedis() {
    assertThat(util.validateOtp("alice@example.com", "12ab56")).isFalse();
    assertThat(util.validateOtp("alice@example.com", null)).isFalse();
    verifyNoInteractions(redis);
  }

  @Test
  void removeDeletesNamespacedKey() {
    util.removeOtp("Alice@Example.com");

    verify(redis).delete("otp:alice@example.com");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=OtpFunctionUtilTest`
Expected: FAIL — `rejectsNonPositiveLength` (no exception, returns `""`), `storesUnderNamespacedLowerCaseKey` (`setValue(" Alice@Example.COM ", ...)` was invoked instead), `validatesAgainstNamespacedKeyCaseInsensitively` / `missingOtpIsInvalid` (strict-stubs `PotentialStubbingProblem`: `getValue` called with the raw e-mail), `malformedOtpIsRejectedWithoutTouchingRedis` (Redis was read), `removeDeletesNamespacedKey` (`delete("Alice@Example.com")`).

- [ ] **Step 3: Rewrite `OtpFunctionUtil`**

Replace the whole content of `src/main/java/com/spring/app/utils/OtpFunctionUtil.java` with:

```java
package com.spring.app.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;

/**
 * Generates, stores and checks one-time passwords.
 *
 * <p>
 * OTPs are stored in Redis under {@code otp:<lower-cased, trimmed e-mail>}:
 * namespaced so they cannot collide with other keys, and case-insensitive like
 * the MySQL e-mail lookup. Every public method takes the e-mail address, never
 * a raw Redis key.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OtpFunctionUtil {

  public static final int OTP_LENGTH = 6;
  static final String KEY_PREFIX = "otp:";

  private static final Pattern OTP_PATTERN = Pattern.compile("\\d{" + OTP_LENGTH + "}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final RedisServiceInterface redisService;

  /**
   * Generates a random numeric OTP of the given length using a CSPRNG.
   *
   * @param length number of digits, at least 1
   * @return the OTP
   */
  public String generateOtp(int length) {
    if (length < 1) {
      throw new IllegalArgumentException("OTP length must be at least 1");
    }
    StringBuilder otp = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      otp.append(RANDOM.nextInt(10));
    }
    return otp.toString();
  }

  /** Generates a {@value #OTP_LENGTH}-digit OTP. */
  public String generateOtp() {
    return generateOtp(OTP_LENGTH);
  }

  /** Redis key holding the OTP for {@code email}. */
  static String otpKey(String email) {
    Objects.requireNonNull(email, "Email must not be null");
    return KEY_PREFIX + email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Stores the OTP for {@code email}, replacing any previous one.
   *
   * @param email                   the address the OTP was sent to
   * @param otp                     the OTP
   * @param expirationTimeInSeconds TTL, must be positive
   */
  public void storeOtp(String email, String otp, long expirationTimeInSeconds) {
    Objects.requireNonNull(otp, "OTP must not be null");
    if (expirationTimeInSeconds <= 0) {
      throw new IllegalArgumentException("Expiration time must be positive");
    }
    redisService.setValue(otpKey(email), otp, expirationTimeInSeconds, TimeUnit.SECONDS);
  }

  /** @return the stored OTP for {@code email}, or {@code null} if none / expired */
  public String retrieveOtp(String email) {
    Object stored = redisService.getValue(otpKey(email));
    return stored instanceof String otp ? otp : null;
  }

  /** Deletes the OTP for {@code email}. */
  public void deleteOtp(String email) {
    redisService.delete(otpKey(email));
  }

  /**
   * Checks {@code otp} against the stored OTP for {@code email}. Malformed input
   * is rejected before Redis is read; the comparison is constant-time.
   */
  public boolean validateOtp(String email, String otp) {
    if (otp == null || !OTP_PATTERN.matcher(otp).matches()) {
      return false;
    }
    String stored = retrieveOtp(email);
    return stored != null
        && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), otp.getBytes(StandardCharsets.UTF_8));
  }

  /** Removes the OTP after successful activation / password reset. */
  public void removeOtp(String email) {
    deleteOtp(email);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=OtpFunctionUtilTest`
Expected: PASS (9 tests, 0 failures).

- [ ] **Step 5: Verify the PRNG change and callers**

`SecureRandom` vs `Math.random` is not observable in a unit test; verify by grep:

```bash
grep -rn "Math.random" src/main/java || echo "no Math.random"
grep -n "otpFunction\." src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java src/main/java/com/spring/app/shared/services/OtpEmailService.java
```
Expected: `no Math.random`; the second command lists only `validateOtp(…, …)`, `removeOtp(…)`, `generateOtp()` and `storeOtp(…, …, …)` calls — all signatures unchanged.

Run: `mvn -q test -Dtest=BaseSpringApplicationTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/utils/OtpFunctionUtil.java \
  src/test/java/com/spring/app/utils/OtpFunctionUtilTest.java
git commit -m "fix(otp): SecureRandom OTPs under namespaced Redis keys

Generate OTPs with SecureRandom instead of Math.random, store them under
otp:<lower(email)> instead of the raw e-mail, reject malformed input before
reading Redis, and compare in constant time. Public signatures unchanged.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `OtpEmailService` — keep the orchestration, stop logging the full e-mail

`OtpEmailService` logs the full recipient address at ERROR on every failed send (PII in log files). Mask it. Lock the existing orchestration (store before send, failures never propagate out of the `@Async` method) with tests.

**Files:**
- Modify: `src/main/java/com/spring/app/shared/services/OtpEmailService.java:20-29`
- Create: `src/test/java/com/spring/app/shared/services/OtpEmailServiceTest.java`

**Interfaces:**
- Consumes: `OtpFunctionUtil#generateOtp()`, `#storeOtp(String, String, long)` (Task 5); `MailServiceInterface#sendOtpEmail(String, String)`.
- Produces: `OtpEmailService#sendOtpEmailAsync(String email)` (unchanged, `@Async("emailExecutor")`), `public static final int OTP_EXPIRATION_SECONDS = 180` (unchanged), new `static String maskEmail(String email)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/shared/services/OtpEmailServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=OtpEmailServiceTest`
Expected: FAIL — test compilation error `cannot find symbol: method maskEmail(java.lang.String)` in `OtpEmailService`.

- [ ] **Step 3: Mask the e-mail in `OtpEmailService`**

In `src/main/java/com/spring/app/shared/services/OtpEmailService.java`, replace lines 20-29:

```java
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
```

with:

```java
  /**
   * Generates and stores a fresh OTP, then mails it. Runs on the bounded
   * {@code emailExecutor}; never throws - a failure is logged (with the address
   * masked) and the user can request a new OTP.
   */
  @Async("emailExecutor")
  public void sendOtpEmailAsync(String email) {
    try {
      String otp = otpFunction.generateOtp();
      otpFunction.storeOtp(email, otp, OTP_EXPIRATION_SECONDS);
      mailService.sendOtpEmail(email, otp);
    } catch (Exception e) {
      log.error("Failed to send OTP e-mail to {}", maskEmail(email), e);
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=OtpEmailServiceTest`
Expected: PASS (8 tests: 3 plain + 4 parameterized + 1 null, 0 failures).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/OtpEmailService.java \
  src/test/java/com/spring/app/shared/services/OtpEmailServiceTest.java
git commit -m "fix(otp): mask recipient address in OTP send failure log

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Full verification (no code changes)

**Files:** none modified.

- [ ] **Step 1: Run the whole suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, 0 failures, 0 errors (nothing running: no MySQL/Redis/SMTP).

- [ ] **Step 2: Prove the template is in the jar (the original production failure)**

```bash
mvn -q -DskipTests package
unzip -l target/springapp-0.0.1-SNAPSHOT.jar | grep "templates/"
```
Expected: exactly one line ending in `BOOT-INF/classes/templates/otp-email.html` (no `OTP.html`).

- [ ] **Step 3: Static checks for the spec items**

```bash
grep -rn "src/main/resources" src/main/java || echo "OK: no source-tree paths"
grep -rn "SimpleAsyncTaskExecutor\|setVirtualThreads" src/main/java || echo "OK: no virtual e-mail executor"
grep -rn "Math.random\|Semaphore\|org.jsoup\|sendContentEmail" src/main/java || echo "OK: removed"
grep -rn "max-concurrent-sends\|MAX_CONCURRENT_SENDS" src/main src/test/resources .env.example || echo "OK: key gone"
grep -n "virtual:" -A1 src/main/resources/application.yml
grep -c "ASYNC_EMAIL_\|MAIL_CONNECTION_TIMEOUT\|MAIL_READ_TIMEOUT\|MAIL_WRITE_TIMEOUT" .env.example
```
Expected: the four `OK:` lines; `virtual:` followed by `enabled: true`; the count is `6`.

- [ ] **Step 4 (optional, manual): end-to-end send against a local SMTP sink**

Only if Docker, MySQL and Redis are available locally:
`docker run --rm -p 1025:1025 -p 8025:8025 axllent/mailpit`, then start the app with `MAIL_HOST=localhost MAIL_PORT=1025 MAIL_FROM=noreply@example.com SPRING_PROFILES_ACTIVE=prod`-like settings (non-dev, so registration sends the OTP), call `POST /api/v1/auth/register`, and check http://localhost:8025 shows the OTP mail with the digits rendered and no "Verify Now" button. Thread name in the app log for the send is `EMAIL-TASK-1`. No commit.

---

## Hand-offs

- **P1 persistence-cache-redis:** `OtpFunctionUtil` (P2) relies on `RedisServiceInterface.setValue(String, Object, long, TimeUnit)`, `getValue(String)` (a `String` value set via `setValue` must come back as a `String`), and `delete(String)`. Keep those three methods and that round-trip when reworking `RedisService`/serializers. OTP keys are now `otp:<lower(email)>`; nothing in P1 should read or evict them.
- **P3 auth-security:**
  - No call-site change is needed in `AuthServiceImpl`: `validateOtp(email, otp)`, `removeOtp(email)` and `sendOtpEmailAsync(email)` keep their signatures, and the OTP key is now normalized (trim + lower-case) inside `OtpFunctionUtil`.
  - `AuthServiceImpl.register` calls `otpEmailService.sendOtpEmailAsync` inside `@Transactional`, *before* `userRepository.save` — if the insert fails (e.g. the `uk_users_email` case in the spec), an OTP mail has already gone out. Send the OTP after commit (e.g. `TransactionSynchronizationManager.registerSynchronization(... afterCommit ...)` or a `@TransactionalEventListener(phase = AFTER_COMMIT)`), which also fits P3's "move work out of the transaction" item.
  - Saturation behaviour changed: when `emailExecutor` is full, `sendOtpEmailAsync` now logs a WARN and drops the send instead of throwing `TaskRejectedException` into the request. The API still answers "OTP sent"; users recover via resend-otp. If P3 adds an OTP-related metric in `MonitoringService`, the drop is visible only as the AsyncConfig WARN log (P2 adds no counter).
- **P6 build-ops-docs-cleanup:**
  - `jsoup` is no longer referenced anywhere after P2 Task 4 → remove the `org.jsoup:jsoup` dependency from `pom.xml`. `MailService.sendContentEmail` is already gone (drop it from the dead-code list).
  - `ApplicationConstants.OTP_EXPIRATION_MINUTES` duplicates `OtpEmailService.OTP_EXPIRATION_SECONDS` and is unused → dead code.
  - Docs (CLAUDE.md/README): e-mail runs on the bounded platform pool `application.async.email.*` (`ASYNC_EMAIL_*`), SMTP timeouts `application.mail.{connection,read,write}-timeout` (`MAIL_*_TIMEOUT`), OTP template is `classpath:templates/otp-email.html` with `{{OTP}}` placeholder replacement (no JSoup / Template Method), OTP Redis keys are `otp:<lower(email)>`; `MAIL_MAX_CONCURRENT_SENDS` no longer exists.
  - The `.env.example` completeness script should expect `ASYNC_EMAIL_CORE_SIZE`, `ASYNC_EMAIL_MAX_SIZE`, `ASYNC_EMAIL_QUEUE_CAPACITY`, `MAIL_CONNECTION_TIMEOUT`, `MAIL_READ_TIMEOUT`, `MAIL_WRITE_TIMEOUT`.

## Spec coverage (P2 bullets → tasks)

| Spec item | Task |
|---|---|
| Template via `ClassPathResource`, cached, HTML-escaped; delete `OTP.html`; test renders from classpath | 1 (plus dead `{{VERIFICATION_URL}}` button removed) |
| Bounded `ThreadPoolTaskExecutor`, `application.async.email.{core-size,max-size,queue-capacity}`, validated, literal test overrides, `ASYNC_EMAIL_*` in `.env.example` | 2 |
| Rejection policy logs + drops, does not block the caller | 2 (`logAndDrop`, `saturatedPoolDropsTheTaskInsteadOfThrowingAtTheCaller`) |
| Task-termination wait on shutdown | 2 (`setWaitForTasksToCompleteOnShutdown(true)` + 30s, `shutdownWaitsForRunningSends`) |
| Fix misleading yml comment ("no synchronized blocks") | 2 Step 7 |
| HTTP stays on virtual threads | 2 (`spring.threads.virtual.enabled: true` kept; Task 7 Step 3) |
| SMTP `connectiontimeout`/`timeout`/`writetimeout` from `MailProperties`, validated, test overrides, `.env.example` | 3 |
| Semaphore without timeout / `MAIL_MAX_CONCURRENT_SENDS` missing | 4 (semaphore + setting removed; pool max-size is the cap) |
| `OtpFunctionUtil` `SecureRandom`; key `otp:<lower(email)>` | 5 |
| `MailService.sendContentEmail` unused — remove if P2 rewrites `MailService` | 4 (rewritten, removed; pom jsoup → P6) |
