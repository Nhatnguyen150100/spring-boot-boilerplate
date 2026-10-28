# P6 build-ops-docs-cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the container/compose/logging setup, make the nightly token purge cluster-safe, clean the build and dead code, add opt-in Testcontainers integration tests, and rewrite CLAUDE.md/README so they describe the code as it is after P1–P5.

**Architecture:** P6 runs LAST, after P1–P5 have landed. It changes code it owns outright (Dockerfile, compose files, `logback-spring.xml`, `TokenCleanupService`, `pom.xml`, `BaseSpringApplication`, `ApplicationConstants`, `enums/*`, docs, ignore files). It may delete dead code anywhere, but because P1–P5 rewrite many of those files first, every deletion is gated by a grep you run at execution time: delete only when the grep shows no remaining use. Integration tests live behind a Maven profile (`-Pintegration`, failsafe, `*IT.java`), so plain `mvn test` still needs nothing running.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Maven, Logback, Spring Data Redis (Lettuce), Flyway + MySQL 8, Testcontainers (Boot-managed 1.x), JUnit 5, Mockito, Docker / Docker Compose v2, Alpine `eclipse-temurin:21-jre-alpine`.

**Spec:** `docs/superpowers/specs/2026-10-28-review-remediation-design.md` (section "P6 build-ops-docs-cleanup"; Decisions, Global constraints, file ownership and allowed cross-edits are binding).

## Global Constraints

- Java 21, Spring Boot 3.5.x, Maven. `mvn test` must pass with nothing running (no MySQL, no Redis, no SMTP, no Docker).
- Framework keys under `spring.*`, app keys under `application.*` bound by `configs/properties/*` with `@Validated`; the six leaf properties classes keep `ignoreUnknownFields = false`; every validation-constrained property gets a **literal** override in `src/test/resources/application-test.yml`; every new env var is added to `.env.example`; profile files hold deltas only and never set `spring.profiles.active`.
- Flyway owns the schema (`src/main/resources/db/...`); any schema change is a new versioned migration, never an edit of an applied one.
- TDD: each fix lands with a test that fails before and passes after, unless the change is pure config/docs (then state how it was verified).
- Match surrounding code style: 2-space indent, Lombok, constructor injection, `ResponseBuilder` for HTTP responses.
- Commit per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Decision 3: do NOT add Bucket4j, spring-session, or structured JSON logging.
- P6 owns: `Dockerfile`, `docker-compose*.yml`, `logback-spring.xml`, `TokenCleanupService`, `pom.xml`, `BaseSpringApplication`, `ApplicationConstants`, `enums/*`, `CLAUDE.md`, `README.md`, `.gitignore`, `.gitattributes`, `BaseSpringApplicationTests`. Allowed beyond that: remove dead code anywhere; update `application*.yml` / `.env.example` to delete obsolete keys; append to `application-test.yml`; add test classes.
- P1 may already have set `REDIS_SSL_ENABLED` for the app in `docker-compose.prod.yml`: preserve it.

## Execution notes (read before Task 1)

- **Do not run two Maven builds at once** in the same checkout (they share `target/`).
- **Pre-flight:** confirm P1–P5 are merged before starting:
  ```bash
  git log --oneline | head -60
  ls docs/superpowers/plans/
  ```
  Expected: commits from P1…P5 are present (their messages reference their plans), and all five `2026-10-28-remediation-P[1-5]-*.md` plans exist. If any are missing, stop: this plan assumes their changes.
- `.env.example` could not be read while this plan was written (the authoring sandbox denies `.env*`). Task 7's script is the source of truth for its contents; do not assume anything about its current lines.
- Line numbers quoted from HEAD `92eac07` are for orientation only. P1–P5 have edited many of these files. Always locate code by the grep given in the step, not by line number.

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/logback-spring.xml` | Rewrite | Console-only under `test`; plain console in `prod`; size+time rolling with `totalSizeCap`; no `scan` |
| `src/test/java/com/spring/app/BaseSpringApplicationTests.java` | Modify | Context smoke test + "test profile logs to console only" |
| `.gitignore`, `.gitattributes` | Modify | Ignore `uploads-test/`; LF for `*.sh` |
| `src/main/java/com/spring/app/shared/services/TokenCleanupService.java` | Modify | Redis `SET NX EX` lock around the nightly purge |
| `src/test/java/com/spring/app/shared/services/TokenCleanupServiceTest.java` | Create | Unit tests for the lock |
| Dead-code files (see Task 3) | Modify/Delete | Grep-gated deletions |
| `src/main/resources/db/migrations/V<next>__demote_manager_role.sql` | Create (conditional) | Map any `MANAGER` rows to `USER` once `ERole.MANAGER` is gone |
| `pom.xml` | Modify | Boot patch upgrade, dependency cleanup, Mockito agent, `integration` profile, Testcontainers |
| `src/main/resources/application.yml`, `src/test/resources/application-test.yml` | Modify | Drop `spring.cloud.compatibility-verifier` and the explicit `H2Dialect` |
| `src/test/java/com/spring/app/integration/*.java` | Create | `IntegrationTestBase`, `FlywayMigrationIT`, `AuthRoundTripIT`, `JpaAuditingIT` |
| `Dockerfile` | Rewrite | JRE base, non-root user, container-aware JVM flags |
| `docker-compose.yml` | Rewrite | Base stack, `prod` profile by default, wget healthcheck, named volumes |
| `docker-compose.dev.yml` | Create | Dev overlay (`SPRING_PROFILES_ACTIVE=dev`) |
| `docker-compose.debug.yml` | Rewrite | JDWP overlay on top of base (+dev) |
| `docker-compose.prod.yml` | Modify | wget healthcheck, named volumes |
| `scripts/check-env-example.sh` | Create | Diff `${VAR}` names in config vs `.env.example` |
| `.env.example` | Modify | Add missing / delete stale variables |
| `CLAUDE.md`, `README.md` | Rewrite | Describe the real implementation after P1–P6 |

Task order matters: 3 (dead code, removes the last jsoup use) → 4 (pom drops jsoup; removes H2Dialect) → 5 (ITs need MySQL dialect auto-detection) → 6 (compose) → 7 (env check sees final compose/yml) → 8 (docs describe the final state).

---

### Task 1: Logging: console-only tests, prod-safe output, bounded files

**Files:**
- Modify: `src/test/java/com/spring/app/BaseSpringApplicationTests.java`
- Rewrite: `src/main/resources/logback-spring.xml`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing.
- Produces: appender names `CONSOLE` (always), `FILE_ALL`, `FILE_ERROR` (every profile except `test`). Task 8 docs refer to them.

- [ ] **Step 1: Write the failing test**

Replace the whole of `src/test/java/com/spring/app/BaseSpringApplicationTests.java` with:

```java
package com.spring.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/**
 * Smoke test: every bean wires up and every {@code @ConfigurationProperties}
 * class passes its {@code @Validated} constraints.
 *
 * <p>
 * Runs on the {@code test} profile (H2, Flyway off), so it needs nothing
 * running on the machine.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class BaseSpringApplicationTests {

  @Test
  void contextLoads() {
  }

  /**
   * logback-spring.xml must not attach file appenders under the test profile:
   * they would create ./logs in the working tree on every {@code mvn test}.
   */
  @Test
  void testProfileLogsToConsoleOnly() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    List<String> appenders = new ArrayList<>();
    root.iteratorForAppenders().forEachRemaining(appender -> appenders.add(appender.getName()));

    assertThat(appenders).containsExactly("CONSOLE");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=BaseSpringApplicationTests`
Expected: FAIL in `testProfileLogsToConsoleOnly` with an AssertJ message like `Expecting actual: ["CONSOLE", "FILE_ALL", "FILE_ERROR"] to contain exactly ... ["CONSOLE"]`. `contextLoads` passes.

- [ ] **Step 3: Rewrite `logback-spring.xml`**

Replace the whole of `src/main/resources/logback-spring.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  No scan="true": this file ships inside the jar and never changes at runtime,
  so a rescan thread only costs CPU in production.
-->
<configuration>

  <!-- Boot's defaults: registers the %clr converter used below. -->
  <include resource="org/springframework/boot/logging/logback/defaults.xml" />

  <!--
    Read the log directory from Spring config (logging.file.path, itself backed
    by the LOG_PATH env var) instead of hard-coding it. Without this the
    logging.file.* properties are silently ignored, because this file replaces
    Boot's default file appender.
  -->
  <springProperty scope="context" name="LOG_PATH" source="logging.file.path" defaultValue="logs" />

  <property name="PLAIN_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n" />

  <!-- prod: never emit ANSI escapes; Docker's json-file driver and log shippers store them verbatim. -->
  <springProfile name="prod">
    <property name="CONSOLE_PATTERN" value="${PLAIN_PATTERN}" />
  </springProfile>
  <!-- elsewhere: %clr colours, which Boot switches off by itself when stdout is not a terminal. -->
  <springProfile name="!prod">
    <property name="CONSOLE_PATTERN" value="%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(%-5level) [%clr(%thread){cyan}] %clr(%logger{36}){green} - %msg%n" />
  </springProfile>

  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>${CONSOLE_PATTERN}</pattern>
      <charset>UTF-8</charset>
    </encoder>
  </appender>

  <!-- Tests log to the console only, so `mvn test` never writes ./logs into the working tree. -->
  <springProfile name="test">
    <root level="INFO">
      <appender-ref ref="CONSOLE" />
    </root>
  </springProfile>

  <springProfile name="!test">
    <!-- All levels. Rolls daily and at 100MB; at most 30 days and 3GB on disk. -->
    <appender name="FILE_ALL" class="ch.qos.logback.core.rolling.RollingFileAppender">
      <file>${LOG_PATH}/application.log</file>
      <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>${LOG_PATH}/application.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
        <maxFileSize>100MB</maxFileSize>
        <maxHistory>30</maxHistory>
        <totalSizeCap>3GB</totalSizeCap>
      </rollingPolicy>
      <encoder>
        <pattern>${PLAIN_PATTERN}</pattern>
        <charset>UTF-8</charset>
      </encoder>
    </appender>

    <!-- ERROR only. Same rolling rules, capped at 1GB. -->
    <appender name="FILE_ERROR" class="ch.qos.logback.core.rolling.RollingFileAppender">
      <file>${LOG_PATH}/error.log</file>
      <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>ERROR</level>
      </filter>
      <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>${LOG_PATH}/error.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
        <maxFileSize>100MB</maxFileSize>
        <maxHistory>30</maxHistory>
        <totalSizeCap>1GB</totalSizeCap>
      </rollingPolicy>
      <encoder>
        <pattern>${PLAIN_PATTERN}</pattern>
        <charset>UTF-8</charset>
      </encoder>
    </appender>

    <root level="INFO">
      <appender-ref ref="CONSOLE" />
      <appender-ref ref="FILE_ALL" />
      <appender-ref ref="FILE_ERROR" />
    </root>
  </springProfile>

</configuration>
```

- [ ] **Step 4: Ignore `uploads-test/`**

In `.gitignore`, the block that currently reads:

```
logs

uploads
```

becomes:

```
logs

uploads
uploads-test/
```

(`logs` is already ignored; leave it as is.)

- [ ] **Step 5: Run tests and verify nothing is written outside `target/`**

Run:
```bash
rm -rf logs uploads-test
mvn test
ls -d logs uploads-test 2>&1
```
Expected: `BUILD SUCCESS`; `BaseSpringApplicationTests` 2 tests pass; the `ls` prints `ls: cannot access 'logs': No such file or directory` and the same for `uploads-test` (P5 moved the test upload dir under `target/`; if `uploads-test` still appears, P5's test property was not applied: check `grep -n "upload-dir" src/test/resources/application-test.yml`. That fix belongs to P5, not here).

Also confirm the static checks:
```bash
grep -c 'scan=' src/main/resources/logback-spring.xml
grep -c 'totalSizeCap' src/main/resources/logback-spring.xml
```
Expected: `0` then `2`.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/logback-spring.xml src/test/java/com/spring/app/BaseSpringApplicationTests.java .gitignore
git commit -m "$(cat <<'EOF'
fix(logging): console-only under test, plain prod console, capped rolling files

Tests no longer create ./logs. Prod console output carries no ANSI codes,
files roll by size and day with totalSizeCap, and the runtime rescan is gone.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Cluster-safe TokenCleanupService (Redis lock)

**Files:**
- Create: `src/test/java/com/spring/app/shared/services/TokenCleanupServiceTest.java`
- Modify: `src/main/java/com/spring/app/shared/services/TokenCleanupService.java`

**Interfaces:**
- Consumes: `RefreshTokenRepository#deleteExpiredOrRevokedTokens(Instant)` (P3/P1 left it in place; check with `grep -n "deleteExpiredOrRevokedTokens" src/main/java/com/spring/app/modules/auth/repositories/RefreshTokenRepository.java`, which should print one line). A `StringRedisTemplate` bean: Boot auto-configures one unless P1 declared its own `StringRedisTemplate`, in which case Boot's backs off. Either way exactly one exists.
- Produces: `TokenCleanupService.LOCK_KEY = "lock:token-cleanup"`, `TokenCleanupService.LOCK_TTL = Duration.ofMinutes(10)` (package-private, used by the test; Task 8 documents the key).

- [ ] **Step 1: Confirm a single `StringRedisTemplate` bean**

Run: `grep -rnE "StringRedisTemplate[[:space:]]+[a-zA-Z]+[[:space:]]*\(" src/main/java --include=*.java`
Expected: zero or one bean factory method returning `StringRedisTemplate`. If two or more appear, give the constructor parameter in Step 3 the exact bean name of the plain one with `@Qualifier("<name>")` and write the constructor by hand instead of `@RequiredArgsConstructor`.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/spring/app/shared/services/TokenCleanupServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.spring.app.modules.auth.repositories.RefreshTokenRepository;

@ExtendWith(MockitoExtension.class)
class TokenCleanupServiceTest {

  @Mock
  private RefreshTokenRepository refreshTokenRepository;

  @Mock
  private StringRedisTemplate stringRedisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  @InjectMocks
  private TokenCleanupService service;

  @BeforeEach
  void setUp() {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @Test
  void purgesWhenThisInstanceAcquiresTheLock() {
    when(valueOperations.setIfAbsent(eq("lock:token-cleanup"), anyString(), eq(Duration.ofMinutes(10))))
        .thenReturn(true);

    service.cleanupExpiredTokens();

    verify(refreshTokenRepository).deleteExpiredOrRevokedTokens(any(Instant.class));
  }

  @Test
  void skipsWhenAnotherInstanceHoldsTheLock() {
    when(valueOperations.setIfAbsent(eq("lock:token-cleanup"), anyString(), eq(Duration.ofMinutes(10))))
        .thenReturn(false);

    service.cleanupExpiredTokens();

    verifyNoInteractions(refreshTokenRepository);
  }

  @Test
  void skipsWhenRedisIsUnavailable() {
    when(valueOperations.setIfAbsent(eq("lock:token-cleanup"), anyString(), eq(Duration.ofMinutes(10))))
        .thenThrow(new RedisConnectionFailureException("Redis down"));

    service.cleanupExpiredTokens();

    verifyNoInteractions(refreshTokenRepository);
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=TokenCleanupServiceTest`
Expected: FAIL. With the current single-argument constructor, `@InjectMocks` builds the service without Redis, so `skipsWhenAnotherInstanceHoldsTheLock` and `skipsWhenRedisIsUnavailable` fail with `No interactions wanted here` on `refreshTokenRepository`, and the strict stubs report `UnnecessaryStubbingException`.

- [ ] **Step 4: Implement the lock**

Replace the whole of `src/main/java/com/spring/app/shared/services/TokenCleanupService.java` with:

```java
package com.spring.app.shared.services;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.modules.auth.repositories.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Nightly purge of expired or revoked refresh tokens.
 *
 * <p>
 * Every instance schedules the job, so a Redis lock ({@code SET NX EX}) lets
 * exactly one of them run it. The lock is not released when the purge ends; it
 * expires after {@link #LOCK_TTL}. An instance whose scheduler fires a few
 * seconds late therefore still finds it held and skips, instead of running the
 * purge a second time. If Redis is unreachable the run is skipped; the next
 * night retries.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenCleanupService {

  static final String LOCK_KEY = "lock:token-cleanup";
  static final Duration LOCK_TTL = Duration.ofMinutes(10);

  private final RefreshTokenRepository refreshTokenRepository;
  private final StringRedisTemplate stringRedisTemplate;

  @Scheduled(cron = "0 0 2 * * *")
  @Transactional
  public void cleanupExpiredTokens() {
    if (!acquireLock()) {
      return;
    }
    log.info("Starting cleanup of expired/revoked refresh tokens");
    refreshTokenRepository.deleteExpiredOrRevokedTokens(Instant.now());
    log.info("Cleanup of refresh tokens completed");
  }

  private boolean acquireLock() {
    try {
      String owner = ManagementFactory.getRuntimeMXBean().getName();
      Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, owner, LOCK_TTL);
      if (!Boolean.TRUE.equals(acquired)) {
        log.debug("Token cleanup skipped: another instance holds {}", LOCK_KEY);
        return false;
      }
      return true;
    } catch (RuntimeException ex) {
      log.warn("Token cleanup skipped: could not acquire Redis lock {} ({})", LOCK_KEY, ex.getMessage());
      return false;
    }
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=TokenCleanupServiceTest,BaseSpringApplicationTests`
Expected: PASS (3 + 2 tests). `contextLoads` proves the `StringRedisTemplate` bean resolves without Redis running.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/TokenCleanupService.java src/test/java/com/spring/app/shared/services/TokenCleanupServiceTest.java
git commit -m "$(cat <<'EOF'
fix(scheduling): run refresh-token cleanup on one instance via Redis lock

SET NX EX lock:token-cleanup (10 min, not released) so concurrent instances
skip; a Redis failure skips the run instead of failing it.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Grep-gated dead-code removal

Pure deletion. There is no new behaviour to test first; the gate for every step is "the grep shows no remaining use", and the task is verified by a clean compile plus the full test suite. **Rule for every step:** run the grep exactly as written. If its output is empty (or matches the "expected if dead" text), make the deletion. Otherwise **keep** the code and write `kept: <name> (<file:line of the use>)` in the commit body.

**Files (each conditional on its grep):**
- Modify: `src/main/java/com/spring/app/constants/ApplicationConstants.java`
- Modify: `src/main/java/com/spring/app/enums/ERole.java`, `src/main/java/com/spring/app/enums/EPermission.java`
- Create: `src/main/resources/db/migrations/V<next>__demote_manager_role.sql`
- Modify: `src/main/java/com/spring/app/shared/services/RateLimitManagerService.java`
- Modify: `src/main/java/com/spring/app/shared/services/RedisService.java`, `src/main/java/com/spring/app/shared/interfaces/RedisServiceInterface.java`
- Modify: `src/main/java/com/spring/app/shared/services/JwtService.java`, `src/main/java/com/spring/app/shared/interfaces/JwtServiceInterface.java`
- Modify: `src/main/java/com/spring/app/modules/auth/repositories/UserRepository.java`, `.../RefreshTokenRepository.java`
- Modify: `src/main/java/com/spring/app/shared/services/MailService.java`
- Delete: `src/main/java/com/spring/app/modules/auth/dto/request/AuthRequestDto.java`
- Delete: `src/main/java/com/spring/app/common/validation/groups/ValidationGroups.java`
- Modify: `src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/spring/app/BaseSpringApplication.java`
- Modify: `src/main/java/com/spring/app/configs/properties/ApplicationProperties.java`, `src/main/resources/application.yml`, `src/main/resources/application-prod.yml`

**Interfaces:**
- Consumes: the post-P5 code base.
- Produces: no jsoup import anywhere if `sendContentEmail` was removed (Task 4 relies on this to drop the dependency). Roles are `USER` and `ADMIN` only if MANAGER was removed (Task 8 documents whichever is true).

- [ ] **Step 1: `ApplicationConstants`: list the constants still in use**

Run:
```bash
for c in CACHE_USERS CACHE_TOKENS CACHE_PERMISSIONS OTP_EXPIRATION_MINUTES JWT_EXPIRATION_HOURS \
         REFRESH_TOKEN_EXPIRATION_DAYS MIN_PASSWORD_LENGTH MAX_EMAIL_LENGTH MAX_NAME_LENGTH \
         API_BASE_PATH AUTH_BASE_PATH USER_BASE_PATH FILES_BASE_PATH EMAIL_ALREADY_EXISTS \
         USER_NOT_FOUND INVALID_CREDENTIALS ACCOUNT_NOT_ACTIVE; do
  printf '%-30s %s\n' "$c" "$(grep -rnw "$c" src --include=*.java | grep -v 'constants/ApplicationConstants.java' | wc -l)"
done
```
Expected at HEAD (P1–P5 are not expected to change this): `AUTH_BASE_PATH`, `USER_BASE_PATH`, `FILES_BASE_PATH` > 0; every other constant `0`. `API_BASE_PATH` shows 0 external uses but is referenced by the three path constants inside the same file, so it stays while any of them stays.

- [ ] **Step 2: Rewrite `ApplicationConstants` with only the used constants**

If Step 1 matched the expectation, replace the whole file with:

```java
package com.spring.app.constants;

/** Base paths shared by the controllers, the security whitelist and the rate-limit filter. */
public final class ApplicationConstants {

  public static final String API_BASE_PATH = "/api/v1";
  public static final String AUTH_BASE_PATH = API_BASE_PATH + "/auth";
  public static final String USER_BASE_PATH = API_BASE_PATH + "/users";
  public static final String FILES_BASE_PATH = API_BASE_PATH + "/files";

  private ApplicationConstants() {
  }
}
```
If any other constant had a non-zero count, keep that constant too, under the same 2-space style.

- [ ] **Step 3: `ERole.MANAGER` / `MANAGER_*` permissions**

Run:
```bash
grep -rnw "MANAGER" src --include=*.java | grep -v "src/main/java/com/spring/app/enums/"
grep -rnE "MANAGER_(READ|UPDATE|CREATE|DELETE)|PERMISSION_MANAGER|ROLE_MANAGER" src | grep -v "src/main/java/com/spring/app/enums/"
grep -rn "MANAGER" src/main/resources src/test/resources
```
Expected if dead: all three print nothing. Then:

Replace the enum constants of `src/main/java/com/spring/app/enums/ERole.java` (the `USER(...)`, `ADMIN(...)`, `MANAGER(...)` block) with:

```java
  USER(Collections.emptySet()),
  ADMIN(
      Set.of(
          ADMIN_READ,
          ADMIN_UPDATE,
          ADMIN_DELETE,
          ADMIN_CREATE));
```

Replace the constants of `src/main/java/com/spring/app/enums/EPermission.java` with:

```java
  ADMIN_READ("PERMISSION_ADMIN_READ"),
  ADMIN_UPDATE("PERMISSION_ADMIN_UPDATE"),
  ADMIN_CREATE("PERMISSION_ADMIN_CREATE"),
  ADMIN_DELETE("PERMISSION_ADMIN_DELETE"),
  ;
```

- [ ] **Step 4: Migration for any persisted `MANAGER` rows (only if Step 3 removed MANAGER)**

`users.role` is a `VARCHAR` mapped with `@Enumerated(EnumType.STRING)`; a row still holding `MANAGER` would make that user unloadable. Find the next version number:
```bash
ls src/main/resources/db/migrations/
```
Expected: `V1__create_user.sql`, `V2__create_refresh_tokens.sql`, plus any migrations P1–P5 added. Use N = highest existing version + 1 (N = 3 if only V1 and V2 exist). Create `src/main/resources/db/migrations/V<N>__demote_manager_role.sql`:

```sql
-- ERole.MANAGER was removed: no endpoint ever granted or checked it. A row that
-- still carries it could not be loaded (Hibernate cannot map an unknown enum
-- constant), so demote it to the least-privileged role.
UPDATE users SET role = 'USER' WHERE role = 'MANAGER';
```

- [ ] **Step 5: `RateLimitManagerService.getRemainingRequests` / `resetRateLimit`**

Run:
```bash
grep -rnw "getRemainingRequests" src --include=*.java | grep -v "shared/services/RateLimitManagerService.java"
grep -rnw "resetRateLimit" src --include=*.java | grep -v "shared/services/RateLimitManagerService.java"
```
Expected if dead: no output for a method. Delete that method's whole declaration (Javadoc + body) from `RateLimitManagerService.java`. At HEAD they are `public long getRemainingRequests(ERateLimitEndpoint endpointType, String time, String identifier) { ... }` and `public void resetRateLimit(String endpointType, String identifier) { ... }`.

- [ ] **Step 6: `RedisService` methods orphaned by Step 5 (or already unused)**

Run:
```bash
for m in getRateLimitValue deleteRateLimitKey hasRateLimitKey setRateLimitValue hasKey; do
  printf '%-20s %s\n' "$m" "$(grep -rnw "$m" src --include=*.java \
    | grep -v 'shared/services/RedisService.java' | grep -v 'shared/interfaces/RedisServiceInterface.java' | wc -l)"
done
```
Expected if dead: `0` for a method. For each method with `0`, delete its declaration from `RedisServiceInterface.java` and its `@Override` implementation (with Javadoc) from `RedisService.java`. Do not touch any other method (P1 owns their semantics).

- [ ] **Step 7: `JwtService.extractUserId / extractRole / extractUserStatus`**

Run:
```bash
grep -rnwE "extractUserId|extractRole|extractUserStatus" src --include=*.java \
  | grep -v "shared/services/JwtService.java" | grep -v "shared/interfaces/JwtServiceInterface.java"
```
Expected if dead: no output. Delete the three declarations from `JwtServiceInterface.java` and their `@Override` implementations from `JwtService.java`. Then remove imports that became unused in each of the two files. Check with:
```bash
for f in src/main/java/com/spring/app/shared/services/JwtService.java src/main/java/com/spring/app/shared/interfaces/JwtServiceInterface.java; do
  echo "== $f"; grep -nE "\bUUID\b|\bERole\b|\bEUserStatus\b" "$f"
done
```
An `import` line whose type appears nowhere else in that file gets deleted.

- [ ] **Step 8: Unused repository finders**

Run:
```bash
for m in existsByEmailAndStatus existsByEmailAndStatusNot findByEmailAndStatus findByEmail findByIdAndStatus; do
  printf '%-28s %s\n' "$m" "$(grep -rnw "$m" src --include=*.java | grep -v 'repositories/UserRepository.java' | wc -l)"
done
for m in findByToken findAllByUserAndIsRevokedFalse revokeAllByUser deleteExpiredOrRevokedTokens; do
  printf '%-28s %s\n' "$m" "$(grep -rnw "$m" src --include=*.java | grep -v 'repositories/RefreshTokenRepository.java' | wc -l)"
done
```
Expected at HEAD: `existsByEmailAndStatus 0`, `findByEmailAndStatus 0`, `findByIdAndStatus 0`, `findAllByUserAndIsRevokedFalse 0`; the others > 0. P1/P3 may have changed this, so act on the actual counts. For each `0`, delete that method declaration from its repository interface. If `findByIdAndStatus` goes, also delete the `org.springframework.lang.NonNull` import when nothing else in `UserRepository.java` uses it. If `findAllByUserAndIsRevokedFalse` goes, delete `import java.util.List;` from `RefreshTokenRepository.java` when nothing else uses `List`.

- [ ] **Step 9: `MailService.sendContentEmail`**

Run:
```bash
grep -rnw "sendContentEmail" src --include=*.java | grep -v "shared/services/MailService.java"
```
Expected if dead: no output. Delete `sendContentEmail(...)` and the private `isHtmlContent(...)` helper from `MailService.java`, then the imports `org.jsoup.Jsoup` and `org.jsoup.nodes.Document`. Then confirm:
```bash
grep -rn "org.jsoup" src
```
Expected: no output (Task 4 removes the dependency on that basis).

- [ ] **Step 10: `AuthRequestDto` and `ValidationGroups`**

Run:
```bash
grep -rnw "AuthRequestDto" src --include=*.java | grep -v "dto/request/AuthRequestDto.java"
grep -rnw "ValidationGroups" src --include=*.java | grep -v "validation/groups/ValidationGroups.java"
```
Expected if dead: no output for each. Then:
```bash
git rm src/main/java/com/spring/app/modules/auth/dto/request/AuthRequestDto.java
git rm src/main/java/com/spring/app/common/validation/groups/ValidationGroups.java
```

- [ ] **Step 11: Unreachable `MessagingException` handler**

`jakarta.mail.MessagingException` is checked. The handler can only fire if a request thread can throw it. Run:
```bash
grep -rn "MessagingException" src/main/java --include=*.java | grep -v "exceptions/GlobalExceptionHandler.java"
grep -rn "sendOtpEmail(" src/main/java --include=*.java
```
Expected if unreachable: the first command lists only `MailService.java` / `MailServiceInterface.java` (and possibly `OtpEmailService.java` inside its `@Async` method's catch). The second lists only the declarations and the call inside `OtpEmailService`'s `@Async("emailExecutor")` method. No controller or synchronous service throws it. Then delete from `GlobalExceptionHandler.java` the method annotated `@ExceptionHandler(MessagingException.class)` (with Javadoc) and the `import jakarta.mail.MessagingException;` line. If `grep -n "MessagingException" src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java` already prints nothing (P4 dropped it), skip.

- [ ] **Step 12: Duplicate `@EnableCaching`**

Run: `grep -rn "@EnableCaching" src/main/java`
Expected: two hits, `BaseSpringApplication.java` and `configs/CacheConfig.java`. If so, remove `@EnableCaching` and `import org.springframework.cache.annotation.EnableCaching;` from `BaseSpringApplication.java`, leaving:

```java
package com.spring.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class BaseSpringApplication {

  public static void main(String[] args) {
    SpringApplication.run(BaseSpringApplication.class, args);
  }
}
```
If only `BaseSpringApplication` has it (P1 removed it from `CacheConfig`), keep it.

- [ ] **Step 13: `application.frontend-url` (only if P3 did not wire it)**

Run:
```bash
grep -rnE "getFrontendUrl|frontendUrl" src/main/java src/test/java --include=*.java | grep -v "configs/properties/ApplicationProperties.java"
```
Expected if dead: no output (P3 is expected to use it for the OAuth2 redirect, in which case there IS output and you keep everything). If dead:
- delete the `frontendUrl` field and its Javadoc from `ApplicationProperties.java`;
- delete from `src/main/resources/application.yml` the two lines
  ```yaml
    # Public base URL of the frontend, used for links inside outgoing e-mails.
    frontend-url: ${FRONTEND_URL:http://localhost:3000}
  ```
- delete `  frontend-url: ${FRONTEND_URL}` from `src/main/resources/application-prod.yml`;
- `FRONTEND_URL` in `.env.example` is removed by Task 7's script.

- [ ] **Step 14: Compile and run the full suite**

Run: `mvn clean test`
Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 15: Commit**

```bash
git add -A src
git commit -m "$(cat <<'EOF'
chore: remove dead code confirmed unused by grep

<list each deletion made, one per line, and each `kept: <name> (<file:line>)`>

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```
(The angle-bracket line is the commit-body content you write from the outcomes of Steps 1–13. It is the only free text in this task.)

---

### Task 4: pom.xml and build hygiene

Config-only. Verified by the dependency tree, effective version, and a green `mvn test` without Mockito's self-attach warning.

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml` (remove `spring.cloud.compatibility-verifier`)
- Modify: `src/test/resources/application-test.yml` (remove explicit `H2Dialect`)

**Interfaces:**
- Consumes: Task 3 removed the last `org.jsoup` import (if it did not, keep jsoup).
- Produces: property `${org.mockito:mockito-core:jar}` (from `maven-dependency-plugin:properties`), reused by Task 5's failsafe config. The test profile no longer pins a Hibernate dialect, which Task 5 needs so the same `test` profile can run against MySQL.

- [ ] **Step 1: Determine the latest Spring Boot 3.5.x patch**

Run:
```bash
curl -s https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml \
  | grep -oE '<version>3\.5\.[0-9]+</version>' | sed -E 's#</?version>##g' | sort -V | tail -1
```
Expected: one line `3.5.N`. N is at least 3, because `3.5.3` is already in the local `~/.m2`. Use exactly that string in Step 2. If there is no network, fall back to the newest 3.5.x in the local repository: `ls ~/.m2/repository/org/springframework/boot/spring-boot-starter-parent/ | grep '^3\.5\.' | sort -V | tail -1`, and say so in the commit body.

- [ ] **Step 2: Bump the parent**

In `pom.xml`, change
```xml
    <version>3.5.0</version>
    <relativePath /> <!-- lookup parent from repository -->
```
to the Step 1 version, e.g. for output `3.5.7`:
```xml
    <version>3.5.7</version>
    <relativePath /> <!-- lookup parent from repository -->
```

- [ ] **Step 3: Confirm `spring-boot-starter-web-services` is unused, then remove it**

Run: `grep -rnE "org\.springframework\.ws|jakarta\.xml\.ws|jakarta\.xml\.bind|\.wsdl|\.xsd" src`
Expected: no output. Then delete from `pom.xml`:
```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web-services</artifactId>
    </dependency>
```

- [ ] **Step 4: Remove jsoup (if Task 3 Step 9 left no use)**

Run: `grep -rn "org.jsoup" src`
Expected: no output. Then delete:
```xml
    <!-- https://mvnrepository.com/artifact/org.jsoup/jsoup -->
    <dependency>
      <groupId>org.jsoup</groupId>
      <artifactId>jsoup</artifactId>
      <version>1.20.1</version>
    </dependency>
```

- [ ] **Step 5: Drop the Lettuce pin**

Delete:
```xml
    <!-- https://mvnrepository.com/artifact/io.lettuce/lettuce-core -->
    <dependency>
      <groupId>io.lettuce</groupId>
      <artifactId>lettuce-core</artifactId>
      <version>6.7.1.RELEASE</version>
    </dependency>
```
(`spring-boot-starter-data-redis` brings the Boot-managed Lettuce.)

- [ ] **Step 6: Declare `commons-lang3` explicitly if still used**

Run: `grep -rln "org.apache.commons.lang3" src/main/java`
Expected at HEAD: `src/main/java/com/spring/app/shared/services/CustomOAuth2UserService.java` (uses `RandomStringUtils.secureStrong()`, available from commons-lang3 3.16). If anything is printed, add right after the `spring-boot-starter-validation` dependency (version managed by Boot):
```xml
    <!-- Used directly (RandomStringUtils); version managed by Spring Boot. -->
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-lang3</artifactId>
    </dependency>
```
If nothing is printed, add nothing.

- [ ] **Step 7: Load Mockito as a `-javaagent` instead of self-attaching**

In `<build><plugins>`, after the `maven-compiler-plugin` block, add:

```xml
      <!--
        Exposes ${org.mockito:mockito-core:jar} (path of the resolved jar) so the
        test JVMs can load Mockito's inline mock maker as an agent. Self-attaching
        is deprecated on JDK 21+ and prints a warning on every run.
      -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-dependency-plugin</artifactId>
        <executions>
          <execution>
            <goals>
              <goal>properties</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <argLine>-javaagent:${org.mockito:mockito-core:jar}</argLine>
        </configuration>
      </plugin>
```

- [ ] **Step 8: Remove `spring.cloud.compatibility-verifier` (no Spring Cloud on the classpath)**

Run: `grep -rn "spring-cloud\|org.springframework.cloud" pom.xml src/main/java`
Expected: no output. Then delete from `src/main/resources/application.yml`:
```yaml
  cloud:
    compatibility-verifier:
      enabled: false

```

- [ ] **Step 9: Remove the explicit H2 dialect from the test profile**

In `src/test/resources/application-test.yml`, change
```yaml
    properties:
      hibernate:
        format_sql: false
        dialect: org.hibernate.dialect.H2Dialect
```
to
```yaml
    properties:
      hibernate:
        format_sql: false
```
(Hibernate 6 detects the dialect from the JDBC connection.)

- [ ] **Step 10: Verify**

Run:
```bash
mvn -q help:evaluate -Dexpression=project.parent.version -DforceStdout; echo
mvn dependency:tree -Dincludes=org.springframework.boot:spring-boot-starter-web-services,org.jsoup,io.lettuce:lettuce-core,org.apache.commons:commons-lang3
mvn clean test 2>&1 | tee /tmp/p6-task4.log | tail -20
grep -c "self-attaching" /tmp/p6-task4.log
```
Expected:
- first line: the Step 1 version;
- dependency tree: no `spring-boot-starter-web-services`, no `jsoup`; `io.lettuce:lettuce-core:jar:<Boot-managed version>` only under `spring-boot-starter-data-redis`; `org.apache.commons:commons-lang3:jar:<version ≥ 3.16.0>:compile` (if Step 6 added it);
- `BUILD SUCCESS`;
- `0` (no "Mockito is currently self-attaching" warning).

- [ ] **Step 11: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "$(cat <<'EOF'
build: Boot 3.5.x patch upgrade, drop unused deps, Mockito as javaagent

Remove spring-boot-starter-web-services, jsoup and the lettuce-core pin;
declare commons-lang3 (used directly); load Mockito via -javaagent; drop the
Spring Cloud verifier key and the hard-coded H2 dialect in tests.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Opt-in integration tests (Testcontainers MySQL + Redis, `-Pintegration`)

**Files:**
- Modify: `pom.xml` (Testcontainers dependency, `integration` profile)
- Create: `src/test/java/com/spring/app/integration/IntegrationTestBase.java`
- Create: `src/test/java/com/spring/app/integration/FlywayMigrationIT.java`
- Create: `src/test/java/com/spring/app/integration/AuthRoundTripIT.java`
- Create: `src/test/java/com/spring/app/integration/JpaAuditingIT.java`

**Interfaces:**
- Consumes: `${org.mockito:mockito-core:jar}` from Task 4. Test profile without a pinned dialect (Task 4 Step 9). Endpoints (post-P3): `POST /api/v1/auth/register`, `POST /api/v1/auth/activate`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh-token`, `DELETE /api/v1/auth/logout`, `GET /api/v1/users/me`. Response shape `BaseResponse` → `$.data.accessToken`, `$.data.refreshToken`, `$.data.email`. `UserRepository` (`JpaRepository<User, UUID>`), `User.builder()`.
- Produces: `IntegrationTestBase` (abstract; containers + properties) for future `*IT` classes. Run command `mvn -Pintegration verify`.

Behaviour each IT relies on, and where it comes from:
- P3: a refresh JWT is rejected as a Bearer access token (`token_type` claim) → 401 on `/api/v1/users/me`; logout requires a valid access token.
- P1: `/api/v1/users/me` is served from the Redis cache on the second call without (de)serialization errors.
- P1: `ApplicationAuditAware` records the authenticated `User`'s id, and `"SYSTEM"` when anonymous.
- P2: the OTP lands in Redis under a key containing the lower-cased e-mail (`otp:<email>`).

- [ ] **Step 1: Pre-check the contracts the ITs rely on**

Run:
```bash
grep -rnE "@(Get|Post|Put|Delete)Mapping|@RequestMapping" src/main/java/com/spring/app/modules/auth/controller src/main/java/com/spring/app/modules/user/controller
grep -n "SYSTEM\|Optional.of" src/main/java/com/spring/app/configs/ApplicationAuditAware.java
grep -rn "skip-email-verification" src/main/resources src/test/resources
grep -n "storeOtp\|otp:" src/main/java/com/spring/app/shared/services/OtpEmailService.java src/main/java/com/spring/app/utils/OtpFunctionUtil.java
```
Expected:
1. Mappings resolve to the six endpoints listed under Interfaces. If P3 renamed any, use the new path in `AuthRoundTripIT`.
2. Anonymous auditor is the literal `"SYSTEM"`, and the authenticated auditor is `user.getId().toString()`. If P1 chose different values, change the two expected values in `JpaAuditingIT` to match.
3. `skip-email-verification` is `false` or absent in `application-test.yml` (the round trip needs a PENDING user and a real activation). If the test profile sets it `true`, add `registry.add("<that full key>", () -> "false");` to `IntegrationTestBase`.
4. The OTP is stored in Redis before or independently of the SMTP send. Mail is mocked in the base class, so the send always "succeeds".

- [ ] **Step 2: Write the integration tests**

Create `src/test/java/com/spring/app/integration/IntegrationTestBase.java`:

```java
package com.spring.app.integration;

import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * Base class for the opt-in integration tests ({@code mvn -Pintegration verify}).
 *
 * <p>
 * Real MySQL (schema built by Flyway, checked by {@code ddl-auto=validate})
 * and real Redis, started once per JVM and shared by every {@code *IT} class
 * so the Spring context is cached across them. Ryuk removes the containers
 * when the JVM exits. Everything else comes from the {@code test} profile.
 * SMTP is replaced by a mock: these tests are about persistence, Redis and
 * the HTTP flow, not mail delivery.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
      .withDatabaseName("db_test_spring")
      .withUsername("springapp")
      .withPassword("springapp")
      .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

  static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
      .withExposedPorts(6379);

  static {
    MYSQL.start();
    REDIS.start();
  }

  @MockitoBean
  protected JavaMailSender javaMailSender;

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
        + "/db_test_spring?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

    // Production schema path: Flyway migrates, Hibernate only validates.
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    // Explicitly empty: a developer's .env REDIS_PASSWORD must not reach a passwordless container.
    registry.add("spring.data.redis.password", () -> "");
    registry.add("spring.data.redis.ssl.enabled", () -> "false");
    registry.add("application.redis.fail-fast", () -> "true");

    // Every request in these tests comes from 127.0.0.1; lift the AUTH quota so
    // the suite never trips it no matter how many ITs run in one JVM.
    registry.add("application.rate-limit.auth.requests-per-minute", () -> "1000");
    registry.add("application.rate-limit.auth.requests-per-hour", () -> "10000");
    registry.add("application.rate-limit.auth.requests-per-day", () -> "100000");
  }

  @BeforeEach
  void stubMailSender() {
    when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
  }
}
```

Create `src/test/java/com/spring/app/integration/FlywayMigrationIT.java`:

```java
package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

/** Flyway builds the real MySQL schema and Hibernate validates the entities against it. */
class FlywayMigrationIT extends IntegrationTestBase {

  @Autowired
  private Flyway flyway;

  @Autowired
  private EntityManagerFactory entityManagerFactory;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void everyMigrationOnTheClasspathIsApplied() throws IOException {
    int migrationFiles = new PathMatchingResourcePatternResolver()
        .getResources("classpath:db/migrations/V*.sql").length;

    MigrationInfoService info = flyway.info();

    assertThat(info.pending()).isEmpty();
    assertThat(info.applied())
        .hasSize(migrationFiles)
        .allSatisfy(migration -> assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
  }

  @Test
  void hibernateValidatedTheFlywaySchema() {
    // The context only starts when validation passes; this pins that it was
    // `validate` that ran, not `update`/`create` papering over a bad migration.
    assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");

    Integer tables = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_name IN ('users', 'refresh_tokens')",
        Integer.class);
    assertThat(tables).isEqualTo(2);
  }
}
```

Create `src/test/java/com/spring/app/integration/AuthRoundTripIT.java`:

```java
package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * register -> activate (OTP read back from real Redis) -> login -> cached
 * profile read -> refresh (rotation) -> logout, through the full filter chain
 * (rate limit, JWT, security) against real MySQL and Redis.
 */
class AuthRoundTripIT extends IntegrationTestBase {

  private static final String PASSWORD = "StrongP@ss123";
  /** A stored OTP value: six digits, optionally JSON-quoted by the value serializer. */
  private static final Pattern OTP_VALUE = Pattern.compile("^\"?(\\d{6})\"?$");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private RedisConnectionFactory redisConnectionFactory;

  @Test
  void registerActivateLoginRefreshLogout() throws Exception {
    String email = "it-" + UUID.randomUUID() + "@example.com";

    // 1. Register: PENDING account, OTP generated on the e-mail executor.
    mockMvc.perform(post("/api/v1/auth/register").contentType(APPLICATION_JSON)
        .content(json(Map.of("email", email, "password", PASSWORD, "fullName", "Integration Tester"))))
        .andExpect(status().isCreated());

    // 2. Activate with the OTP that landed in Redis.
    String otp = awaitOtpFor(email);
    mockMvc.perform(post("/api/v1/auth/activate").contentType(APPLICATION_JSON)
        .content(json(Map.of("email", email, "otp", otp))))
        .andExpect(status().isOk());

    // 3. Login.
    JsonNode login = body(mockMvc.perform(post("/api/v1/auth/login").contentType(APPLICATION_JSON)
        .content(json(Map.of("email", email, "password", PASSWORD))))
        .andExpect(status().isOk()));
    String accessToken = login.at("/data/accessToken").asText();
    String refreshToken = login.at("/data/refreshToken").asText();
    assertThat(accessToken).isNotBlank();
    assertThat(refreshToken).isNotBlank();

    // 4. Profile twice: the second read comes from the Redis cache (P1 serializer).
    for (int i = 0; i < 2; i++) {
      mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + accessToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.email").value(email));
    }

    // 5. A refresh token is not an access token (P3 token_type claim).
    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + refreshToken))
        .andExpect(status().isUnauthorized());

    // JWTs carry second-resolution iat/exp and no jti: two tokens minted for the
    // same user within one second are byte-identical. Cross a second boundary
    // so this test exercises rotation, not that collision.
    Thread.sleep(1_100);

    // 6. Refresh: a new pair, and the old refresh token is revoked.
    JsonNode refreshed = body(mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(APPLICATION_JSON)
        .content(json(Map.of("refreshToken", refreshToken))))
        .andExpect(status().isOk()));
    String newAccessToken = refreshed.at("/data/accessToken").asText();
    String newRefreshToken = refreshed.at("/data/refreshToken").asText();
    assertThat(newAccessToken).isNotBlank().isNotEqualTo(accessToken);
    assertThat(newRefreshToken).isNotBlank().isNotEqualTo(refreshToken);

    mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(APPLICATION_JSON)
        .content(json(Map.of("refreshToken", refreshToken))))
        .andExpect(status().is4xxClientError());

    // 7. Logout.
    mockMvc.perform(delete("/api/v1/auth/logout").header(AUTHORIZATION, "Bearer " + newAccessToken))
        .andExpect(status().isOk());

    // 8. After logout the access token is blacklisted and the refresh token revoked.
    mockMvc.perform(get("/api/v1/users/me").header(AUTHORIZATION, "Bearer " + newAccessToken))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(APPLICATION_JSON)
        .content(json(Map.of("refreshToken", newRefreshToken))))
        .andExpect(status().is4xxClientError());
  }

  private String json(Map<String, String> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }

  private JsonNode body(ResultActions result) throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * Polls Redis for the OTP written by the asynchronous e-mail task. It looks
   * for any plain string key that contains the e-mail and holds exactly six
   * digits. That way the test does not depend on the exact key namespace or
   * value serializer P2 chose. Rate-limit counters and Spring cache entries
   * also contain the e-mail but never hold a bare six-digit value.
   */
  private String awaitOtpFor(String email) throws InterruptedException {
    byte[] pattern = ("*" + email + "*").getBytes(StandardCharsets.UTF_8);
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      try (RedisConnection connection = redisConnectionFactory.getConnection()) {
        Set<byte[]> keys = connection.keyCommands().keys(pattern);
        if (keys != null) {
          for (byte[] key : keys) {
            if (connection.keyCommands().type(key) != DataType.STRING) {
              continue;
            }
            byte[] value = connection.stringCommands().get(key);
            if (value == null) {
              continue;
            }
            Matcher matcher = OTP_VALUE.matcher(new String(value, StandardCharsets.UTF_8));
            if (matcher.matches()) {
              return matcher.group(1);
            }
          }
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("No OTP for " + email + " appeared in Redis within 10s");
  }
}
```

Create `src/test/java/com/spring/app/integration/JpaAuditingIT.java`:

```java
package com.spring.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

/** Auditing columns are populated on real MySQL inserts and updates. */
class JpaAuditingIT extends IntegrationTestBase {

  @Autowired
  private UserRepository userRepository;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anonymousInsertIsStampedWithSystemAuditorAndTimestamps() {
    User saved = userRepository.saveAndFlush(newUser());

    User reloaded = userRepository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getCreatedAt()).isNotNull();
    assertThat(reloaded.getUpdatedAt()).isNotNull();
    assertThat(reloaded.getCreatedBy()).isEqualTo("SYSTEM");
    assertThat(reloaded.getUpdatedBy()).isEqualTo("SYSTEM");
  }

  @Test
  void authenticatedInsertAndUpdateAreStampedWithTheActorsId() {
    User actor = userRepository.saveAndFlush(newUser());
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
    String actorId = actor.getId().toString();

    User target = userRepository.saveAndFlush(newUser());
    assertThat(target.getCreatedBy()).isEqualTo(actorId);

    target.setFullName("Renamed By Actor");
    userRepository.saveAndFlush(target);

    User reloaded = userRepository.findById(target.getId()).orElseThrow();
    assertThat(reloaded.getCreatedBy()).isEqualTo(actorId);
    assertThat(reloaded.getUpdatedBy()).isEqualTo(actorId);
    assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(reloaded.getCreatedAt());
  }

  private static User newUser() {
    return User.builder()
        .email("audit-" + UUID.randomUUID() + "@example.com")
        .password("not-a-real-hash")
        .fullName("Audit Subject")
        .build();
  }
}
```

- [ ] **Step 3: Run to verify they fail (no Testcontainers, no profile yet)**

Run: `mvn -Pintegration verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at `testCompile` with `package org.testcontainers.containers does not exist` (and a warning that profile `integration` does not exist).

- [ ] **Step 4: Add Testcontainers and the `integration` profile to `pom.xml`**

After the `h2` test dependency add (versions come from the Testcontainers BOM that Spring Boot imports):

```xml
    <!-- Opt-in integration tests only (mvn -Pintegration verify); never started by `mvn test`. -->
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>mysql</artifactId>
      <scope>test</scope>
    </dependency>
```

After `</build>` and before `</project>` add:

```xml
  <!--
    mvn -Pintegration verify
    Runs the *IT classes with failsafe against Testcontainers MySQL + Redis
    (Docker required). Plain `mvn test` never runs them: surefire's default
    includes do not match *IT.
  -->
  <profiles>
    <profile>
      <id>integration</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <configuration>
              <argLine>-javaagent:${org.mockito:mockito-core:jar}</argLine>
            </configuration>
            <executions>
              <execution>
                <goals>
                  <goal>integration-test</goal>
                  <goal>verify</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

- [ ] **Step 5: Run the integration tests (Docker required)**

Run:
```bash
docker info --format '{{.ServerVersion}}'
mvn -Pintegration verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: the Docker server version prints, then failsafe reports
`Tests run: 2 ... in com.spring.app.integration.FlywayMigrationIT`,
`Tests run: 1 ... in com.spring.app.integration.AuthRoundTripIT`,
`Tests run: 2 ... in com.spring.app.integration.JpaAuditingIT`, `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

Troubleshooting (act only on the matching symptom):
- `client version 1.32 is too old. Minimum supported API version is 1.44`: Docker Engine 29+ needs a newer Testcontainers than the Boot-managed one. Get the newest 1.x: `curl -s https://repo.maven.apache.org/maven2/org/testcontainers/testcontainers/maven-metadata.xml | grep -oE '<version>1\.[0-9]+\.[0-9]+</version>' | sed -E 's#</?version>##g' | sort -V | tail -1`. Add `<testcontainers.version>THAT_VERSION</testcontainers.version>` inside `<properties>` and rerun.
- `Could not find a valid Docker environment`: Docker is not available. The ITs cannot run on this machine. Do not weaken them. `mvn test` (Step 6) is the hermetic gate. Record "ITs not executed: no Docker" in the commit body.
- A failing assertion that matches a contract from Step 1 that a previous plan changed: align the expectation with the code (e.g. the auditor value) and note it in the commit body. A 500 anywhere is a real bug. Stop and report it instead of loosening the assertion.

- [ ] **Step 6: Confirm `mvn test` stays hermetic and ignores the ITs**

Run:
```bash
mvn clean test 2>&1 | tee /tmp/p6-task5.log | tail -5
grep -c "com.spring.app.integration" /tmp/p6-task5.log
```
Expected: `BUILD SUCCESS`, then `0` (no IT class ran or was mentioned by surefire). Run this with Docker stopped if you can (`sudo systemctl stop docker` or quit Docker Desktop) to prove nothing needs it.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/test/java/com/spring/app/integration
git commit -m "$(cat <<'EOF'
test: opt-in Testcontainers integration tests behind -Pintegration

Flyway migrate + ddl-auto=validate on MySQL 8, the
register/activate/login/refresh/logout round trip on real Redis, and JPA
auditing on save. Run with `mvn -Pintegration verify`; `mvn test` is unchanged.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Dockerfile and Compose

Config-only. Verified with the Docker commands below. A no-Docker fallback is given for each check.

**Files:**
- Rewrite: `Dockerfile`
- Rewrite: `docker-compose.yml`
- Create: `docker-compose.dev.yml`
- Rewrite: `docker-compose.debug.yml`
- Modify: `docker-compose.prod.yml`

**Interfaces:**
- Consumes: logback writes under `${LOG_PATH}` (Task 1): `/app/logs` by default in the image, `/var/log/springapp` under prod compose.
- Produces: image user `app` (UID/GID 10001), writable `/app/uploads`, `/app/logs`, `/var/log/springapp`. Compose usage documented in Task 8:
  - `docker compose up` → prod profile, needs a full `.env`;
  - `docker compose -f docker-compose.yml -f docker-compose.dev.yml up` → dev profile;
  - `+ -f docker-compose.debug.yml` → JDWP on `127.0.0.1:5005`.

- [ ] **Step 1: Confirm which HTTP client the runtime image has**

Run: `docker run --rm eclipse-temurin:21-jre-alpine sh -c 'which wget curl'`
Expected: exactly one line, `/usr/bin/wget` (BusyBox), and exit status 1 because `curl` is absent. That is why the healthchecks switch to `wget`.
No Docker: open `https://github.com/adoptium/containers/tree/main/21/jre/alpine` and read the Dockerfile's persistent `apk add` list. `curl` is not in it, while `wget` is a BusyBox applet in every Alpine base. Same conclusion.

- [ ] **Step 2: Rewrite the `Dockerfile`**

Replace the whole file with:

```dockerfile
# ============================
# Stage 1: Build the application
# ============================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy pom.xml and download dependencies first (cache layer)
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Copy source code and build (tests run in CI, not in the image build)
COPY src ./src
RUN mvn -B clean package -DskipTests

# ============================
# Stage 2: Run the application
# ============================
# JRE only: no compiler or JDK tools in the runtime image.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Unprivileged runtime user with a fixed UID/GID (10001) so volume ownership is
# predictable. The writable directories are created here, owned by that user;
# named volumes mounted on them inherit the ownership on first use.
RUN addgroup -S -g 10001 app \
  && adduser -S -D -H -u 10001 -G app app \
  && mkdir -p /app/uploads /app/logs /var/log/springapp \
  && chown -R app:app /app /var/log/springapp

COPY --from=builder --chown=app:app /build/target/springapp-0.0.1-SNAPSHOT.jar app.jar

USER app:app

# Expose port (matches SERVER_PORT's default in application.yml)
EXPOSE 8080

# Fail-closed default: application.yml falls back to the "dev" profile when
# SPRING_PROFILES_ACTIVE is unset, which boots with the committed dev JWT
# secret and open Swagger UI. The prod profile requires DB_HOST,
# JWT_SECRET_KEY and friends with no fallback, so a misconfigured
# `docker run` crashes immediately instead of silently serving with dev secrets.
ENV SPRING_PROFILES_ACTIVE=prod

# Container-aware heap: 75% of the container memory limit (the JVM reads the
# cgroup limit), and exit on OOM so the orchestrator restarts a clean process.
# JAVA_TOOL_OPTIONS (e.g. the debug overlay's JDWP agent) is added on top.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
```

- [ ] **Step 3: Rewrite `docker-compose.yml` (base stack, prod profile by default)**

Replace the whole file with:

```yaml
# =============================================================================
#  Base stack: MySQL + Redis + the app.
#
#  The app runs the PROD profile here, so it needs a .env with the prod
#  variables (see .env.example). Local development adds the dev overlay:
#
#    docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
#
#  Values come from .env. Compose uses that same file both for ${...}
#  interpolation here and, via env_file, for the app container.
# =============================================================================

services:
  # ===============================
  # = MySQL Database Service
  # ===============================
  mysql:
    image: mysql:8.0
    container_name: springapp-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-rootpassword}
      MYSQL_DATABASE: ${DB_NAME:-db_test_spring}
      # The MySQL image refuses MYSQL_USER=root, so DB_USERNAME must name a
      # dedicated application user when running through Compose.
      MYSQL_USER: ${DB_USERNAME:-springapp}
      MYSQL_PASSWORD: ${DB_PASSWORD:-springapp}
      TZ: UTC
    ports:
      - "${DB_PORT:-3306}:3306"
    volumes:
      # db/migrations is NOT mounted into docker-entrypoint-initdb.d: Flyway
      # owns the schema, and letting MySQL replay the same files would leave
      # flyway_schema_history out of step with the database.
      - mysql_data:/var/lib/mysql
    command: --default-authentication-plugin=mysql_native_password --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD:-rootpassword}"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - springapp-network

  # ===============================
  # = Redis Service
  # ===============================
  redis:
    image: redis:7-alpine
    container_name: springapp-redis
    restart: unless-stopped
    ports:
      - "${REDIS_PORT:-6379}:6379"
    volumes:
      - redis_data:/data
    # An empty --requirepass makes redis-server refuse to start, so the
    # password is mandatory: Compose aborts with this message if .env lacks it.
    environment:
      REDIS_PASSWORD: ${REDIS_PASSWORD:?REDIS_PASSWORD must be set in .env}
    command: ["sh", "-c", "exec redis-server --appendonly yes --requirepass \"$$REDIS_PASSWORD\""]
    healthcheck:
      # A bare `redis-cli ping` exits 0 even on a NOAUTH error, so it would
      # report healthy with a wrong password. Authenticate and require PONG.
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_PASSWORD\" --no-auth-warning ping | grep -q PONG"]
      interval: 10s
      timeout: 3s
      retries: 5
    networks:
      - springapp-network

  # ===============================
  # = Spring Boot Application
  # ===============================
  springapp:
    image: springapp:latest
    container_name: springapp-api
    build:
      context: .
      dockerfile: ./Dockerfile
    ports:
      - "${SERVER_PORT:-8080}:8080"
    # Everything the app reads comes from .env, so this file never has to repeat
    # (and never has to be kept in sync with) the full variable list.
    env_file:
      - path: .env
        required: false
    environment:
      # Explicit profile. A SPRING_PROFILES_ACTIVE=dev in .env (handy for
      # `mvn spring-boot:run`) must not silently turn this stack into dev;
      # use docker-compose.dev.yml for that.
      - SPRING_PROFILES_ACTIVE=prod
      # Values that MUST differ inside the Compose network, or must equal what
      # the mysql/redis services above were started with.
      - DB_HOST=mysql
      - DB_PORT=3306
      - DB_NAME=${DB_NAME:-db_test_spring}
      - DB_USERNAME=${DB_USERNAME:-springapp}
      - DB_PASSWORD=${DB_PASSWORD:-springapp}
      - REDIS_HOST=redis
      - REDIS_PORT=6379
      - REDIS_PASSWORD=${REDIS_PASSWORD:-}
      # The redis service above speaks plain TCP.
      - REDIS_SSL_ENABLED=false
      - UPLOAD_DIR=/app/uploads
    volumes:
      # Named volumes: they inherit the image's ownership (UID 10001), so the
      # non-root app user can write to them. A host bind mount would be root-owned.
      - uploads_data:/app/uploads
      - logs_data:/app/logs
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    restart: unless-stopped
    healthcheck:
      # BusyBox wget ships with the Alpine JRE image; curl does not.
      test: ["CMD", "wget", "-q", "-T", "5", "-O", "/dev/null", "http://127.0.0.1:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    networks:
      - springapp-network

# ===============================
# = Networks
# ===============================
networks:
  springapp-network:
    driver: bridge

# ===============================
# = Volumes
# ===============================
volumes:
  mysql_data:
    driver: local
  redis_data:
    driver: local
  uploads_data:
    name: springapp_uploads_data
  logs_data:
    name: springapp_logs_data
```

- [ ] **Step 4: Create the dev overlay `docker-compose.dev.yml`**

```yaml
# =============================================================================
#  Local development overlay. Runs the app with the dev profile:
#
#    docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
#
#  Never use this on a shared or internet-facing host: the dev profile falls
#  back to a committed JWT secret and exposes Swagger UI.
# =============================================================================

services:
  springapp:
    environment:
      - SPRING_PROFILES_ACTIVE=dev
```

- [ ] **Step 5: Rewrite `docker-compose.debug.yml` as an overlay**

Replace the whole file with:

```yaml
# =============================================================================
#  Remote-debug overlay (JDWP on localhost:5005), on top of base + dev:
#
#    docker compose -f docker-compose.yml -f docker-compose.dev.yml \
#                   -f docker-compose.debug.yml up --build
# =============================================================================

services:
  springapp:
    environment:
      # Read by the JVM itself, in addition to the Dockerfile ENTRYPOINT flags.
      - JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005,quiet=y
    ports:
      # Loopback only: an open JDWP port is remote code execution.
      - "127.0.0.1:5005:5005"
```

- [ ] **Step 6: Update `docker-compose.prod.yml`**

(a) In the `springapp` service, replace the healthcheck test line
```yaml
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
```
with
```yaml
      # BusyBox wget ships with the Alpine JRE image; curl does not.
      test: ["CMD", "wget", "-q", "-T", "5", "-O", "/dev/null", "http://127.0.0.1:8080/actuator/health"]
```

(b) In the `springapp` service, replace
```yaml
    volumes:
      - ./uploads:/app/uploads
      - ./logs:/var/log/springapp
```
with
```yaml
    volumes:
      # Named volumes inherit the image's ownership (UID 10001); host bind
      # mounts would be root-owned and unwritable for the non-root app user.
      - uploads_prod_data:/app/uploads
      - logs_prod_data:/var/log/springapp
```

(c) In the top-level `volumes:` block, after `redis_prod_data:` / `driver: local`, add:
```yaml
  uploads_prod_data:
    name: springapp_uploads_prod_data
  logs_prod_data:
    name: springapp_logs_prod_data
```

(d) Run `grep -n "REDIS_SSL_ENABLED" docker-compose.prod.yml`. If P1's cross-edit is present, leave it. If there is no output, add `      - REDIS_SSL_ENABLED=false` after `      - REDIS_PORT=6379` in the `springapp` environment list (the prod Redis service has no TLS, and the prod profile defaults SSL to true).

- [ ] **Step 7: Verify the Compose files**

Run:
```bash
grep -n "curl" Dockerfile docker-compose*.yml
docker compose -f docker-compose.yml config --quiet && echo base-ok
docker compose -f docker-compose.yml config | grep "SPRING_PROFILES_ACTIVE"
docker compose -f docker-compose.yml -f docker-compose.dev.yml config | grep "SPRING_PROFILES_ACTIVE"
docker compose -f docker-compose.yml -f docker-compose.dev.yml -f docker-compose.debug.yml config | grep -E "JAVA_TOOL_OPTIONS|5005"
```
Expected: the first `grep` prints nothing; `base-ok`; `SPRING_PROFILES_ACTIVE: prod`; `SPRING_PROFILES_ACTIVE: dev`; the JDWP `JAVA_TOOL_OPTIONS` line plus `host_ip: 127.0.0.1` / `published: "5005"` / `target: 5005` entries.
For `docker-compose.prod.yml` (its `env_file: .env` is mandatory): if `.env` exists, run `docker compose -f docker-compose.prod.yml config --quiet && echo prod-ok` → `prod-ok`. If not, `cp .env.example .env`, run it, then `rm .env`.
No Docker CLI: `grep -n "wget\|SPRING_PROFILES_ACTIVE\|5005" docker-compose*.yml` and check the lines by eye against Steps 3–6.

- [ ] **Step 8: Verify the image**

Run:
```bash
docker build -t springapp:p6 .
docker run --rm --entrypoint id springapp:p6
docker run --rm --entrypoint sh springapp:p6 -c 'command -v javac || echo no-javac; command -v wget'
docker run --rm -m 1g --entrypoint java springapp:p6 -XX:MaxRAMPercentage=75.0 -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E ' MaxHeapSize '
```
Expected: build ends `naming to docker.io/library/springapp:p6`; `uid=10001(app) gid=10001(app) groups=10001(app)`; `no-javac` then `/usr/bin/wget`; `MaxHeapSize` ≈ `805306368` (768 MiB = 75% of 1 GiB).

- [ ] **Step 9: Verify the running dev stack (healthcheck + writable volumes)**

Run (the shell values reach both the redis service and the app via interpolation):
```bash
REDIS_PASSWORD=devredispass docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
sleep 90
docker inspect --format '{{.State.Health.Status}}' springapp-api
docker exec springapp-api sh -c 'id -u; touch /app/uploads/.w /app/logs/.w && rm /app/uploads/.w /app/logs/.w && echo writable'
REDIS_PASSWORD=devredispass docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```
Expected: `healthy`; `10001`; `writable`. If `unhealthy`, run `docker exec springapp-api wget -qO- http://127.0.0.1:8080/actuator/health` to see which component is DOWN. Fix only causes in files P6 owns; otherwise report.
No Docker: skip, and record "image/compose runtime not verified: no Docker" in the commit body.

- [ ] **Step 10: Commit**

```bash
git add Dockerfile docker-compose.yml docker-compose.dev.yml docker-compose.debug.yml docker-compose.prod.yml
git commit -m "$(cat <<'EOF'
ops: JRE non-root image, wget healthchecks, explicit compose profiles

Runtime on eclipse-temurin:21-jre-alpine as UID 10001 with
MaxRAMPercentage=75 and ExitOnOutOfMemoryError. Healthchecks use BusyBox
wget (no curl in the image). The base compose runs prod; dev and debug are
overlays. Uploads/logs move to named volumes the non-root user can write.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `.env.example` completeness check

Config/script. Verified by the script's empty output.

**Files:**
- Create: `scripts/check-env-example.sh`
- Modify: `.gitattributes`
- Modify: `.env.example`

**Interfaces:**
- Consumes: final `application*.yml` (Tasks 3–4) and compose files (Task 6).
- Produces: `scripts/check-env-example.sh` (exit 0 + no output when in sync). Task 8 documents it.

- [ ] **Step 1: Create the script**

Create `scripts/check-env-example.sh`:

```bash
#!/usr/bin/env bash
# Verifies that .env.example lists exactly the ${VAR} names used by the
# committed Spring config (src/main/resources/application*.yml) and the
# Compose files. Commented-out entries (`# VAR=`) count as listed.
# Prints nothing and exits 0 when both sides agree.
set -euo pipefail
export LC_ALL=C   # identical collation for sort and comm
cd "$(dirname "$0")/.."

used=$(grep -ohE '\$\{[A-Z][A-Z0-9_]*' src/main/resources/application*.yml docker-compose*.yml \
  | cut -c3- | sort -u)
declared=$(grep -oE '^[[:space:]]*#?[[:space:]]*[A-Z][A-Z0-9_]*=' .env.example \
  | sed -E 's/^[[:space:]]*#?[[:space:]]*//; s/=$//' | sort -u)

missing=$(comm -23 <(printf '%s\n' "$used") <(printf '%s\n' "$declared"))
unused=$(comm -13 <(printf '%s\n' "$used") <(printf '%s\n' "$declared"))

status=0
if [ -n "$missing" ]; then
  echo "Used but missing from .env.example:"
  printf '%s\n' "$missing" | sed 's/^/  /'
  status=1
fi
if [ -n "$unused" ]; then
  echo "Listed in .env.example but used nowhere:"
  printf '%s\n' "$unused" | sed 's/^/  /'
  status=1
fi
exit "$status"
```

Then:
```bash
chmod +x scripts/check-env-example.sh
```

Append to `.gitattributes`:
```
*.sh text eol=lf
```

- [ ] **Step 2: Run it**

Run: `scripts/check-env-example.sh; echo "exit=$?"`
Expected now: possibly non-empty. Typical findings after P1–P6: variables P2/P4/P5 introduced but forgot (`ASYNC_EMAIL_*`, SMTP timeouts, upload allowlist), `MYSQL_ROOT_PASSWORD` (compose-only), and stale entries for keys deleted by P2/P4/Task 3 (`MAIL_MAX_CONCURRENT_SENDS` if the semaphore went, `FRONTEND_URL` if Task 3 Step 13 removed it).

- [ ] **Step 3: Fix `.env.example`**

- For each **missing** name `VAR`: run `grep -rnE '\$\{VAR[:}]' src/main/resources docker-compose*.yml` to find where it is used. Add it to `.env.example` in the section for that area (same grouping as its neighbours), as two lines:
  ```properties
  # <yml key path that uses it, e.g. application.async.email.core-size>
  VAR=<the default after ':' in ${VAR:default}, or empty if there is none>
  ```
  For a compose-only variable (`MYSQL_ROOT_PASSWORD`), the comment names the compose service instead (`# docker-compose: mysql root password (Compose only)`).
- For each **unused** name: delete its line (and its comment lines) from `.env.example`.

- [ ] **Step 4: Re-run until clean**

Run: `scripts/check-env-example.sh; echo "exit=$?"`
Expected: exactly one line of output, `exit=0`.

- [ ] **Step 5: Commit**

```bash
git add scripts/check-env-example.sh .gitattributes .env.example
git commit -m "$(cat <<'EOF'
chore(config): script and enforce .env.example completeness

scripts/check-env-example.sh diffs ${VAR} names in application*.yml and
docker-compose*.yml against .env.example; .env.example updated until it
reports nothing.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Docs that match reality (CLAUDE.md, README, test-yml claim)

Pure docs. Verified by Step 1's fact-check (run first: its output decides some sentences) and Step 5's grep for false claims.

**Files:**
- Rewrite: `CLAUDE.md`
- Rewrite: `README.md`
- Modify: `src/test/resources/application-test.yml` (header comment only)

**Interfaces:**
- Consumes: the final state of everything above and of P1–P5.
- Produces: nothing code-facing.

- [ ] **Step 1: Fact-check the code the docs describe**

Run each command and keep the output next to you. The text in Steps 2–3 is written for the expected result. Where the output differs, change the matching sentence to say what the code does. Never change code in this task.

```bash
# F1 virtual threads on
grep -n -A1 "virtual:" src/main/resources/application.yml
# F2 email executor is a bounded platform pool + its keys
grep -n "ThreadPoolTaskExecutor\|emailExecutor\|RejectedExecutionHandler\|setRejectedExecutionHandler\|setAwaitTermination" src/main/java/com/spring/app/configs/AsyncConfig.java
sed -n '/^  async:/,/^  [a-z-]*:$/p' src/main/resources/application.yml
# F3 SMTP timeouts exist
grep -n "timeout" src/main/java/com/spring/app/configs/MailConfig.java
# F4 custom meters
grep -rhoE '(Counter|Timer)\.builder\("[^"]+"' src/main/java | sed -E 's/.*\("//; s/"$//' | sort -u
# F5 prometheus is ADMIN-only; method security on
grep -rn "prometheus" src/main/java
grep -rn "EnableMethodSecurity\|PreAuthorize" src/main/java | head
# F6 rate-limit buckets and path patterns
grep -n "RATELIMIT" src/main/java/com/spring/app/constants/WhitelistUrlConstant.java
grep -n "ERateLimitEndpoint\.\(AUTH\|UPLOAD\|API\|GLOBAL\)" src/main/java/com/spring/app/filter/RateLimitFilter.java
sed -n '/^  rate-limit:/,/^[a-z]/p' src/main/resources/application.yml
# F7 failed-login throttling key
grep -rn "checkRateLimitAndThrow\|login" src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java | head -20
# F8 email-verification skip property
grep -rn "skip-email-verification" src/main/resources src/main/java
# F9 auditor values
grep -n "SYSTEM\|getId" src/main/java/com/spring/app/configs/ApplicationAuditAware.java
# F10 endpoints
grep -rnE "@(Get|Post|Put|Delete)Mapping|@RequestMapping" src/main/java/com/spring/app/modules
# F11 strict binding: every properties class except ApplicationProperties
grep -L "ignoreUnknownFields = false" src/main/java/com/spring/app/configs/properties/*.java
# F12 cache names / what is cached
grep -rn "@Cacheable\|@CachePut\|@CacheEvict" src/main/java
# F13 upload design (per-user folder, allowlist, dedup registry kept or removed)
ls src/main/java/com/spring/app/modules/upload/services/ src/main/java/com/spring/app/modules/upload/services/impl/
grep -n "private\|@NotEmpty\|@NotNull" src/main/java/com/spring/app/configs/properties/FileStorageProperties.java
# F14 roles
grep -n "^  [A-Z_]*(" src/main/java/com/spring/app/enums/ERole.java
# F15 OAuth2 success redirect target
grep -rn "frontend\|sendRedirect\|#access" src/main/java/com/spring/app/handlers/OAuth2AuthenticationSuccessHandler.java
# F16 does application-test.yml override rate-limit?
grep -n "rate-limit" src/test/resources/application-test.yml
# F17 Boot version
grep -n -A1 "<artifactId>spring-boot-starter-parent</artifactId>" pom.xml
# F18 password max length (72 bytes) and JWT secret validation at startup
grep -rn "72" src/main/java/com/spring/app/common/validation src/main/java/com/spring/app/modules/auth/dto src/main/java/com/spring/app/modules/user/dto
grep -n "256\|Base64\|BASE64\|@PostConstruct\|decode" src/main/java/com/spring/app/configs/properties/JwtProperties.java src/main/java/com/spring/app/shared/services/JwtService.java
# F19 exception handler base class; CORS credentials
grep -n "class GlobalExceptionHandler" src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java
grep -rn "allowCredentials\|setAllowCredentials" src/main/java
# F20 upload temp dir sweep and file permissions
grep -rn "tmp\|temp\|PosixFilePermission" src/main/java/com/spring/app/modules/upload
```

Expected (what Steps 2–3 assume):
- F1 `enabled: true`.
- F2 `emailExecutor` builds a `ThreadPoolTaskExecutor` with a rejection handler. The yml has `async.email.{core-size,max-size,queue-capacity}`.
- F3 three timeout settings.
- F4 a list of `auth.*` meter names. Copy the printed names, one bullet each, into the "Custom metrics" bullets of both docs (replacing the example list there).
- F5 `prometheus` mapped with `hasRole("ADMIN")` (or `hasRole('ADMIN')`) in `SecurityConfig`.
- F6 AUTH = `/api/v1/auth/**`, UPLOAD = `/api/v1/files/**`, API = a non-empty pattern list covering the remaining `/api/**`; each request counted in exactly one bucket; quotas 100/1000/10000, 10/100/1000, 5/50/500, 60/600/6000 per minute/hour/day; no `burst-capacity`.
- F7 failed-login throttling keyed by e-mail + client IP.
- F8 the property exists, default `false`.
- F9 `"SYSTEM"` / user id.
- F10 endpoints matching the README table in Step 3. Add or remove table rows so the table equals this output exactly.
- F11 prints only `ApplicationProperties.java`.
- F12 only DTO caches (no `User`/`RefreshToken` entity caching).
- F13 per-user folder; allowlist field present; the registry kept as per-user/logging-only, or removed.
- F14 `USER`, `ADMIN` (plus `MANAGER` only if Task 3 kept it).
- F15 redirect to the frontend URL with tokens in the fragment.
- F16 no output, so the claim must be reworded (Step 4).
- F17 the Task 4 version.
- F18 a 72-byte password limit on register/reset DTOs; JWT secret Base64-decoded once and checked for ≥ 256 bits at startup.
- F19 `extends ResponseEntityExceptionHandler`; `allowCredentials(false)` or no `allowCredentials` call at all.
- F20 a dedicated temp subdirectory swept at startup, and explicit POSIX permissions on stored files.

- [ ] **Step 2: Rewrite `CLAUDE.md`**

Replace the whole file with the text below. Apply Step 1's adjustments: F4 metric list, F14 roles, F13 upload sentence, and any other sentence a fact-check contradicted.

````markdown
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

### Essential Commands
- **Build**: `mvn clean install` - Clean and build the entire project
- **Run**: `mvn spring-boot:run` - Start the application on port 8080 (needs MySQL and Redis running)
- **Test**: `mvn test` - Unit and slice tests; hermetic (H2, no Redis/SMTP/Docker needed)
- **Integration tests**: `mvn -Pintegration verify` - `*IT` classes via failsafe against Testcontainers MySQL 8 + Redis 7 (Docker required). Never run by `mvn test`.
- **Package**: `mvn clean package` - Create deployable JAR file
- **Env catalogue check**: `scripts/check-env-example.sh` - must print nothing; run it after adding or removing any `${VAR}`

### Docker
- `docker compose up --build` - full stack with the **prod** profile (needs a complete `.env`)
- `docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build` - same stack, **dev** profile
- add `-f docker-compose.debug.yml` - JDWP on `127.0.0.1:5005`
- `docker compose -f docker-compose.prod.yml up -d` - production stack (`.env` mandatory, ports bound to 127.0.0.1)
- Image: `eclipse-temurin:21-jre-alpine`, runs as non-root UID 10001, `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError`. Uploads and logs live in named volumes (`springapp_uploads_*`, `springapp_logs_*`). Healthchecks use BusyBox `wget` (the image has no `curl`).

### Development Environment
- **Java Version**: 21 (required)
- **Maven**: Used for dependency management and build
- **Profile**: `spring.profiles.active` defaults to `dev` (`SPRING_PROFILES_ACTIVE`) when running from source; the Docker image and the base compose file default to `prod`

### Database Setup
1. Create MySQL database: `db_test_spring`
2. Copy `.env.example` to `.env` and set `DB_*` there (never in the YAML files)
3. Flyway owns the schema (`FLYWAY_ENABLED=true` by default); migrations in `src/main/resources/db/migrations`, never edit an applied one
4. Hibernate only validates against it (`JPA_DDL_AUTO=validate`). For
   throw-away local work, set `JPA_DDL_AUTO=update` + `FLYWAY_ENABLED=false`

### Configuration Files
All YAML below is committed and contains **no secrets** — only structure and
`${ENV_VAR:default}` placeholders.

- Base (all profiles): `src/main/resources/application.yml`
- Profile deltas: `application-dev.yml`, `application-prod.yml` — these declare
  **only the differences**; Spring merges them onto the base. Never copy a
  block between them, and never set `spring.profiles.active` in a
  profile-specific file (Spring Boot rejects it).
- Variable catalogue: `.env.example` (committed) → `.env` (git-ignored, holds
  the real values, loaded via `spring.config.import`). `scripts/check-env-example.sh`
  keeps the catalogue and the `${VAR}` names in `application*.yml` /
  `docker-compose*.yml` in sync.
- Tests: `src/test/resources/application-test.yml` (H2, Flyway off, Redis
  fail-fast off) so `mvn test` needs nothing running. Every validation-constrained
  property whose value in `application.yml` comes from a `${ENV_VAR}`
  placeholder (JWT expirations, cache TTLs, async pool sizes, mail, upload
  settings...) is overridden here with a literal. This blocks two separate
  leaks: a developer's `.env` **file** cannot reach these keys, and neither
  can a plain OS environment variable of the same name (e.g. a stray `export
  ASYNC_TASK_CORE_SIZE=0` in a dev shell or CI runner). Spring's placeholder
  resolution checks OS env vars regardless of whether `.env` exists, so only
  a literal override actually blocks it. Constrained properties that are
  already literals in `application.yml` (the `application.rate-limit.*`
  quotas) need no override. Properties without a validation constraint are
  not overridden and can still pick up an ambient env var; that's fine since
  a bad value there can't fail the build.

Namespace rule: `spring.*` is for framework properties only; everything this
application owns lives under `application.*`, bound by the `@ConfigurationProperties`
classes in `configs/properties/` and validated at startup via `@Validated`.
Adding a setting means adding a field there (plus a literal test override if
it is constrained and placeholder-backed, plus a `.env.example` entry).
Strict binding (`ignoreUnknownFields = false`) is enabled on every leaf class
in `configs/properties/` (all of them except `ApplicationProperties`), so a
typo'd key under one of their prefixes fails startup instead of binding to
nothing. `ApplicationProperties` itself (the bare `application.*` prefix,
covering `cors`, `redis`, `frontend-url`, `trusted-proxies`,
`oauth2-allowed-email-domains`) is left on Spring Boot's default relaxed
binding, because it is the shared parent prefix for all the others; turning
on strict binding there would make every child class's keys look "unknown"
to it and break startup entirely. A typo under those specific keys is still
silently ignored.

## Architecture Overview

### Core Architecture Pattern
The application follows a **layered architecture**:

**Controller → Service → Repository → Database**

Services currently return `ResponseEntity` (built with `ResponseBuilder`);
moving them to DTO returns is a planned follow-up refactor.

### Module Structure
Feature modules under `src/main/java/com/spring/app/modules/`:
- **auth**: registration, OTP activation, login, refresh, logout, password reset
- **user**: the caller's own profile (`/api/v1/users/me`)
- **upload**: per-user file storage

### Threads and Concurrency
- HTTP requests and `@Scheduled` jobs run on **virtual threads**
  (`spring.threads.virtual.enabled=true`). Tomcat's thread pool no longer
  caps concurrency; the **HikariCP pool** (`DB_POOL_MAX_SIZE`, default 20) is
  the real limit on concurrent DB work, so never hold a transaction open
  across Redis calls, BCrypt or JWT signing.
- **E-mail** runs on the `emailExecutor`: a **bounded platform-thread**
  `ThreadPoolTaskExecutor` (`application.async.email.*`, env `ASYNC_EMAIL_*`).
  It does not use virtual threads, because jakarta.mail's SMTP transport
  synchronizes around socket I/O and would pin carrier threads shared with
  Tomcat. When the pool and queue are full, a new e-mail is logged and dropped
  rather than blocking the caller. SMTP connect/read/write timeouts are
  configured under `application.mail.*`.
- General `@Async` work uses `taskExecutor` (`application.async.task.*`).
- `TokenCleanupService` purges expired/revoked refresh tokens daily at 02:00;
  a Redis lock (`lock:token-cleanup`, `SET NX EX` 10 min) makes exactly one
  instance run it.

### Security Architecture
- **JWT** (jjwt, HS256). Access and refresh tokens carry a `token_type`
  claim: only access tokens are accepted as `Authorization: Bearer`, and only
  refresh tokens at `/api/v1/auth/refresh-token`. Refresh rotates the token
  (the old one is revoked). Logout blacklists the access token in Redis until
  it expires and revokes the user's refresh tokens. The JWT filter loads the
  user from the DB on every request and rejects non-ACTIVE users.
- **Public endpoints** are listed explicitly in `WhitelistUrlConstant`, the
  single source used by both `SecurityConfig` and `JwtAuthenticatorFilter`.
  Everything else needs a valid access token.
- **Roles**: `USER` (default) and `ADMIN`, with method security enabled.
  `ADMIN` is required for `/actuator/prometheus`. Every other protected
  endpoint only requires an authenticated, ACTIVE user.
- **E-mail verification**: new accounts are PENDING until activated with a
  6-digit OTP (SecureRandom, 3-minute TTL in Redis). Skipping verification is
  an explicit opt-in (`application.auth.skip-email-verification`, default
  `false`), never implied by the profile.
- **OAuth2 (Google)**: requires a verified Google e-mail, optionally
  restricted by `application.oauth2-allowed-email-domains`. On success it
  redirects to `application.frontend-url` with the tokens in the URL fragment.
  OAuth2 authorization state lives in the servlet `HttpSession` (in memory,
  not Redis), so **multi-instance deployments need sticky sessions** for the
  Google login flow.
- **Rate limiting**: Redis fixed-window counters (atomic increment with
  expiry), keyed by client IP. `X-Forwarded-For` / `X-Real-IP` are honoured
  only from `application.trusted-proxies`. Each request counts against
  exactly one bucket, chosen by path (minute / hour / day):
  - AUTH `/api/v1/auth/**`: 10 / 100 / 1000
  - UPLOAD `/api/v1/files/**`: 5 / 50 / 500
  - API every other `/api/**`: 60 / 600 / 6000
  - GLOBAL everything else (actuator, Swagger, OAuth2 callbacks): 100 / 1000 / 10000

  Failed logins are additionally throttled per e-mail + IP (successes are not
  counted). Exceeding a limit returns 429.
- **CORS**: one `CorsConfigurationSource` (`application.cors.*`), no credentials.
- **CSRF**: disabled. The API is stateless and authenticates with bearer
  tokens, not cookies.

### Caching and Redis
- Spring Cache on Redis caches **immutable DTOs only** (e.g. the user profile
  `UserResponseDto`), never JPA entities. TTLs are set under `application.cache.*`.
- Redis also holds OTPs, the access-token blacklist, rate-limit counters and
  the token-cleanup lock. There are **no HTTP sessions in Redis**: the API is
  stateless (see the OAuth2 note above).

### Monitoring and Observability
- Actuator exposes `health`, `info`, `prometheus` only. `/actuator/health` is
  public (detail level per `management.endpoint.health.show-details`);
  `/actuator/prometheus` requires ADMIN.
- Custom metrics (`MonitoringService`):
  - `auth.login.success`
  - `auth.login.failed`
  - `auth.registration.attempts`
  - `auth.login.duration`
  - `auth.registration.duration`
- **Logging** is plain text via `logback-spring.xml`. Console (coloured only
  on a terminal, never under `prod`), plus rolling files
  `${LOG_PATH}/application.log` and `error.log` (daily and at 100MB, 30 days,
  3GB / 1GB caps) in every profile except `test`, which logs to the console only.

### Data Layer
- JPA/Hibernate on MySQL 8; Flyway migrations; `ddl-auto=validate`.
- HikariCP (max 20 by default). Hibernate JDBC batching is configured
  (`batch_size` 50, ordered inserts/updates); there are no bulk-processing jobs.
- Auditing: `BaseAuditingEntity` fills `created_at/updated_at` and
  `created_by/updated_by` via `ApplicationAuditAware`: the authenticated
  user's id, or `SYSTEM` when there is none.

### File Upload System
- Files are stored per user under `<upload-dir>/<userId>/` (`UPLOAD_DIR`,
  default `uploads`). List/download/delete only see the caller's own folder,
  and an upload is always stored under the requested name.
- Temporary files go to a dedicated temp subdirectory (swept at startup),
  never into the servable namespace. Stored files get explicit permissions.
- Allowed extensions/content types come from `application.file-storage`
  (`FileStorageProperties`). Max size 10MB (`MAX_FILE_SIZE`).
- Upload rate limit is the UPLOAD bucket, **per client IP** (not per user).

### Error Handling
- `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` and
  answers in the `ResponseBuilder` / `BaseResponse` shape.
- 4xx are logged at WARN/INFO. 5xx return a fixed generic message; exception
  text (SQL, stack traces) never reaches the client.

### E-mail
- The OTP mail is built from the classpath template
  `templates/otp-email.html` (loaded once), with placeholders replaced by
  HTML-escaped values (`HtmlTemplateOTPBuilder`).

### Key Design Patterns
- **Repository Pattern**: Data access abstraction
- **Service Layer Pattern**: Business logic encapsulation
- **DTO Pattern**: Data transfer with MapStruct mapping
- **Builder Pattern**: Response construction via `ResponseBuilder`

## Dependencies and Technology Stack

### Core Spring Dependencies
- Spring Boot 3.5.x (exact patch in `pom.xml`), Java 21
- Spring Security with OAuth2 client
- Spring Data JPA with MySQL
- Spring Cache + Spring Data Redis (Lettuce, Boot-managed)
- Spring Boot Actuator + Micrometer Prometheus registry

### External Libraries
- **JWT**: io.jsonwebtoken (jjwt) 0.12.6
- **Mapping**: MapStruct 1.6.3
- **Database Migration**: Flyway (MySQL support)
- **Documentation**: SpringDoc OpenAPI 2.8.8 (`/swagger-ui.html`, `/api-docs`; off in prod)
- **Email**: Spring Mail (jakarta.mail)
- **Testing**: JUnit 5, Mockito (loaded as `-javaagent`), H2 for `mvn test`, Testcontainers for `-Pintegration`

### Development Tools
- **Lombok**: Reduces boilerplate code
- **Spring DevTools**: Hot reload support
- **Maven**: Build and dependency management

## Security Best Practices
- BCrypt password hashing. Passwords are limited to 72 bytes (BCrypt's input limit).
- `JWT_SECRET_KEY` must be Base64 and ≥ 256 bits; validated at startup. The
  prod profile has no fallback.
- Login, resend-OTP, activation and reset responses are uniform, so they do
  not reveal whether an account exists or is active.
- Input validation with Bean Validation and custom validators
  (`@StrongPassword`, `@ValidEmail`).
````

- [ ] **Step 3: Rewrite `README.md`**

Replace the whole file with the text below. Apply Step 1's adjustments: F4 metric list, F10 endpoint rows, F14 roles, F17 version badge text.

````markdown
# SpringApp - Spring Boot Boilerplate

A Spring Boot 3.5 / Java 21 REST API boilerplate with JWT + Google OAuth2
authentication, e-mail OTP verification, Redis-backed caching and rate
limiting, per-user file uploads, Flyway-managed MySQL, and Prometheus metrics.

[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=spring-boot)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=java)](https://openjdk.org/)

## Contents

- [Features](#features)
- [Technology stack](#technology-stack)
- [Quick start](#quick-start)
- [Running with Docker](#running-with-docker)
- [API](#api)
- [Security](#security)
- [Runtime model](#runtime-model)
- [Monitoring and logging](#monitoring-and-logging)
- [Testing](#testing)
- [Configuration](#configuration)
- [Project structure](#project-structure)

## Features

- **Authentication**: JWT access + refresh tokens (typed, rotated on refresh,
  revoked on logout; logged-out access tokens are blacklisted in Redis).
- **E-mail verification**: 6-digit OTP sent by e-mail; accounts stay PENDING until activated.
- **Google sign-in** (OAuth2), verified e-mails only, optional domain allow-list.
- **Roles**: `USER` and `ADMIN` (method security enabled; Prometheus scrape is ADMIN-only).
- **Rate limiting**: Redis fixed-window counters per client IP, four buckets (auth, upload, API, global).
- **Caching**: Spring Cache on Redis for immutable DTOs.
- **File uploads**: per-user folders, type allow-list, 10MB limit.
- **Persistence**: MySQL 8, Flyway migrations, Hibernate in `validate` mode, JPA auditing.
- **Observability**: Actuator health, Prometheus metrics, custom auth counters and timers.

## Technology stack

| Area | Technology |
|---|---|
| Framework | Spring Boot 3.5.x (see `pom.xml`), Java 21, Maven |
| Web / security | Spring MVC, Spring Security, OAuth2 client, jjwt 0.12.6 |
| Persistence | Spring Data JPA, Hibernate, MySQL 8, Flyway, HikariCP |
| Cache / Redis | Spring Cache, Spring Data Redis (Lettuce) |
| Mapping | MapStruct 1.6.3, Lombok |
| Mail | Spring Mail (jakarta.mail), classpath HTML template |
| API docs | SpringDoc OpenAPI 2.8.8 |
| Monitoring | Spring Boot Actuator, Micrometer + Prometheus |
| Tests | JUnit 5, Mockito, H2, Testcontainers |

## Quick start

### Prerequisites

- Java 21, Maven 3.9+
- MySQL 8.0+ and Redis 7+ (or use Docker, below)

### 1. Clone

```bash
git clone https://github.com/Nhatnguyen150100/spring-boot-boilerplate.git
cd spring-boot-boilerplate
```

### 2. Database

```sql
CREATE DATABASE db_test_spring CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Flyway creates the schema on first start.

### 3. Configuration

The `application*.yml` files are committed and contain only structure and
`${ENV_VAR:default}` placeholders. Real values go in `.env` (git-ignored):

```bash
cp .env.example .env
```

Every variable has a working local default, so `.env` can stay almost empty
for a first run. Typical values:

```properties
DB_USERNAME=your_username
DB_PASSWORD=your_password

# Base64, >= 256-bit. Generate with: openssl rand -base64 48
JWT_SECRET_KEY=

MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-app-password
```

> Flyway owns the schema and Hibernate only validates against it. For
> throw-away local work, set `JPA_DDL_AUTO=update` and `FLYWAY_ENABLED=false`.

### 4. Build and run

```bash
mvn clean install
mvn spring-boot:run
```

### 5. Check

```bash
curl http://localhost:8080/actuator/health
# Swagger UI (dev only): http://localhost:8080/swagger-ui.html
```

## Running with Docker

| Command | What runs |
|---|---|
| `docker compose up --build` | MySQL + Redis + app with the **prod** profile (needs a complete `.env`) |
| `docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build` | Same stack, **dev** profile |
| add `-f docker-compose.debug.yml` | plus JDWP debugger on `127.0.0.1:5005` |
| `docker compose -f docker-compose.prod.yml up -d` | Production stack; `.env` mandatory, ports bound to 127.0.0.1 |

The image is `eclipse-temurin:21-jre-alpine`, runs as non-root UID 10001, and
sizes the heap to 75% of the container memory limit. Uploads and logs are
stored in named volumes (`springapp_uploads_data` / `springapp_logs_data`;
`springapp_uploads_prod_data` / `springapp_logs_prod_data` in prod).

Upgrading a deployment that used the old `./uploads` bind mount (also note
that files are now stored per user, under `<upload-dir>/<userId>/`):

```bash
docker run --rm -v "$PWD/uploads:/from:ro" -v springapp_uploads_prod_data:/to alpine \
  sh -c 'cp -a /from/. /to/ && chown -R 10001:10001 /to'
```

## API

All responses use one envelope:

```json
{
  "statusCode": 200,
  "message": "Success",
  "data": {},
  "timestamp": "2026-01-01T12:00:00Z"
}
```

### Authentication: `/api/v1/auth`

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Register; sends an OTP e-mail | public |
| `POST` | `/api/v1/auth/resend-otp` | Resend the activation OTP | public |
| `POST` | `/api/v1/auth/activate` | Activate the account with the OTP | public |
| `POST` | `/api/v1/auth/login` | Returns access + refresh token | public |
| `POST` | `/api/v1/auth/refresh-token` | Exchange a refresh token for a new pair | refresh token in body |
| `DELETE` | `/api/v1/auth/logout` | Blacklist the access token, revoke refresh tokens | Bearer access token |
| `POST` | `/api/v1/auth/forgot-password` | Send a password-reset OTP | public |
| `POST` | `/api/v1/auth/reset-password` | Reset the password with the OTP | public |
| `GET` | `/api/v1/auth/oauth2/authorization/google` | Start Google sign-in | public |

### User: `/api/v1/users`

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/api/v1/users/me` | Current user's profile | Bearer |
| `PUT` | `/api/v1/users/me/update` | Update the current user's profile | Bearer |

### Files: `/api/v1/files` (scoped to the caller's own folder)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/v1/files` | Upload (multipart `file`) | Bearer |
| `GET` | `/api/v1/files/download/{filename}` | Download | Bearer |
| `DELETE` | `/api/v1/files/delete/{filename}` | Delete | Bearer |

## Security

- **Tokens**: HS256 JWTs with a `token_type` claim. Only access tokens are
  accepted as `Authorization: Bearer`; only refresh tokens at `/refresh-token`.
  Defaults: access 24h (`JWT_EXPIRATION`), refresh 7 days (`JWT_REFRESH_EXPIRATION`).
- **Accounts**: only ACTIVE users can log in or use a token. Login, OTP and
  reset responses are uniform, so they do not reveal whether an e-mail is registered.
- **Passwords**: BCrypt. `@StrongPassword` policy on register/reset (8+
  chars, upper, lower, digit, special), max 72 bytes.
- **Roles**: `USER` (default), `ADMIN`. `/actuator/prometheus` requires `ADMIN`.
- **CSRF** is disabled: the API is stateless and uses bearer tokens, not
  cookies. **CORS** origins come from `CORS_ALLOWED_ORIGINS`.
- **Headers**: Spring Security defaults (`X-Content-Type-Options`,
  `X-Frame-Options: DENY`, cache control; HSTS on HTTPS requests).
- **Rate limits** (per client IP, per minute / hour / day; 429 when exceeded):

  | Bucket | Paths | Limits |
  |---|---|---|
  | AUTH | `/api/v1/auth/**` | 10 / 100 / 1000 |
  | UPLOAD | `/api/v1/files/**` | 5 / 50 / 500 |
  | API | other `/api/**` | 60 / 600 / 6000 |
  | GLOBAL | everything else | 100 / 1000 / 10000 |

  Failed logins are also throttled per e-mail + IP. Behind a reverse proxy,
  set `TRUSTED_PROXIES` so the real client IP is used.
- **Google sign-in** keeps its OAuth2 state in the in-memory HTTP session.
  With more than one instance, enable sticky sessions on the load balancer.

## Runtime model

- HTTP requests run on **virtual threads**. The database pool
  (`DB_POOL_MAX_SIZE`, default 20) is the effective concurrency limit for DB work.
- E-mail is sent on a **bounded platform-thread pool** (`ASYNC_EMAIL_*`) with
  SMTP timeouts. When it is saturated, new mails are logged and dropped
  instead of blocking requests.
- A nightly job (02:00) deletes expired/revoked refresh tokens. A Redis lock
  ensures only one instance runs it.

## Monitoring and logging

```bash
GET /actuator/health        # public
GET /actuator/info          # public
GET /actuator/prometheus    # ADMIN bearer token
```

Custom metrics:

- `auth.login.success`
- `auth.login.failed`
- `auth.registration.attempts`
- `auth.login.duration`
- `auth.registration.duration`

Logs are plain text: console, plus rolling `application.log` / `error.log`
under `LOG_PATH` (daily and at 100MB, 30 days, size-capped). Tests log to the
console only.

## Testing

```bash
mvn test                      # hermetic: H2, no MySQL/Redis/SMTP/Docker
mvn -Pintegration verify      # + Testcontainers MySQL 8 and Redis 7 (Docker required)
```

The integration suite checks Flyway + Hibernate `validate` on real MySQL, the
register → activate → login → refresh → logout round trip on real Redis, and
JPA auditing.

## Configuration

| File | Committed? | Contains |
|---|---|---|
| `src/main/resources/application.yml` | yes | Every setting, as structure + `${ENV_VAR:default}` |
| `src/main/resources/application-dev.yml` | yes | Only what dev changes (SQL logging, DevTools, debug levels) |
| `src/main/resources/application-prod.yml` | yes | Only what prod changes; most placeholders have no fallback |
| `.env.example` | yes | Catalogue of every variable |
| `.env` | **no** | Your real values, loaded via `spring.config.import` |

- `spring.*` holds framework properties. `application.*` holds everything the
  app owns, bound and validated by the classes in `configs/properties/`.
  Unknown keys under the strict prefixes fail startup.
- `scripts/check-env-example.sh` verifies that `.env.example` lists exactly
  the variables the YAML and Compose files use.

## Project structure

```
src/main/java/com/spring/app/
├── BaseSpringApplication.java
├── common/          # base entities, pagination, response envelope, validation
├── configs/         # Spring configuration + properties/ (typed settings)
├── constants/       # base paths, public-URL whitelist
├── enums/
├── exceptions/      # domain exceptions + GlobalExceptionHandler
├── filter/          # JWT and rate-limit filters
├── handlers/        # OAuth2 success/failure, access denied
├── modules/
│   ├── auth/        # controller, dto, entities, mapper, repositories, services
│   ├── user/
│   └── upload/
├── shared/          # cross-module services (JWT, Redis, mail, monitoring, ...)
├── templates/       # OTP e-mail builder
└── utils/
```
````

- [ ] **Step 4: Fix the "every constrained property" claim in `application-test.yml`**

If F16 printed nothing, replace in `src/test/resources/application-test.yml` the header lines
```yaml
#    2. Spring's placeholder resolution (${ASYNC_TASK_CORE_SIZE:5} etc. in
#       application.yml) also checks plain OS environment variables, which a
#       literal here shields against too. Any property bound to an
#       @jakarta.validation constraint MUST be overridden with a literal below
#       -- otherwise an exported shell variable of the same name (common in a
#       long-lived dev shell or a CI runner) can fail validation and break the
#       build for a reason that has nothing to do with the code under test.
```
with
```yaml
#    2. Spring's placeholder resolution (${ASYNC_TASK_CORE_SIZE:5} etc. in
#       application.yml) also checks plain OS environment variables, which a
#       literal here shields against too. Any validation-constrained property
#       whose application.yml value comes from a ${ENV_VAR} placeholder MUST be
#       overridden with a literal below -- otherwise an exported shell variable
#       of the same name (common in a long-lived dev shell or a CI runner) can
#       fail validation and break the build for a reason that has nothing to do
#       with the code under test. Constrained properties that are already
#       literals in application.yml (application.rate-limit.*) need no override.
```
If F16 printed `rate-limit` lines (a previous plan made the stronger claim true), leave the comment as it is, and in CLAUDE.md drop the sentence starting "Constrained properties that are already literals…".

- [ ] **Step 5: Verify no false claim survives**

Run:
```bash
grep -niE "bucket4j|resilience4j|circuit breaker|jsoup|template method|session storage|structured logging|redis cluster|grafana|aspect-oriented|event-driven|csrf protection|content security policy|refresh-token\.expiration|springapp_db|/api/v1/upload|/users/profile|rate limited per user|lettuce driver 6|3\.5\.0" CLAUDE.md README.md
mvn -q test 2>&1 | tail -3
```
Expected: the `grep` prints nothing; `mvn test` still passes (only a YAML comment changed). Then re-read the F4/F10/F14 outputs against the two docs one last time: metric names, endpoint rows and roles must match exactly.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md README.md src/test/resources/application-test.yml
git commit -m "$(cat <<'EOF'
docs: describe the real implementation after the review remediation

Fixed-window Redis rate limiting (API bucket applied, per IP), stateless
API with sticky sessions for OAuth2, typed JWTs, ADMIN-only Prometheus,
real metric names, plain-text logging, classpath e-mail template, virtual
threads with a bounded platform pool for e-mail, per-user uploads, Docker
and -Pintegration usage. Drops Bucket4j/Resilience4j/CSRF/session/JSoup claims.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review (performed by the plan author)

**Spec coverage (P6 bullets):**
- Dockerfile JRE / non-root / container JVM flags → Task 6 Steps 2, 8.
- Compose healthchecks `curl` → verified absent, `wget` used → Task 6 Steps 1, 3, 6, 7, 9.
- Compose default dev profile → base compose hard-sets `prod`, dev overlay file → Task 6 Steps 3–4.
- logback ANSI in prod, `scan`, `totalSizeCap` → Task 1.
- TokenCleanupService multi-instance → Redis SET NX EX lock → Task 2.
- pom: web-services, jsoup, commons-lang3, lettuce pin, Boot 3.5.x patch, Mockito javaagent, H2Dialect, compatibility-verifier → Task 4 (jsoup gated by Task 3 Step 9).
- Dead code: ApplicationConstants, ERole.MANAGER, RateLimitManagerService methods, JwtService extract*, repository finders, `sendContentEmail`, `AuthRequestDto`, `ValidationGroups`, `MessagingException` handler, duplicate `@EnableCaching`, `frontend-url` → Task 3 Steps 1–13 (plus the RedisService cascade in Step 6 and the MANAGER migration in Step 4).
- Opt-in Testcontainers ITs (Flyway + validate, auth round trip on real Redis, JPA auditing) → Task 5.
- Tests writing outside `target/`; `.gitignore` → Task 1.
- `.env.example` completeness script → Task 7.
- Docs rewrite, including P3 (sticky sessions, JWT filter DB hit, RBAC), P5 (per-IP upload limit) and the test-yml claim → Task 8.

**Placeholder scan:** The only free text the executor writes is the Task 3 commit body (an outcome list) and data copied verbatim from deterministic commands: Task 4 Step 1 version, Task 7 `.env.example` lines, Task 8 F4/F10 lists. Each has an exact command and format.

**Type consistency:** `TokenCleanupService.LOCK_KEY`/`LOCK_TTL` match the test's literals (`"lock:token-cleanup"`, `Duration.ofMinutes(10)`). `IntegrationTestBase` is the only base used by the three ITs. The Mockito agent property `${org.mockito:mockito-core:jar}` is defined in Task 4 and reused in Task 5.
