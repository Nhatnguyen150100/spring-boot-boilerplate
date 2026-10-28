# P1 persistence-cache-redis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop caching JPA entities and `ResponseEntity`, cache only an immutable `UserResponseDto` with a correctly configured JSON mapper, fix the JPA auditor's `ClassCastException`, make Redis honour SSL and timeouts, and make the rate-limit counter atomic on the right (String) template.

**Architecture:** `AuthCacheService` keeps its public method signatures but always reads through the repositories. Its "update/evict" methods now evict the one remaining cache, `userProfile` (`UserResponseDto` keyed by user id). `CacheConfig` declares that cache with a typed `Jackson2JsonRedisSerializer<UserResponseDto>` (JavaTimeModule, no default typing, unknown properties ignored), refuses undeclared cache names, and is transaction-aware. `UserService` returns DTOs and `UserController` wraps them with `ResponseBuilder`. `RedisConfig` drops its hand-built connection factory so Spring Boot's Lettuce auto-configuration applies `spring.data.redis.ssl.*`, `timeout` and `connect-timeout`. `RedisService` sends rate-limit traffic through Boot's `StringRedisTemplate`, with INCR+EXPIRE combined in one Lua script. `ApplicationAuditAware` resolves the auditor from the app `User`, an OAuth2 principal, or the authentication name, and never casts.

**Tech Stack:** Java 21, Spring Boot 3.5.0 (Spring Framework 6.2.7), Spring Data Redis 3.5.0 / Lettuce 6.7.1, Spring Data JPA + Hibernate, Jackson 2.19 + `jackson-datatype-jsr310`, MapStruct 1.6.3, Lombok, JUnit 5, Mockito 5, AssertJ, H2 (tests).

**Spec:** `docs/superpowers/specs/2026-10-28-review-remediation-design.md` (section "### P1 persistence-cache-redis"; Decisions, Global constraints, ownership table and allowed cross-edits are binding)

## Global Constraints

- Java 21, Spring Boot 3.5.x, Maven. `mvn test` must pass with nothing running (no MySQL, no Redis, no SMTP, no Docker).
- Config rules from CLAUDE.md are binding: framework keys under `spring.*`, app keys under `application.*` bound by `configs/properties/*` with `@Validated`; the six leaf properties classes keep `ignoreUnknownFields = false`; every validation-constrained property gets a **literal** override in `src/test/resources/application-test.yml`; every new env var is added to `.env.example`; profile files hold deltas only and never set `spring.profiles.active`.
- Flyway owns the schema (`src/main/resources/db/...`); any schema change is a new versioned migration, never an edit of an applied one.
- TDD: each fix lands with a test that fails before and passes after, unless the change is pure config/docs (then state how it was verified).
- Match surrounding code style: 2-space indent, Lombok, constructor injection, `ResponseBuilder` for HTTP responses.
- Commit per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- P1 owns: `CacheConfig`, `RedisConfig`, `ApplicationAuditAware`, `RedisService`, `RedisServiceInterface`, `AuthCacheService`, `UserService`, `UserServiceInterface`, `UserController`, `CacheProperties`, `common/entities/*`, `application.yml` (`spring.data.redis`, cache sections), `application-prod.yml` (redis section). Allowed cross-edit: `REDIS_SSL_ENABLED` in `docker-compose.prod.yml`. Any plan may append to `application-test.yml` for properties it adds, and add test classes in its own area.

## Execution notes (read before Task 1)

- Work from the repo root `/home/nhatnx/my-project/spring-boot-boilerplate`. P1 runs first, so all line numbers are at HEAD `92eac07`. A later task in this plan can shift lines an earlier task touched, so for YAML always match on the quoted text, not the line number.
- Where a step says "Replace the entire contents", overwrite the file. `RedisService.java` and `UserController.java` currently have CRLF line endings. Rewriting them with LF is acceptable (`.gitattributes` does not pin Java files).
- `.env.example` is plain LF. In some agent sandboxes it falls under a `.env*` read/edit deny rule. If your Read/Edit tool is denied on it, do NOT work around the rule. Stop and ask the user to apply the exact diff shown in the step.
- Removing the `tokens-ttl` / `default-ttl` lines from `application-test.yml` (Task 6) is a deletion, not an append. It is forced: `CacheProperties` uses strict binding, so the test context would fail on a key whose field no longer exists.
- Test commands use `mvn -q test -Dtest=...`. With `-q`, a passing run prints little or nothing and exits 0. A failing run prints `[ERROR]` lines and ends with `BUILD FAILURE`.

## File map

| File | Action | Responsibility after P1 |
|---|---|---|
| `src/main/java/com/spring/app/configs/ApplicationAuditAware.java` | Rewrite | Auditor = app user id / OAuth2 `id` attribute / auth name / `SYSTEM`. Never casts, never queries |
| `src/main/java/com/spring/app/configs/RedisConfig.java` | Rewrite | JSON `redisTemplate` and the fail-fast ping only. Connection factory comes from Boot |
| `src/main/resources/application.yml` | Modify `spring.data.redis` (HEAD 70-77), `application.cache` (HEAD 173-177) | Redis timeouts; one cache TTL |
| `src/main/resources/application-prod.yml` | Modify redis section (HEAD 29-35) | Comment the compose TLS override |
| `docker-compose.prod.yml` | Cross-edit (after HEAD line 103) | `REDIS_SSL_ENABLED=false` for the bundled plain-TCP Redis |
| `.env.example` | Modify (HEAD lines 46, 51, 53) | `REDIS_TIMEOUT`, `REDIS_CONNECT_TIMEOUT`; drop `CACHE_DEFAULT_TTL`, `CACHE_TOKENS_TTL` |
| `src/main/java/com/spring/app/shared/services/RedisService.java` | Rewrite | Rate-limit ops on `StringRedisTemplate`, atomic Lua window |
| `src/main/java/com/spring/app/shared/interfaces/RedisServiceInterface.java` | Rewrite (Javadoc only, signatures unchanged) | Contract docs |
| `src/main/java/com/spring/app/shared/services/AuthCacheService.java` | Rewrite (same public signatures) | Repository read-through, e-mail normalisation, `userProfile` eviction |
| `src/main/java/com/spring/app/modules/user/services/UserServiceInterface.java` | Rewrite | Returns `UserResponseDto` |
| `src/main/java/com/spring/app/modules/user/services/impl/UserService.java` | Rewrite | Spring `@Transactional`, `readOnly` read, DTO cache, evict on update |
| `src/main/java/com/spring/app/modules/user/controller/UserController.java` | Rewrite | Wraps DTOs with `ResponseBuilder` |
| `src/main/java/com/spring/app/configs/CacheConfig.java` | Rewrite | Typed DTO serializer, declared caches only, transaction-aware |
| `src/main/java/com/spring/app/configs/properties/CacheProperties.java` | Rewrite | Only `usersTtl` |
| `src/test/resources/application-test.yml` | Modify (HEAD 63-66) | Cache literal reduced to `users-ttl` |
| `src/test/java/com/spring/app/configs/ApplicationAuditAwareTest.java` | Create | Principal-type unit tests |
| `src/test/java/com/spring/app/configs/ApplicationAuditAwareJpaTest.java` | Create | `@DataJpaTest` auditing on insert/update |
| `src/test/java/com/spring/app/configs/RedisConfigTest.java` | Create | SSL/timeouts reach the Lettuce client |
| `src/test/java/com/spring/app/shared/services/RedisServiceTest.java` | Create | Lua script, String template, no split INCR/EXPIRE |
| `src/test/java/com/spring/app/shared/services/AuthCacheServiceTest.java` | Create | Read-through, normalisation, eviction |
| `src/test/java/com/spring/app/configs/CachedReturnTypesTest.java` | Create | No `@Cacheable`/`@CachePut` returning an `@Entity` or `ResponseEntity` |
| `src/test/java/com/spring/app/modules/user/services/impl/UserServiceTest.java` | Create | DTO caching + eviction through real Spring cache proxies |
| `src/test/java/com/spring/app/modules/user/controller/UserControllerTest.java` | Create | Response shape |
| `src/test/java/com/spring/app/configs/CacheConfigTest.java` | Create | Serialization round trip, TTL, declared caches only |

`common/entities/*` (also P1-owned) needs no change. `BaseAuditingEntity` already declares `@CreatedBy`/`@LastModifiedBy` as `String`, which matches `AuditorAware<String>`.

---

### Task 1: Fix the JPA auditor (`ApplicationAuditAware`)

Today every JPA insert/update made by a JWT-authenticated user throws `ClassCastException`. Line 35 casts the principal (the app `User` entity) to `java.nio.file.attribute.UserPrincipal`, so `PUT /api/v1/users/me/update` returns 500.

**Files:**
- Modify: `src/main/java/com/spring/app/configs/ApplicationAuditAware.java:1-43` (whole file)
- Test: `src/test/java/com/spring/app/configs/ApplicationAuditAwareTest.java` (create)
- Test: `src/test/java/com/spring/app/configs/ApplicationAuditAwareJpaTest.java` (create)

**Interfaces:**
- Consumes: `com.spring.app.modules.auth.entities.User` (`getId(): UUID` from `BaseEntity`, Lombok `@Builder`, `setId(UUID)`); `org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal#getAttribute(String)`.
- Produces: `ApplicationAuditAware implements AuditorAware<String>` with no constructor args (unchanged bean name `applicationAuditAware`, still referenced by `ApplicationConfig`'s `@EnableJpaAuditing(auditorAwareRef = "applicationAuditAware")`). Contract for P3: an OAuth2 principal's `"id"` attribute (the app user UUID) is used as the auditor.

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/spring/app/configs/ApplicationAuditAwareTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.spring.app.modules.auth.entities.User;

class ApplicationAuditAwareTest {

  private static final UUID USER_ID = UUID.fromString("3b0a4f0e-6a43-4c2b-9d0e-0c5a3f7d1e21");

  private final ApplicationAuditAware auditAware = new ApplicationAuditAware();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void noAuthenticationIsAttributedToSystem() {
    assertThat(auditAware.getCurrentAuditor()).contains("SYSTEM");
  }

  @Test
  void anonymousAuthenticationIsAttributedToSystem() {
    authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    assertThat(auditAware.getCurrentAuditor()).contains("SYSTEM");
  }

  @Test
  void jwtAuthenticatedAppUserIsAttributedToTheirId() {
    User user = User.builder().email("john@example.com").password("hash").build();
    user.setId(USER_ID);
    authenticate(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));

    assertThat(auditAware.getCurrentAuditor()).contains(USER_ID.toString());
  }

  @Test
  void oauth2PrincipalIsAttributedToTheAppUserIdAttribute() {
    OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
        Map.of("id", USER_ID, "email", "jane@example.com"), "email");
    authenticate(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));

    assertThat(auditAware.getCurrentAuditor()).contains(USER_ID.toString());
  }

  @Test
  void oauth2PrincipalWithoutIdFallsBackToItsName() {
    OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
        Map.of("email", "jane@example.com"), "email");
    authenticate(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));

    assertThat(auditAware.getCurrentAuditor()).contains("jane@example.com");
  }

  @Test
  void anyOtherPrincipalFallsBackToTheAuthenticationName() {
    authenticate(new UsernamePasswordAuthenticationToken("ops-script", null,
        AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

    assertThat(auditAware.getCurrentAuditor()).contains("ops-script");
  }

  private static void authenticate(Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
```

- [ ] **Step 2: Write the failing `@DataJpaTest`**

Create `src/test/java/com/spring/app/configs/ApplicationAuditAwareJpaTest.java`. It runs on the `test` profile (H2, Flyway off) with an embedded DB per context. `ApplicationConfig` is not part of a JPA slice, so auditing is switched on by the nested config, which registers the bean under the production name.

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

/**
 * Saves real entities through Hibernate with a security context in place, which
 * is exactly the path PUT /users/me/update takes.
 */
@DataJpaTest
@ActiveProfiles("test")
class ApplicationAuditAwareJpaTest {

  @TestConfiguration
  @EnableJpaAuditing(auditorAwareRef = "applicationAuditAware")
  static class AuditingConfig {

    @Bean
    ApplicationAuditAware applicationAuditAware() {
      return new ApplicationAuditAware();
    }
  }

  @Autowired
  private UserRepository userRepository;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void insertAndUpdateAreAttributedToTheAuthenticatedUsersId() {
    User creator = actor("11111111-1111-1111-1111-111111111111", "creator@example.com");
    authenticateAs(creator);

    User saved = userRepository.saveAndFlush(
        User.builder().email("new@example.com").password("hash").build());

    assertThat(saved.getCreatedBy()).isEqualTo(creator.getId().toString());
    assertThat(saved.getUpdatedBy()).isEqualTo(creator.getId().toString());
    assertThat(saved.getCreatedAt()).isNotNull();

    User editor = actor("22222222-2222-2222-2222-222222222222", "editor@example.com");
    authenticateAs(editor);
    saved.setFullName("Renamed User");

    User updated = userRepository.saveAndFlush(saved);

    assertThat(updated.getCreatedBy()).isEqualTo(creator.getId().toString());
    assertThat(updated.getUpdatedBy()).isEqualTo(editor.getId().toString());
  }

  @Test
  void insertWithoutAuthenticationIsAttributedToSystem() {
    User saved = userRepository.saveAndFlush(
        User.builder().email("anonymous-signup@example.com").password("hash").build());

    assertThat(saved.getCreatedBy()).isEqualTo("SYSTEM");
  }

  private static User actor(String id, String email) {
    User user = User.builder().email(email).password("hash").build();
    user.setId(UUID.fromString(id));
    return user;
  }

  private static void authenticateAs(User user) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
  }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -q test -Dtest='ApplicationAuditAwareTest,ApplicationAuditAwareJpaTest'`
Expected: `BUILD FAILURE`.
- In `ApplicationAuditAwareTest`, 4 tests fail (`jwtAuthenticatedAppUser...`, `oauth2PrincipalIsAttributed...`, `oauth2PrincipalWithoutId...`, `anyOtherPrincipal...`) with `java.lang.ClassCastException: class ... cannot be cast to class java.nio.file.attribute.UserPrincipal`.
- In `ApplicationAuditAwareJpaTest`, `insertAndUpdateAreAttributedToTheAuthenticatedUsersId` fails with the same `ClassCastException`.
- The two `SYSTEM` tests pass.

- [ ] **Step 4: Rewrite `ApplicationAuditAware`**

Replace the entire contents of `src/main/java/com/spring/app/configs/ApplicationAuditAware.java` with:

```java
package com.spring.app.configs;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

import com.spring.app.modules.auth.entities.User;

/**
 * Supplies the value JPA auditing writes to {@code created_by} /
 * {@code updated_by}.
 *
 * <p>
 * Resolution order:
 * </p>
 * <ol>
 * <li>no authentication, or an anonymous one: {@code SYSTEM}</li>
 * <li>the app {@link User} entity (what {@code JwtAuthenticatorFilter} puts in
 * the security context): its id</li>
 * <li>an OAuth2 principal carrying the app user id in its {@code id}
 * attribute (set by {@code CustomOAuth2UserService}): that id</li>
 * <li>any other principal: {@link Authentication#getName()}</li>
 * </ol>
 *
 * <p>
 * Never casts the principal to a fixed type and never queries the database:
 * this runs inside Hibernate's flush, where a query would re-enter the session
 * being flushed.
 * </p>
 */
@Component
public class ApplicationAuditAware implements AuditorAware<String> {

  private static final String SYSTEM_AUDITOR = "SYSTEM";
  private static final String OAUTH2_USER_ID_ATTRIBUTE = "id";

  @Override
  @NonNull
  public Optional<String> getCurrentAuditor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return Optional.of(SYSTEM_AUDITOR);
    }

    Object principal = authentication.getPrincipal();

    if (principal instanceof User user && user.getId() != null) {
      return Optional.of(user.getId().toString());
    }

    if (principal instanceof OAuth2AuthenticatedPrincipal oauth2Principal) {
      Object userId = oauth2Principal.getAttribute(OAUTH2_USER_ID_ATTRIBUTE);
      if (userId != null) {
        return Optional.of(userId.toString());
      }
    }

    String name = authentication.getName();
    return Optional.of(name == null || name.isBlank() ? SYSTEM_AUDITOR : name);
  }
}
```

(The per-insert `log.warn` lines are dropped on purpose. Anonymous inserts, such as registration, are normal and were flooding WARN.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest='ApplicationAuditAwareTest,ApplicationAuditAwareJpaTest'`
Expected: exit 0, 8 tests pass, no `[ERROR]` lines.

- [ ] **Step 6: Run the whole suite**

Run: `mvn -q test`
Expected: exit 0 (`BaseSpringApplicationTests.contextLoads` still green).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spring/app/configs/ApplicationAuditAware.java \
  src/test/java/com/spring/app/configs/ApplicationAuditAwareTest.java \
  src/test/java/com/spring/app/configs/ApplicationAuditAwareJpaTest.java
git commit -m "$(cat <<'EOF'
fix(audit): resolve auditor from app User / OAuth2 principal instead of casting

The auditor cast every principal to java.nio.file.attribute.UserPrincipal, so
any JPA write by a JWT-authenticated user threw ClassCastException (PUT
/users/me/update -> 500). Resolve the app user id, the OAuth2 "id" attribute,
or the authentication name; anonymous stays SYSTEM.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Let Spring Boot build the Redis connection (SSL + timeouts)

`RedisConfig.redisConnectionFactory` (lines 29-39) builds a `LettuceConnectionFactory` from `RedisStandaloneConfiguration` only. It ignores `spring.data.redis.ssl.enabled`, `timeout`, `connect-timeout`, pooling, and Boot's virtual-thread executor. The fix deletes the custom factory and lets Boot's `LettuceConnectionConfiguration` build it. That also makes Boot's `StringRedisTemplate` bean available, which Task 3 uses.

**Files:**
- Modify: `src/main/java/com/spring/app/configs/RedisConfig.java:1-96` (whole file; removes lines 29-39 and the now-unused imports)
- Modify: `src/main/resources/application.yml:70-77` (`spring.data.redis`)
- Modify: `src/main/resources/application-prod.yml:29-35` (`spring.data.redis`)
- Modify: `docker-compose.prod.yml:102-103` (allowed cross-edit, insert after line 103)
- Modify: `.env.example:46` (insert after)
- Test: `src/test/java/com/spring/app/configs/RedisConfigTest.java` (create)

**Interfaces:**
- Consumes: Boot's `RedisAutoConfiguration` (provides `RedisConnectionFactory` = `LettuceConnectionFactory`, and `StringRedisTemplate stringRedisTemplate`).
- Produces: beans `redisTemplate` (`RedisTemplate<String, Object>`, JSON values, unchanged) and `redisConnectionCheck` (unchanged). After this task there is no P1-declared `RedisConnectionFactory`, and exactly one `StringRedisTemplate` bean (Boot's). Task 3 relies on that bean.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/configs/RedisConfigTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Builds only the Redis part of the context. No Redis server is needed: the
 * Lettuce factory does not connect until first use, and the fail-fast ping is
 * switched off.
 */
class RedisConfigTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
      .withUserConfiguration(RedisConfig.class)
      .withPropertyValues("application.redis.fail-fast=false");

  @Test
  void connectionFactoryHonoursSslAndTimeoutsFromSpringDataRedisProperties() {
    contextRunner
        .withPropertyValues(
            "spring.data.redis.ssl.enabled=true",
            "spring.data.redis.timeout=1500ms",
            "spring.data.redis.connect-timeout=700ms")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(RedisConnectionFactory.class);

          LettuceClientConfiguration client = context.getBean(LettuceConnectionFactory.class)
              .getClientConfiguration();

          assertThat(client.isUseSsl()).isTrue();
          assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(1500));
          assertThat(client.getClientOptions()).isPresent();
          assertThat(client.getClientOptions().get().getSocketOptions().getConnectTimeout())
              .isEqualTo(Duration.ofMillis(700));
        });
  }

  @Test
  void jsonTemplateAndBootsStringTemplateAreBothAvailable() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasBean("redisTemplate");
      assertThat(context).hasSingleBean(StringRedisTemplate.class);
    });
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=RedisConfigTest`
Expected: `BUILD FAILURE`. `connectionFactoryHonoursSslAndTimeoutsFromSpringDataRedisProperties` fails at `assertThat(client.isUseSsl()).isTrue()` with `Expecting value to be true but was false`, because the custom factory ignores `ssl.enabled`. `jsonTemplateAndBootsStringTemplateAreBothAvailable` passes.

- [ ] **Step 3: Rewrite `RedisConfig` without the custom factory**

Replace the entire contents of `src/main/java/com/spring/app/configs/RedisConfig.java` with:

```java
package com.spring.app.configs;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis templates and the startup reachability check.
 *
 * <p>
 * There is deliberately no {@link RedisConnectionFactory} bean here. Spring
 * Boot's Lettuce auto-configuration builds it from {@code spring.data.redis.*}
 * and, unlike the hand-built factory this class used to declare, honours
 * {@code ssl.enabled}, {@code timeout}, {@code connect-timeout}, pooling and
 * (with {@code spring.threads.virtual.enabled}) the virtual-thread executor.
 * Boot also contributes the {@code StringRedisTemplate} that
 * {@code RedisService} uses for rate-limit counters.
 * </p>
 */
@Configuration
@Slf4j
public class RedisConfig {

  /**
   * Provides a RedisTemplate bean configured for interaction with Redis
   * using JSON serialization for values and String serialization for keys.
   * Its name ({@code redisTemplate}) makes Boot's own
   * {@code RedisTemplate<Object, Object>} back off.
   *
   * @param connectionFactory The RedisConnectionFactory to establish
   *                          connections with the Redis server.
   * @return A RedisTemplate instance for Redis operations.
   */
  @Bean
  RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();

    template.setConnectionFactory(connectionFactory);

    // Use String serializer for keys
    template.setKeySerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());

    // Use JSON serializer for values
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
    template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());

    template.afterPropertiesSet();

    return template;
  }

  /**
   * Verifies Redis is reachable, and stops startup if it is not.
   *
   * <p>
   * This deliberately lives outside {@link #redisTemplate} - a bean factory
   * method should build an object, not perform I/O. Keeping the check separate
   * also makes it switchable: {@code application.redis.fail-fast=false} lets
   * the context start without Redis, which is what the test profile needs.
   * </p>
   */
  @Bean
  @ConditionalOnProperty(name = "application.redis.fail-fast", havingValue = "true", matchIfMissing = true)
  ApplicationRunner redisConnectionCheck(RedisConnectionFactory connectionFactory) {
    return args -> {
      try (RedisConnection connection = connectionFactory.getConnection()) {
        connection.ping();
        log.info("Successfully connected to Redis");
      } catch (Exception e) {
        throw new IllegalStateException(
            "Redis is unreachable. Rate limiting, caching and refresh tokens all depend on it - "
                + "check REDIS_HOST/REDIS_PORT/REDIS_PASSWORD/REDIS_SSL_ENABLED, or set "
                + "application.redis.fail-fast=false to start anyway.",
            e);
      }
    };
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=RedisConfigTest`
Expected: exit 0, 2 tests pass.

- [ ] **Step 5: Set sane timeouts in `application.yml`**

In `src/main/resources/application.yml`, replace the block at HEAD lines 70-77:

```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 60000
      ssl:
        enabled: ${REDIS_SSL_ENABLED:false}
```

with:

```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      # Applied by Spring Boot's Lettuce auto-configuration (RedisConfig does not
      # build its own connection factory). `timeout` bounds every command,
      # `connect-timeout` the TCP/TLS handshake. Keep them short: rate limiting
      # fails open on a Redis error, but a hung command blocks the request.
      timeout: ${REDIS_TIMEOUT:2s}
      connect-timeout: ${REDIS_CONNECT_TIMEOUT:2s}
      ssl:
        enabled: ${REDIS_SSL_ENABLED:false}
```

(`spring.data.redis.*` is Boot's `RedisProperties`, which has no `@Validated` constraints, so no literal override in `application-test.yml` is required.)

- [ ] **Step 6: Document the prod TLS default in `application-prod.yml`**

In `src/main/resources/application-prod.yml`, replace HEAD lines 29-35:

```yaml
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: ${REDIS_SSL_ENABLED:true}
```

with:

```yaml
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}
      ssl:
        # TLS by default for a managed Redis. This is now actually applied, so
        # docker-compose.prod.yml sets REDIS_SSL_ENABLED=false for its bundled
        # plain-TCP redis:7-alpine.
        enabled: ${REDIS_SSL_ENABLED:true}
```

- [ ] **Step 7: Make the prod compose stack explicit (allowed cross-edit)**

In `docker-compose.prod.yml`, in the `springapp` service's `environment:` list, replace HEAD lines 102-103:

```yaml
      - REDIS_HOST=redis
      - REDIS_PORT=6379
```

with:

```yaml
      - REDIS_HOST=redis
      - REDIS_PORT=6379
      # The bundled redis service speaks plain TCP on the private network. The
      # prod profile defaults Redis TLS to on, which would fail every connection.
      - REDIS_SSL_ENABLED=false
```

- [ ] **Step 8: Add the new variables to `.env.example`**

In `.env.example`, after HEAD line 46 (`REDIS_SSL_ENABLED=false`), insert two lines so the block reads:

```
REDIS_PASSWORD=
REDIS_SSL_ENABLED=false
# Command / connect timeouts (Spring duration syntax).
REDIS_TIMEOUT=2s
REDIS_CONNECT_TIMEOUT=2s
# Refuse to start when Redis is unreachable. Leave true outside tests.
REDIS_FAIL_FAST=true
```

(If your tool is denied on `.env.example`, ask the user to insert the three lines `# Command / connect timeouts (Spring duration syntax).`, `REDIS_TIMEOUT=2s`, `REDIS_CONNECT_TIMEOUT=2s` after `REDIS_SSL_ENABLED=false`.)

- [ ] **Step 9: Verify config and the whole suite**

Run: `grep -n "REDIS_SSL_ENABLED" docker-compose.prod.yml src/main/resources/application*.yml`
Expected: three hits: `docker-compose.prod.yml` (`- REDIS_SSL_ENABLED=false`), `application.yml` (`${REDIS_SSL_ENABLED:false}`), `application-prod.yml` (`${REDIS_SSL_ENABLED:true}`).

Run: `mvn -q test`
Expected: exit 0. `contextLoads` now gets its `RedisConnectionFactory` from Boot and still does not connect, because the test profile sets `application.redis.fail-fast: false`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/spring/app/configs/RedisConfig.java \
  src/test/java/com/spring/app/configs/RedisConfigTest.java \
  src/main/resources/application.yml src/main/resources/application-prod.yml \
  docker-compose.prod.yml .env.example
git commit -m "$(cat <<'EOF'
fix(redis): use Boot's Lettuce factory so ssl and timeouts are honoured

The hand-built LettuceConnectionFactory ignored spring.data.redis.ssl.enabled
and all timeouts. Drop it, set 2s command/connect timeouts (REDIS_TIMEOUT,
REDIS_CONNECT_TIMEOUT), and set REDIS_SSL_ENABLED=false for the bundled
plain-TCP Redis in docker-compose.prod.yml now that the prod TLS default is
really applied.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Atomic rate-limit window on the String template (`RedisService`)

There are two bugs. First, `private final RedisTemplate<String, Object> rateLimitRedisTemplate;` is resolved by type, and the only `RedisTemplate<String,Object>` is the JSON `redisTemplate`, so rate limiting uses JSON (de)serialization. Second, `incrementRateLimit` (lines 106-114) sends INCR and then EXPIRE as two commands. If EXPIRE is lost, the key never expires and the client is blocked forever.

**Files:**
- Modify: `src/main/java/com/spring/app/shared/services/RedisService.java:1-149` (whole file)
- Modify: `src/main/java/com/spring/app/shared/interfaces/RedisServiceInterface.java:1-36` (whole file; Javadoc only, signatures unchanged)
- Test: `src/test/java/com/spring/app/shared/services/RedisServiceTest.java` (create)

**Interfaces:**
- Consumes: `StringRedisTemplate` bean (Boot, see Task 2); `RedisTemplate<String, Object> redisTemplate` (Task 2).
- Produces: constructor `RedisService(RedisTemplate<String, Object> redisTemplate, StringRedisTemplate stringRedisTemplate)`; constant `static final RedisScript<Long> INCREMENT_FIXED_WINDOW`; every `RedisServiceInterface` signature unchanged (`setValue`, `getValue`, `delete`, `hasKey`, `blacklistToken`, `isTokenBlacklisted`, `setRateLimitValue`, `incrementRateLimit(String, long): long`, `getRateLimitValue(String): Object` (now always a `String` or `null`), `deleteRateLimitKey`, `hasRateLimitKey`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/shared/services/RedisServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Pure Mockito: no Redis server. What Redis does with the Lua script is
 * covered by the opt-in Testcontainers IT (P6).
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceTest {

  private static final String KEY = "rate_limit:AUTH:minute:203.0.113.7";

  @Mock
  private RedisTemplate<String, Object> redisTemplate;

  @Mock
  private StringRedisTemplate stringRedisTemplate;

  @Mock
  private ValueOperations<String, String> stringValueOps;

  @Captor
  private ArgumentCaptor<RedisScript<Long>> scriptCaptor;

  private RedisService redisService;

  @BeforeEach
  void setUp() {
    redisService = new RedisService(redisTemplate, stringRedisTemplate);
  }

  @Test
  void incrementRunsIncrAndExpireAsOneScriptOnTheStringTemplate() {
    when(stringRedisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq("60")))
        .thenReturn(1L);

    long count = redisService.incrementRateLimit(KEY, 60);

    assertThat(count).isEqualTo(1L);
    verify(stringRedisTemplate).execute(scriptCaptor.capture(), eq(List.of(KEY)), eq("60"));
    RedisScript<Long> script = scriptCaptor.getValue();
    assertThat(script.getResultType()).isEqualTo(Long.class);
    assertThat(script.getScriptAsString())
        .contains("redis.call('INCR', KEYS[1])")
        .contains("redis.call('EXPIRE', KEYS[1], ARGV[1])")
        .contains("redis.call('TTL', KEYS[1]) == -1");

    // No separate round trips that could leave a counter without a TTL.
    verify(stringRedisTemplate, never()).opsForValue();
    verify(stringRedisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    verifyNoInteractions(redisTemplate);
  }

  @Test
  void incrementTreatsANullReplyAsZero() {
    when(stringRedisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq("3600")))
        .thenReturn((Long) null);

    assertThat(redisService.incrementRateLimit(KEY, 3600)).isZero();
  }

  @Test
  void rateLimitValuesAreWrittenAndReadAsPlainStrings() {
    when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
    when(stringValueOps.get(KEY)).thenReturn("7");

    redisService.setRateLimitValue(KEY, 5, 60, TimeUnit.SECONDS);

    verify(stringValueOps).set(KEY, "5", 60, TimeUnit.SECONDS);
    assertThat(redisService.getRateLimitValue(KEY)).isEqualTo("7");
    verifyNoInteractions(redisTemplate);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=RedisServiceTest`
Expected: `BUILD FAILURE` at test compilation: `incompatible types: org.springframework.data.redis.core.StringRedisTemplate cannot be converted to org.springframework.data.redis.core.RedisTemplate<java.lang.String,java.lang.Object>` (the constructor still takes two `RedisTemplate<String,Object>`).

- [ ] **Step 3: Rewrite `RedisService`**

Replace the entire contents of `src/main/java/com/spring/app/shared/services/RedisService.java` with:

```java
package com.spring.app.shared.services;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin Redis facade.
 *
 * <p>
 * Two templates, chosen by type so Spring can never swap them:
 * </p>
 * <ul>
 * <li>{@code redisTemplate} ({@code RedisTemplate<String, Object>}, JSON
 * values): OTPs and the token blacklist.</li>
 * <li>{@code stringRedisTemplate} (Boot's {@link StringRedisTemplate}, plain
 * strings): rate-limit counters, which must be raw integers for INCR.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService implements RedisServiceInterface {

  private static final String BLACKLIST_PREFIX = "blacklist:token:";

  /**
   * Fixed-window counter in one server-side step, so a counter can never exist
   * without a TTL. EXPIRE is applied when the window opens ({@code count == 1})
   * and also whenever the key has no TTL ({@code TTL == -1}), which re-arms
   * counters left without one by the old two-command INCR/EXPIRE code. A
   * steady stream of requests never extends a live window.
   */
  static final RedisScript<Long> INCREMENT_FIXED_WINDOW = RedisScript.of("""
      local count = redis.call('INCR', KEYS[1])
      if count == 1 or redis.call('TTL', KEYS[1]) == -1 then
        redis.call('EXPIRE', KEYS[1], ARGV[1])
      end
      return count
      """, Long.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final StringRedisTemplate stringRedisTemplate;

  /**
   * Sets a value in the Redis store with a TTL (Time-To-Live) that is
   * specified by the given duration and unit. The value is stored under the
   * given key.
   *
   * @param key      the key under which the value is stored
   * @param value    the value to store
   * @param duration the duration of the TTL
   * @param unit     the unit of time for the TTL
   */
  @Override
  public void setValue(String key, Object value, long duration, TimeUnit unit) {
    try {
      redisTemplate.opsForValue().set(key, value, duration, unit);
    } catch (Exception e) {
      log.error("Failed to set value in Redis for key: {}", key, e);
      throw new RuntimeException("Could not store value in Redis", e);
    }
  }

  /**
   * Retrieves the value that is associated with the given key from the Redis
   * store. If no value is associated with the key, null is returned.
   *
   * @param key the key for which the value is retrieved
   * @return the associated value, or null if no value is associated with the
   *         given key
   */
  @Override
  public Object getValue(String key) {
    return redisTemplate.opsForValue().get(key);
  }

  /**
   * Deletes the value associated with the given key from the Redis store.
   *
   * @param key the key for which the value should be deleted
   */
  @Override
  public void delete(String key) {
    redisTemplate.delete(key);
  }

  /**
   * Determines whether the given key exists in the Redis store.
   *
   * @param key the key for which to check existence
   * @return true if the key exists, false otherwise
   */
  @Override
  public boolean hasKey(String key) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
  }

  @Override
  public void blacklistToken(String token, long expirySeconds) {
    if (expirySeconds > 0) {
      redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "1", expirySeconds, TimeUnit.SECONDS);
    }
  }

  @Override
  public boolean isTokenBlacklisted(String token) {
    return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
  }

  @Override
  public void setRateLimitValue(String key, Object value, long duration, TimeUnit unit) {
    stringRedisTemplate.opsForValue().set(key, String.valueOf(value), duration, unit);
  }

  @Override
  public long incrementRateLimit(String key, long windowSeconds) {
    Long count = stringRedisTemplate.execute(INCREMENT_FIXED_WINDOW, List.of(key), String.valueOf(windowSeconds));
    return count == null ? 0L : count;
  }

  @Override
  public Object getRateLimitValue(String key) {
    return stringRedisTemplate.opsForValue().get(key);
  }

  @Override
  public void deleteRateLimitKey(String key) {
    stringRedisTemplate.delete(key);
  }

  @Override
  public boolean hasRateLimitKey(String key) {
    return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
  }
}
```

- [ ] **Step 4: Update the interface contract**

Replace the entire contents of `src/main/java/com/spring/app/shared/interfaces/RedisServiceInterface.java` with:

```java
package com.spring.app.shared.interfaces;

import java.util.concurrent.TimeUnit;

public interface RedisServiceInterface {
  void setValue(String key, Object value, long duration, TimeUnit unit);

  Object getValue(String key);

  void delete(String key);

  boolean hasKey(String key);

  void blacklistToken(String token, long expirySeconds);

  boolean isTokenBlacklisted(String token);

  /**
   * Stores {@code String.valueOf(value)} as a plain string in the rate-limit
   * keyspace.
   */
  void setRateLimitValue(String key, Object value, long duration, TimeUnit unit);

  /**
   * Increments a fixed-window rate-limit counter and returns the new value.
   * INCR and EXPIRE run as one Lua script, so the counter can never be left
   * without a TTL. The TTL is set when the window opens (and re-armed if the
   * key somehow has none), and a steady stream of requests does NOT extend it.
   *
   * @param key           the counter key
   * @param windowSeconds TTL of the window, applied when the window opens
   * @return the counter value after incrementing ({@code 0} if Redis returned
   *         no reply)
   */
  long incrementRateLimit(String key, long windowSeconds);

  /**
   * @return the raw counter as a {@code String}, or {@code null} if absent
   */
  Object getRateLimitValue(String key);

  void deleteRateLimitKey(String key);

  boolean hasRateLimitKey(String key);
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=RedisServiceTest`
Expected: exit 0, 3 tests pass.

- [ ] **Step 6: Run the whole suite (wiring check)**

Run: `mvn -q test`
Expected: exit 0. `contextLoads` proves Spring resolves `StringRedisTemplate` uniquely (Boot's `stringRedisTemplate`). P4's `rateLimitRedisTemplate` is a `RedisTemplate<String,String>`, not a `StringRedisTemplate`, so it is not a candidate.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/RedisService.java \
  src/main/java/com/spring/app/shared/interfaces/RedisServiceInterface.java \
  src/test/java/com/spring/app/shared/services/RedisServiceTest.java
git commit -m "$(cat <<'EOF'
fix(redis): atomic INCR+EXPIRE via Lua on StringRedisTemplate

rateLimitRedisTemplate was declared RedisTemplate<String,Object>, so Spring
injected the JSON template. INCR and EXPIRE were separate commands: losing the
EXPIRE left a counter without TTL and blocked the client forever. Use Boot's
StringRedisTemplate and one Lua script that also re-arms TTL-less keys.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Stop caching JPA entities in `AuthCacheService`

`User` and `RefreshToken` entities are cached in Redis. Serialization fails: there is no JavaTimeModule for the `Instant` audit fields, the getter-only `username`/`enabled` properties are unknown on read, and `@JsonIgnore password` reads back as `null`, which `activeAccount` then saves. The cached `RefreshToken` also goes stale after the bulk `revokeAllByUser` (saving it un-revokes it). Fix: every read goes to the repository. The method signatures stay the same, so `AuthServiceImpl` (P3) compiles unchanged. "Update/evict" now evicts the DTO cache `userProfile`. E-mails are normalised (trim + lower-case, `Locale.ROOT`) before lookup. In prod this is a no-op because the `users` table uses `utf8mb4_unicode_ci`, but it keeps every e-mail-derived key canonical. The raw-token / e-mail DEBUG logs are removed.

**Files:**
- Modify: `src/main/java/com/spring/app/shared/services/AuthCacheService.java:1-51` (whole file)
- Test: `src/test/java/com/spring/app/shared/services/AuthCacheServiceTest.java` (create)

**Interfaces:**
- Consumes: `CacheConfig.USER_PROFILE` (`"userProfile"`, exists at HEAD); a `CacheManager` bean (HEAD `CacheConfig#cacheManager`; Task 6 keeps the bean); `UserRepository#findByEmail(String): Optional<User>`; `RefreshTokenRepository#findByToken(String): Optional<RefreshToken>`.
- Produces (signatures unchanged except the added constructor dependency):
  - `AuthCacheService(UserRepository, RefreshTokenRepository, CacheManager)` (Lombok `@RequiredArgsConstructor`, in this order)
  - `public static String normalizeEmail(String email)`: `null` → `null`, else `email.trim().toLowerCase(Locale.ROOT)`
  - `public User getUserByEmail(String email)`: repository read, throws `ResourceNotFoundException("User not found")` (no e-mail in the message)
  - `public User updateCachedUser(User user)`: evicts `userProfile[user.id]`, returns `user`
  - `public void evictCachedUser(String email)`: resolves the id and evicts `userProfile[id]`
  - `public RefreshToken getRefreshToken(String token)`: repository read, throws `BadRequestException("Invalid refresh token")`
  - `public void evictCachedToken(String token)`: no-op (nothing cached), kept for callers

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/shared/services/AuthCacheServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import com.spring.app.configs.CacheConfig;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthCacheServiceTest {

  @Mock
  private UserRepository userRepository;

  @Mock
  private RefreshTokenRepository refreshTokenRepository;

  private final CacheManager cacheManager = new ConcurrentMapCacheManager(CacheConfig.USER_PROFILE);

  private AuthCacheService service;
  private User user;

  @BeforeEach
  void setUp() {
    service = new AuthCacheService(userRepository, refreshTokenRepository, cacheManager);
    user = User.builder()
        .email("john@example.com")
        .password("$2a$10$hashhashhash")
        .status(EUserStatus.PENDING)
        .build();
    user.setId(UUID.fromString("5d1c8a0b-2f4e-4c3a-8b6d-9e7f1a2b3c4d"));
  }

  @Test
  void getUserByEmailNormalisesTheAddressAndReturnsTheManagedEntity() {
    when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));

    User found = service.getUserByEmail("  John@Example.COM ");

    assertThat(found).isSameAs(user);
    assertThat(found.getPassword()).isEqualTo("$2a$10$hashhashhash");
  }

  @Test
  void getUserByEmailDoesNotEchoTheAddressWhenMissing() {
    when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getUserByEmail("ghost@example.com"))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("User not found");
  }

  @Test
  void updateCachedUserEvictsTheUsersProfileDto() {
    profileCache().put(user.getId(), "stale profile");

    assertThat(service.updateCachedUser(user)).isSameAs(user);
    assertThat(profileCache().get(user.getId())).isNull();
  }

  @Test
  void evictCachedUserResolvesTheIdAndEvictsTheProfileDto() {
    when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(user));
    profileCache().put(user.getId(), "stale profile");

    service.evictCachedUser("John@Example.com");

    assertThat(profileCache().get(user.getId())).isNull();
  }

  @Test
  void refreshTokensAreAlwaysReadFromTheRepository() {
    RefreshToken token = RefreshToken.builder().token("abc").isRevoked(true).user(user).build();
    when(refreshTokenRepository.findByToken("abc")).thenReturn(Optional.of(token));

    assertThat(service.getRefreshToken("abc")).isSameAs(token);
    assertThat(service.getRefreshToken("abc").isRevoked()).isTrue();
    verify(refreshTokenRepository, times(2)).findByToken("abc");
  }

  @Test
  void unknownRefreshTokenIsABadRequest() {
    when(refreshTokenRepository.findByToken("nope")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getRefreshToken("nope"))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("Invalid refresh token");
  }

  @Test
  void normalizeEmailTrimsAndLowerCases() {
    assertThat(AuthCacheService.normalizeEmail("  Mixed.Case@Example.ORG ")).isEqualTo("mixed.case@example.org");
    assertThat(AuthCacheService.normalizeEmail(null)).isNull();
  }

  private Cache profileCache() {
    return Objects.requireNonNull(cacheManager.getCache(CacheConfig.USER_PROFILE));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=AuthCacheServiceTest`
Expected: `BUILD FAILURE` at test compilation: `constructor AuthCacheService in class com.spring.app.shared.services.AuthCacheService cannot be applied to given types` and `cannot find symbol: method normalizeEmail(java.lang.String)`.

- [ ] **Step 3: Rewrite `AuthCacheService`**

Replace the entire contents of `src/main/java/com/spring/app/shared/services/AuthCacheService.java` with:

```java
package com.spring.app.shared.services;

import java.util.Locale;
import java.util.UUID;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.spring.app.configs.CacheConfig;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * User / refresh-token lookups for the auth flows.
 *
 * <p>
 * JPA entities are deliberately NOT cached. A {@code User} or
 * {@code RefreshToken} read back from Redis is detached, comes back without
 * its {@code @JsonIgnore}d password, and goes stale the moment a bulk JPQL
 * update (e.g. {@code revokeAllByUser}) runs; saving such a copy resurrects old
 * state. Every read here therefore goes to the database. The only cache this
 * class touches is {@link CacheConfig#USER_PROFILE} (an immutable
 * {@code UserResponseDto} per user id), which it evicts when a user changes.
 * </p>
 *
 * <p>
 * The method names are kept so existing callers compile unchanged.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class AuthCacheService {

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final CacheManager cacheManager;

  /**
   * Canonical form of an e-mail address for lookups and any e-mail-derived
   * key: trimmed and lower-cased with {@link Locale#ROOT}. MySQL compares
   * {@code users.email} case-insensitively, while Redis keys and H2 do not.
   */
  public static String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  /** Always a fresh, managed entity from the database. */
  public User getUserByEmail(String email) {
    return userRepository.findByEmail(normalizeEmail(email))
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
  }

  /**
   * Call after changing a user. Nothing entity-shaped is cached; this evicts
   * the user's cached profile DTO so the next read reflects the change.
   */
  public User updateCachedUser(User user) {
    if (user != null && user.getId() != null) {
      evictUserProfile(user.getId());
    }
    return user;
  }

  /** Evicts the cached profile DTO of the user with this e-mail, if any. */
  public void evictCachedUser(String email) {
    userRepository.findByEmail(normalizeEmail(email))
        .map(User::getId)
        .ifPresent(this::evictUserProfile);
  }

  /** Always read from the database, so a revoked token is seen as revoked. */
  public RefreshToken getRefreshToken(String token) {
    return refreshTokenRepository.findByToken(token)
        .orElseThrow(() -> new BadRequestException("Invalid refresh token"));
  }

  /**
   * No-op: refresh tokens are no longer cached. Kept so existing callers
   * compile; never log the token value.
   */
  public void evictCachedToken(String token) {
    // Intentionally empty.
  }

  private void evictUserProfile(UUID userId) {
    Cache cache = cacheManager.getCache(CacheConfig.USER_PROFILE);
    if (cache != null) {
      cache.evict(userId);
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=AuthCacheServiceTest`
Expected: exit 0, 7 tests pass.

- [ ] **Step 5: Run the whole suite**

Run: `mvn -q test`
Expected: exit 0. `contextLoads` injects the existing `RedisCacheManager` into `AuthCacheService`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/AuthCacheService.java \
  src/test/java/com/spring/app/shared/services/AuthCacheServiceTest.java
git commit -m "$(cat <<'EOF'
fix(cache): stop caching User/RefreshToken entities in AuthCacheService

Cached entities failed to (de)serialize, lost the @JsonIgnore'd password that
activeAccount then saved, and went stale after bulk revokeAllByUser (saving the
stale token un-revoked it). Read through the repositories, normalise e-mails,
evict the userProfile DTO on user changes, and stop logging raw tokens/e-mails.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: User module returns DTOs; cache the DTO, evict on update

`UserService.getUserProfile` caches a `ResponseEntity` under `userProfile`. It has no Jackson creator, so `GET /users/me` fails on a cache read. The service uses `jakarta.transaction.Transactional`, and `updateUserProfile` does `@CachePut` of a `ResponseEntity` instead of evicting. Fix: service methods return `UserResponseDto` with Spring's `@Transactional` (`readOnly = true` on the read), `@Cacheable` on the DTO, and `@CacheEvict` on update. The controller wraps the DTO with `ResponseBuilder`. This task also adds a guard test: no cached method may return an `@Entity` or `ResponseEntity`.

**Files:**
- Modify: `src/main/java/com/spring/app/modules/user/services/UserServiceInterface.java:1-13` (whole file)
- Modify: `src/main/java/com/spring/app/modules/user/services/impl/UserService.java:1-61` (whole file)
- Modify: `src/main/java/com/spring/app/modules/user/controller/UserController.java:1-56` (whole file)
- Test: `src/test/java/com/spring/app/configs/CachedReturnTypesTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/user/services/impl/UserServiceTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/user/controller/UserControllerTest.java` (create)

**Interfaces:**
- Consumes: `CacheConfig.USER_PROFILE`; `AuthMapper#userToUserResponseDto(User): UserResponseDto` (generated `com.spring.app.modules.auth.mapper.AuthMapperImpl`); `UpdateUserMapper#updateUserDtoToUser(UpdateUserDto, User)` (generated `com.spring.app.modules.user.mapper.UpdateUserMapperImpl`); `UserRepository#findById(UUID)`, `#saveAndFlush(User)`; `ResponseBuilder.success(String, T): ResponseEntity<BaseResponse<T>>`.
- Produces:
  - `UserServiceInterface#getUserProfile(UUID userId): UserResponseDto`
  - `UserServiceInterface#updateUserProfile(UUID userId, UpdateUserDto updateUserDto): UserResponseDto`
  - `UserController#getUserProfile(): ResponseEntity<BaseResponse<UserResponseDto>>` (message `"Get user profile successfully"`)
  - `UserController#updateUserProfile(UpdateUserDto): ResponseEntity<BaseResponse<UserResponseDto>>` (message `"Update user profile successfully"`)
  - Cache contract: `userProfile` entries are `UserResponseDto` keyed by the user's `UUID`.

- [ ] **Step 1: Write the failing guard test**

Create `src/test/java/com/spring/app/configs/CachedReturnTypesTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;

import com.spring.app.modules.user.services.impl.UserService;
import com.spring.app.shared.services.AuthCacheService;

import jakarta.persistence.Entity;

/**
 * Guard rail: values put into Redis must be immutable DTOs. A cached JPA
 * entity comes back detached and stale, and a cached ResponseEntity cannot be
 * deserialized at all.
 */
class CachedReturnTypesTest {

  @Test
  void cachedMethodsNeverReturnEntitiesOrResponseEntities() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
      @Override
      protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
        return true; // include interfaces, abstract and nested classes too
      }
    };
    scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);

    Set<String> scanned = new HashSet<>();
    List<String> offenders = new ArrayList<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("com.spring.app")) {
      Class<?> type = ClassUtils.forName(candidate.getBeanClassName(), getClass().getClassLoader());
      scanned.add(type.getName());
      for (Method method : type.getDeclaredMethods()) {
        boolean cached = AnnotatedElementUtils.hasAnnotation(method, Cacheable.class)
            || AnnotatedElementUtils.hasAnnotation(method, CachePut.class);
        Class<?> returned = method.getReturnType();
        if (cached && (returned.isAnnotationPresent(Entity.class)
            || ResponseEntity.class.isAssignableFrom(returned))) {
          offenders.add(type.getSimpleName() + "#" + method.getName() + " -> " + returned.getSimpleName());
        }
      }
    }

    // Sanity check that the scan really covered the caching classes.
    assertThat(scanned).contains(UserService.class.getName(), AuthCacheService.class.getName());
    assertThat(offenders).isEmpty();
  }
}
```

- [ ] **Step 2: Run the guard test to verify it fails**

Run: `mvn -q test -Dtest=CachedReturnTypesTest`
Expected: `BUILD FAILURE`. The assertion lists the offenders: `Expecting empty but was: ["UserService#getUserProfile -> ResponseEntity", "UserService#updateUserProfile -> ResponseEntity"]` (order may vary). `AuthCacheService` is already clean after Task 4.

- [ ] **Step 3: Write the failing service and controller tests**

Create `src/test/java/com/spring/app/modules/user/services/impl/UserServiceTest.java`. It uses a real Spring caching proxy with an in-memory cache, the real MapStruct mappers, and a mocked repository:

```java
package com.spring.app.modules.user.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.configs.CacheConfig;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.mapper.AuthMapperImpl;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.mapper.UpdateUserMapperImpl;
import com.spring.app.modules.user.services.UserServiceInterface;

@SpringJUnitConfig(UserServiceTest.Config.class)
class UserServiceTest {

  @Configuration
  @EnableCaching
  static class Config {

    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager(CacheConfig.USER_PROFILE);
    }

    @Bean
    UserRepository userRepository() {
      return mock(UserRepository.class);
    }

    @Bean
    UserService userService(UserRepository userRepository) {
      return new UserService(userRepository, new AuthMapperImpl(), new UpdateUserMapperImpl());
    }
  }

  private static final UUID USER_ID = UUID.fromString("7f3c2a3e-9d7b-4b6a-9a51-2f1f0d6c8e11");

  @Autowired
  private UserServiceInterface userService;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private CacheManager cacheManager;

  private User user;

  @BeforeEach
  void setUp() {
    reset(userRepository);
    profileCache().clear();

    user = User.builder()
        .email("john@example.com")
        .password("hash")
        .fullName("John Doe")
        .dateOfBirth(LocalDate.of(1990, 1, 15))
        .status(EUserStatus.ACTIVE)
        .build();
    user.setId(USER_ID);
    user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    user.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
  }

  @Test
  void profileIsReturnedAsADtoAndServedFromTheCacheTheSecondTime() {
    UserResponseDto first = userService.getUserProfile(USER_ID);
    UserResponseDto second = userService.getUserProfile(USER_ID);

    assertThat(first.email()).isEqualTo("john@example.com");
    assertThat(first.status()).isEqualTo("ACTIVE");
    assertThat(second).isEqualTo(first);
    verify(userRepository, times(1)).findById(USER_ID);
    assertThat(profileCache().get(USER_ID)).isNotNull();
    assertThat(profileCache().get(USER_ID).get()).isInstanceOf(UserResponseDto.class);
  }

  @Test
  void updateEvictsTheCachedProfile() {
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    userService.getUserProfile(USER_ID); // warm the cache

    UserResponseDto updated = userService.updateUserProfile(USER_ID,
        UpdateUserDto.builder().fullName("Jane Doe").build());

    assertThat(updated.fullName()).isEqualTo("Jane Doe");
    assertThat(profileCache().get(USER_ID)).isNull();
    assertThat(userService.getUserProfile(USER_ID).fullName()).isEqualTo("Jane Doe");
  }

  @Test
  void unknownUserIsNotFoundAndNothingIsCached() {
    UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000001");

    assertThatThrownBy(() -> userService.getUserProfile(unknown))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(profileCache().get(unknown)).isNull();
  }

  @Test
  void transactionsAreSpringOnesAndTheReadIsReadOnly() throws NoSuchMethodException {
    Method read = UserService.class.getMethod("getUserProfile", UUID.class);
    Method write = UserService.class.getMethod("updateUserProfile", UUID.class, UpdateUserDto.class);

    Transactional readTx = AnnotationUtils.findAnnotation(read, Transactional.class);
    Transactional writeTx = AnnotationUtils.findAnnotation(write, Transactional.class);

    assertThat(readTx).isNotNull();
    assertThat(readTx.readOnly()).isTrue();
    assertThat(writeTx).isNotNull();
    assertThat(writeTx.readOnly()).isFalse();
    assertThat(read.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
    assertThat(write.isAnnotationPresent(jakarta.transaction.Transactional.class)).isFalse();
  }

  private Cache profileCache() {
    return Objects.requireNonNull(cacheManager.getCache(CacheConfig.USER_PROFILE));
  }
}
```

Create `src/test/java/com/spring/app/modules/user/controller/UserControllerTest.java`:

```java
package com.spring.app.modules.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.services.UserServiceInterface;

class UserControllerTest {

  private static final UUID USER_ID = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

  private final UserServiceInterface userService = mock(UserServiceInterface.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();

    User principal = User.builder().email("john@example.com").password("hash").build();
    principal.setId(USER_ID);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void getProfileWrapsTheServiceDtoInTheStandardResponse() throws Exception {
    when(userService.getUserProfile(USER_ID)).thenReturn(UserResponseDto.builder()
        .id(USER_ID)
        .email("john@example.com")
        .dateOfBirth(LocalDate.of(1990, 1, 15))
        .build());

    mockMvc.perform(get("/api/v1/users/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusCode").value(200))
        .andExpect(jsonPath("$.message").value("Get user profile successfully"))
        .andExpect(jsonPath("$.data.email").value("john@example.com"))
        .andExpect(jsonPath("$.data.dateOfBirth").value("1990-01-15"));
  }

  @Test
  void updateProfilePassesTheCallersIdAndBody() throws Exception {
    when(userService.updateUserProfile(eq(USER_ID), any(UpdateUserDto.class)))
        .thenReturn(UserResponseDto.builder().id(USER_ID).fullName("Jane Doe").build());

    mockMvc.perform(put("/api/v1/users/me/update")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"fullName\":\"Jane Doe\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Update user profile successfully"))
        .andExpect(jsonPath("$.data.fullName").value("Jane Doe"));

    verify(userService).updateUserProfile(eq(USER_ID), argThat(dto -> "Jane Doe".equals(dto.fullName())));
  }
}
```

- [ ] **Step 4: Run the new tests to verify they fail**

Run: `mvn -q test -Dtest='UserServiceTest,UserControllerTest'`
Expected: `BUILD FAILURE` at test compilation, e.g. `incompatible types: org.springframework.http.ResponseEntity<capture#1 of ?> cannot be converted to com.spring.app.modules.auth.dto.response.UserResponseDto`.

- [ ] **Step 5: Change the service interface**

Replace the entire contents of `src/main/java/com/spring/app/modules/user/services/UserServiceInterface.java` with:

```java
package com.spring.app.modules.user.services;

import java.util.UUID;

import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;

public interface UserServiceInterface {
  UserResponseDto getUserProfile(UUID userId);

  UserResponseDto updateUserProfile(UUID userId, UpdateUserDto updateUserDto);
}
```

- [ ] **Step 6: Rewrite `UserService`**

Replace the entire contents of `src/main/java/com/spring/app/modules/user/services/impl/UserService.java` with:

```java
package com.spring.app.modules.user.services.impl;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.configs.CacheConfig;
import com.spring.app.exceptions.ResourceNotFoundException;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.mapper.UpdateUserMapper;
import com.spring.app.modules.user.services.UserServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Profile reads are cached as an immutable {@link UserResponseDto} (never the
 * entity, never a ResponseEntity) under {@link CacheConfig#USER_PROFILE},
 * keyed by user id. Any write evicts the entry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService implements UserServiceInterface {

  private final UserRepository userRepository;
  private final AuthMapper userMapper;
  private final UpdateUserMapper updateUserMapper;

  @Override
  @Transactional(readOnly = true)
  @Cacheable(value = CacheConfig.USER_PROFILE, key = "#userId")
  public UserResponseDto getUserProfile(UUID userId) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

    log.debug("User profile loaded from the database for userId: {}", userId);
    return userMapper.userToUserResponseDto(user);
  }

  @Override
  @Transactional
  @CacheEvict(value = CacheConfig.USER_PROFILE, key = "#userId")
  public UserResponseDto updateUserProfile(UUID userId, UpdateUserDto updateUserDto) {
    var user = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

    updateUserMapper.updateUserDtoToUser(updateUserDto, user);

    // Flush so the auditing fields in the response reflect this update.
    var saved = userRepository.saveAndFlush(user);

    log.info("User profile updated for userId: {}", userId);
    return userMapper.userToUserResponseDto(saved);
  }
}
```

- [ ] **Step 7: Rewrite `UserController`**

Replace the entire contents of `src/main/java/com/spring/app/modules/user/controller/UserController.java` with:

```java
package com.spring.app.modules.user.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.constants.ApplicationConstants;
import com.spring.app.modules.auth.dto.response.UserResponseDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;
import com.spring.app.modules.user.services.UserServiceInterface;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(ApplicationConstants.USER_BASE_PATH)
@Tag(name = "User Management", description = "APIs for managing user accounts")
@RequiredArgsConstructor
public class UserController {
  private final UserServiceInterface userService;

  @Operation(summary = "Get user profile", description = "Retrieve the profile of the authenticated user")
  @GetMapping("/me")
  public ResponseEntity<BaseResponse<UserResponseDto>> getUserProfile() {
    return ResponseBuilder.success("Get user profile successfully",
        userService.getUserProfile(currentUserId()));
  }

  @Operation(summary = "Update user profile", description = "Update the profile of the authenticated user")
  @PutMapping("/me/update")
  public ResponseEntity<BaseResponse<UserResponseDto>> updateUserProfile(
      @Valid @RequestBody UpdateUserDto updateUserDto) {
    return ResponseBuilder.success("Update user profile successfully",
        userService.updateUserProfile(currentUserId(), updateUserDto));
  }

  private UUID currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return ((User) auth.getPrincipal()).getId();
  }
}
```

- [ ] **Step 8: Run the task's tests to verify they pass**

Run: `mvn -q test -Dtest='CachedReturnTypesTest,UserServiceTest,UserControllerTest'`
Expected: exit 0, 7 tests pass (1 + 4 + 2).

- [ ] **Step 9: Run the whole suite**

Run: `mvn -q test`
Expected: exit 0.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/spring/app/modules/user/services/UserServiceInterface.java \
  src/main/java/com/spring/app/modules/user/services/impl/UserService.java \
  src/main/java/com/spring/app/modules/user/controller/UserController.java \
  src/test/java/com/spring/app/configs/CachedReturnTypesTest.java \
  src/test/java/com/spring/app/modules/user/services/impl/UserServiceTest.java \
  src/test/java/com/spring/app/modules/user/controller/UserControllerTest.java
git commit -m "$(cat <<'EOF'
fix(user): cache UserResponseDto, not ResponseEntity; evict on update

userProfile cached a ResponseEntity, which cannot be deserialized, so GET
/users/me failed on every cache hit. The service now returns DTOs (controller
wraps them with ResponseBuilder), uses Spring @Transactional (readOnly on the
read) and evicts the profile on update. Adds a guard test that no cached
method returns an @Entity or ResponseEntity.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Typed, declared-only, transaction-aware Redis cache (`CacheConfig`)

`CacheConfig.java:49` uses a bare `GenericJackson2JsonRedisSerializer`: no JavaTimeModule, default typing on, fails on unknown properties. After Tasks 4-5 the only cache is `userProfile`. This task gives it a typed `Jackson2JsonRedisSerializer<UserResponseDto>` backed by an `ObjectMapper` with JavaTimeModule, ISO dates, no default typing and unknown properties ignored (entries written by an older build stay readable). Undeclared cache names are refused. Puts and evicts are made transaction-aware, so an evict runs after commit. The dead `users`/`tokens` caches go away, and so do the now-meaningless properties: `default-ttl` (no undeclared caches), `tokens-ttl`, and `cache-null-values` (a typed serializer cannot store Spring's `NullValue` sentinel, so nulls are never cached).

**Files:**
- Modify: `src/main/java/com/spring/app/configs/CacheConfig.java:1-65` (whole file)
- Modify: `src/main/java/com/spring/app/configs/properties/CacheProperties.java:1-43` (whole file)
- Modify: `src/main/resources/application.yml` (`application.cache` block, HEAD 173-177, about 178-182 after Task 2)
- Modify: `src/test/resources/application-test.yml:63-66`
- Modify: `.env.example` (HEAD lines 51 and 53, 53 and 55 after Task 2)
- Test: `src/test/java/com/spring/app/configs/CacheConfigTest.java` (create)

**Interfaces:**
- Consumes: `CacheProperties#getUsersTtl(): Duration`; `UserResponseDto` (record, `@Builder`).
- Produces:
  - `CacheConfig(CacheProperties)` (Lombok `@RequiredArgsConstructor`)
  - `public static final String USER_PROFILE = "userProfile"` (unchanged; `USERS_CACHE` and `TOKENS_CACHE` are removed. Their only user was `AuthCacheService`, rewritten in Task 4)
  - `@Bean CacheManager cacheManager(RedisConnectionFactory)` returning a `RedisCacheManager` (transaction-aware, create-on-missing disabled)
  - package-private `Map<String, RedisCacheConfiguration> cacheConfigurations()`
  - `CacheProperties` with only `Duration usersTtl` (`@NotNull`, default 10m), bound from `application.cache.users-ttl` / `CACHE_USERS_TTL`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/configs/CacheConfigTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.spring.app.configs.properties.CacheProperties;
import com.spring.app.modules.auth.dto.response.UserResponseDto;

/**
 * Exercises the exact serializer the cache manager hands to Redis, without a
 * Redis server.
 */
class CacheConfigTest {

  private static final UUID USER_ID = UUID.fromString("c0ffee00-1234-4abc-9def-001122334455");

  private final CacheConfig cacheConfig = new CacheConfig(new CacheProperties());

  @Test
  void userProfileEntriesRoundTripThroughJsonWithoutTypeMetadata() {
    UserResponseDto dto = UserResponseDto.builder()
        .id(USER_ID)
        .email("john@example.com")
        .fullName("Nguyễn Văn A")
        .phone("0987654321")
        .avatarUrl("https://example.com/a.png")
        .address("1 Main St")
        .dateOfBirth(LocalDate.of(1990, 1, 15))
        .status("ACTIVE")
        .description("hello")
        .role("USER")
        .createdAt("2026-01-01T00:00:00Z")
        .updatedAt("2026-01-02T00:00:00Z")
        .build();

    RedisCacheConfiguration config = userProfileConfig();
    byte[] json = toBytes(config.getValueSerializationPair().write(dto));
    Object restored = config.getValueSerializationPair().read(ByteBuffer.wrap(json));

    assertThat(restored).isEqualTo(dto);
    assertThat(new String(json, StandardCharsets.UTF_8))
        .contains("\"dateOfBirth\":\"1990-01-15\"")
        .doesNotContain("@class");
  }

  @Test
  void entriesWrittenByAnOlderBuildWithExtraFieldsStillDeserialize() {
    String json = "{\"id\":\"" + USER_ID + "\",\"email\":\"john@example.com\",\"removedField\":true}";

    Object restored = userProfileConfig().getValueSerializationPair()
        .read(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));

    assertThat(restored).isInstanceOf(UserResponseDto.class);
    assertThat(((UserResponseDto) restored).email()).isEqualTo("john@example.com");
  }

  @Test
  void userProfileUsesTheConfiguredTtlAndNeverCachesNull() {
    RedisCacheConfiguration config = userProfileConfig();

    assertThat(config.getTtlFunction().getTimeToLive("key", null)).isEqualTo(Duration.ofMinutes(10));
    assertThat(config.getAllowCacheNullValues()).isFalse();
  }

  @Test
  void onlyDeclaredCachesExistAndTheyAreTransactionAware() {
    RedisCacheManager manager = (RedisCacheManager) cacheConfig.cacheManager(mock(RedisConnectionFactory.class));
    manager.afterPropertiesSet();

    assertThat(manager.getCache(CacheConfig.USER_PROFILE)).isInstanceOf(TransactionAwareCacheDecorator.class);
    assertThat(manager.getCache("users")).isNull();
    assertThat(manager.getCache("tokens")).isNull();
  }

  private RedisCacheConfiguration userProfileConfig() {
    RedisCacheConfiguration config = cacheConfig.cacheConfigurations().get(CacheConfig.USER_PROFILE);
    assertThat(config).isNotNull();
    return config;
  }

  private static byte[] toBytes(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=CacheConfigTest`
Expected: `BUILD FAILURE` at test compilation: `cannot find symbol: method cacheConfigurations()`.

- [ ] **Step 3: Rewrite `CacheConfig`**

Replace the entire contents of `src/main/java/com/spring/app/configs/CacheConfig.java` with:

```java
package com.spring.app.configs;

import java.time.Duration;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.spring.app.configs.properties.CacheProperties;
import com.spring.app.modules.auth.dto.response.UserResponseDto;

import lombok.RequiredArgsConstructor;

/**
 * Redis cache manager.
 *
 * <p>
 * Rules:
 * </p>
 * <ul>
 * <li>Only immutable DTOs are cached, never JPA entities or
 * {@code ResponseEntity} (guarded by {@code CachedReturnTypesTest}).</li>
 * <li>Every cache is declared in {@link #cacheConfigurations()} with a
 * serializer typed to its value class, so no {@code @class} type metadata is
 * written and nothing is deserialized into an unexpected type. Undeclared cache
 * names are refused at runtime ({@code Cannot find cache named ...}). To add a
 * cache, add an entry there.</li>
 * <li>The JSON mapper has JavaTimeModule (ISO dates) and ignores unknown
 * properties, so entries written by an older build stay readable after a DTO
 * loses a field.</li>
 * <li>Puts and evicts are transaction-aware: inside a transaction they run
 * after commit, so an evict cannot be undone by a reader that saw pre-commit
 * data.</li>
 * <li>{@code null} is never cached.</li>
 * </ul>
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class CacheConfig {

  /** {@link UserResponseDto} by user id ({@code UUID}). */
  public static final String USER_PROFILE = "userProfile";

  private static final ObjectMapper CACHE_OBJECT_MAPPER = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private final CacheProperties cacheProperties;

  @Bean
  CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    return RedisCacheManager.builder(connectionFactory)
        .withInitialCacheConfigurations(cacheConfigurations())
        .disableCreateOnMissingCache()
        .transactionAware()
        .build();
  }

  /** One entry per cache; nothing else may be cached. */
  Map<String, RedisCacheConfiguration> cacheConfigurations() {
    return Map.of(
        USER_PROFILE, typedConfiguration(UserResponseDto.class, cacheProperties.getUsersTtl()));
  }

  private static <T> RedisCacheConfiguration typedConfiguration(Class<T> valueType, Duration ttl) {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(ttl)
        .disableCachingNullValues()
        .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(SerializationPair.fromSerializer(
            new Jackson2JsonRedisSerializer<>(CACHE_OBJECT_MAPPER, valueType)));
  }
}
```

- [ ] **Step 4: Shrink `CacheProperties` to what is used**

Replace the entire contents of `src/main/java/com/spring/app/configs/properties/CacheProperties.java` with:

```java
package com.spring.app.configs.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Redis cache TTLs.
 *
 * <p>
 * Durations use the shorthand Spring Boot understands ({@code 30m},
 * {@code 5s}, {@code 1h}), which reviews better than a raw millisecond count.
 * Only caches declared in {@code CacheConfig} exist, so there is no default
 * TTL; and {@code null} is never cached.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.cache", ignoreUnknownFields = false)
public class CacheProperties {

  /** TTL for the {@code userProfile} cache ({@code UserResponseDto} by id). */
  @NotNull
  private Duration usersTtl = Duration.ofMinutes(10);
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=CacheConfigTest`
Expected: exit 0, 4 tests pass.

- [ ] **Step 6: Remove the obsolete keys from `application.yml`**

In `src/main/resources/application.yml`, replace (match on text):

```yaml
  cache:
    default-ttl: ${CACHE_DEFAULT_TTL:30m}
    users-ttl: ${CACHE_USERS_TTL:10m}
    tokens-ttl: ${CACHE_TOKENS_TTL:5m}
    cache-null-values: false
```

with:

```yaml
  cache:
    # Only immutable DTOs are cached, never JPA entities (see CacheConfig).
    # One cache today: userProfile (UserResponseDto by user id).
    users-ttl: ${CACHE_USERS_TTL:10m}
```

- [ ] **Step 7: Update the literal test override (strict binding requires it)**

In `src/test/resources/application-test.yml`, replace HEAD lines 63-66:

```yaml
  cache:
    default-ttl: 30m
    users-ttl: 10m
    tokens-ttl: 5m
```

with:

```yaml
  cache:
    users-ttl: 10m
```

- [ ] **Step 8: Remove the obsolete variables from `.env.example`**

In `.env.example`, delete the two lines `CACHE_DEFAULT_TTL=30m` and `CACHE_TOKENS_TTL=5m`, so the block reads:

```
# --- Cache TTLs (Spring duration syntax: 30m, 90s, 2h) ----------------------
CACHE_USERS_TTL=10m
```

(If your tool is denied on `.env.example`, ask the user to delete those two lines.)

- [ ] **Step 9: Verify no stale references remain, then run the whole suite**

Run: `grep -rn "USERS_CACHE\|TOKENS_CACHE\|getTokensTtl\|getDefaultTtl\|isCacheNullValues\|tokens-ttl\|default-ttl\|cache-null-values\|CACHE_TOKENS_TTL\|CACHE_DEFAULT_TTL" src docker-compose*.yml`
Expected: no output.

Run: `mvn -q test`
Expected: exit 0. `contextLoads` binds `application.cache.users-ttl` strictly with no unknown keys.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/spring/app/configs/CacheConfig.java \
  src/main/java/com/spring/app/configs/properties/CacheProperties.java \
  src/test/java/com/spring/app/configs/CacheConfigTest.java \
  src/main/resources/application.yml src/test/resources/application-test.yml .env.example
git commit -m "$(cat <<'EOF'
fix(cache): typed JSON serializer for userProfile, declared caches only

The generic serializer had no JavaTimeModule, used default typing and failed
on unknown properties. userProfile now uses Jackson2JsonRedisSerializer
<UserResponseDto> (JavaTimeModule, no type metadata, unknown fields ignored),
undeclared caches are refused, puts/evicts are transaction-aware, and the dead
users/tokens caches plus default-ttl / tokens-ttl / cache-null-values settings
are removed (CACHE_DEFAULT_TTL, CACHE_TOKENS_TTL dropped from .env.example).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Final verification sweep

No code changes. This task proves the spec bullets hold on the finished tree.

**Files:** none modified.

**Interfaces:** none.

- [ ] **Step 1: No entity caching and no old transactional annotation**

Run: `grep -rn "@Cacheable\|@CachePut" src/main/java`
Expected: exactly one hit, `UserService.java` (`@Cacheable(value = CacheConfig.USER_PROFILE, key = "#userId")`).

Run: `grep -rn "jakarta.transaction.Transactional" src/main/java/com/spring/app/modules/user src/main/java/com/spring/app/shared/services/AuthCacheService.java`
Expected: no output.

- [ ] **Step 2: No token or e-mail logging in the cache layer, no unsafe cast**

Run: `grep -n "log\." src/main/java/com/spring/app/shared/services/AuthCacheService.java`
Expected: no output.

Run: `grep -rn "java.nio.file.attribute.UserPrincipal" src/main/java`
Expected: no output.

- [ ] **Step 3: No custom Redis connection factory, no split INCR/EXPIRE**

Run: `grep -rn "new LettuceConnectionFactory\|RedisStandaloneConfiguration" src/main/java`
Expected: no output.

Run: `grep -n "\.expire(\|\.increment(" src/main/java/com/spring/app/shared/services/RedisService.java`
Expected: no output.

- [ ] **Step 4: Full suite, hermetic**

Run: `mvn -q test`
Expected: exit 0. New tests from this plan: `ApplicationAuditAwareTest` (6), `ApplicationAuditAwareJpaTest` (2), `RedisConfigTest` (2), `RedisServiceTest` (3), `AuthCacheServiceTest` (7), `CachedReturnTypesTest` (1), `UserServiceTest` (4), `UserControllerTest` (2), `CacheConfigTest` (4), plus the pre-existing tests.

- [ ] **Step 5: Confirm the tree is clean**

Run: `git status --short`
Expected: no modified tracked files from this plan (everything committed in Tasks 1-6).

---

## Hand-offs

**P2 email-otp**
- None required. `RedisServiceInterface.setValue/getValue/delete` keep their signatures. The JSON `redisTemplate` behind them is unchanged, so `OtpFunctionUtil` string OTPs work as before.

**P3 auth-security** (owns `AuthServiceImpl`, `CustomOAuth2UserService`, `OAuth2AuthenticationSuccessHandler`, `JwtAuthenticatorFilter`, `modules/user/dto/**`)
- `AuthCacheService` no longer caches anything entity-shaped. `getUserByEmail` / `getRefreshToken` always hit the DB, and `evictCachedToken` is a no-op. Remove the `authCacheService.evictCachedToken(...)` call in `AuthServiceImpl.refreshToken` (currently line 210) when you rewrite that method.
- `getUserByEmail` now throws `ResourceNotFoundException("User not found")` with no e-mail in the message, and lower-cases/trims the address before the lookup. `AuthCacheService.normalizeEmail(String)` is public static if you want the same canonical form at entry points. Your uniform-response work still has to decide the status code these flows return.
- After any change to a user that is visible in `UserResponseDto` (status change on activate, re-registration/reactivation, OAuth2 linking that updates avatar/name, admin status change), call `authCacheService.updateCachedUser(user)` so the `userProfile` cache is evicted. `activeAccount` and `resetPassword` already call it.
- `ApplicationAuditAware` uses an OAuth2 principal's `"id"` attribute (the app user UUID) as the auditor. Keep putting `"id"` into the `DefaultOAuth2User` attributes when you rewrite `CustomOAuth2UserService` (null-safe map). Without it the auditor falls back to the principal name (e-mail).
- `UserController` now builds the response with `ResponseBuilder`. `UserControllerTest` posts `fullName = "Jane Doe"`, which passes both the current and the Unicode-friendly regex you will put on `UpdateUserDto`.
- The spec's optional "short TTL cache of `UserResponseDto`" for the JWT filter: `CacheConfig` refuses undeclared caches, and P3 cannot edit `CacheConfig`. Either reuse `UserServiceInterface#getUserProfile(UUID)` (already cached in `userProfile`), or skip the optional item.

**P4 web-errors-ratelimit** (owns `RateLimitConfig`, `RateLimitManagerService`, `HealthCheckConfig`)
- `RedisService` now injects Boot's `StringRedisTemplate`. The `rateLimitRedisTemplate` bean in `RateLimitConfig` is unused, so delete it (or leave it for P6's dead-code pass).
- `redisService.getRateLimitValue(...)` now really returns a `String` (or `null`), so the `(String)` cast in `RateLimitManagerService.getRemainingRequests` is now correct.
- The `RedisConnectionFactory` that `HealthCheckConfig` injects now comes from Boot (same type, no code change needed).

**P5 uploads**
- None. Note: `application-test.yml` loses 2 lines in the `application.cache` block (HEAD 63-66 become 63-64), so the HEAD line numbers after it shift up by 2. Match on content.

**P6 build-ops-docs-cleanup**
- Opt-in Testcontainers IT (`-Pintegration`) with real Redis:
  1. `RedisService.incrementRateLimit`: the first call returns 1 and sets TTL = window. Later calls do not extend the TTL. A pre-existing counter without TTL (`SET k 5`) gets a TTL after one increment.
  2. `GET /api/v1/users/me` twice reads MySQL once. Redis key `userProfile::<uuid>` holds JSON without `@class` and with `"dateOfBirth":"yyyy-MM-dd"`. `PUT /api/v1/users/me/update` evicts it after commit.
  3. JPA auditing on a real MySQL save writes the caller's UUID into `created_by` / `updated_by`.
  4. The app connects with `spring.data.redis.ssl.enabled=false` and the 2s timeouts.
- Docs (CLAUDE.md/README): caching is a single `userProfile` cache of `UserResponseDto` (TTL `CACHE_USERS_TTL`, default 10m). Entities are never cached. The "30-minute default TTL" claim is false. `CACHE_DEFAULT_TTL`, `CACHE_TOKENS_TTL` and `application.cache.cache-null-values` are gone. Redis connection settings come from Boot's auto-configuration and honour `REDIS_SSL_ENABLED`, `REDIS_TIMEOUT` and `REDIS_CONNECT_TIMEOUT` (new). The rate-limit counter is an atomic Lua fixed window.
- Dead code: `AuthCacheService.evictCachedToken` (and `evictCachedUser` if P3 drops its callers), `ApplicationConstants.CACHE_USERS/CACHE_TOKENS/CACHE_PERMISSIONS`, `RateLimitConfig.rateLimitRedisTemplate` if P4 left it. For the duplicate `@EnableCaching`, keep the one on `CacheConfig` and remove the one on `BaseSpringApplication`.
- `.env.example` completeness script: expect `REDIS_TIMEOUT` and `REDIS_CONNECT_TIMEOUT` present and `CACHE_DEFAULT_TTL` / `CACHE_TOKENS_TTL` absent.

## Spec coverage (self-review)

| Spec bullet (P1) | Task |
|---|---|
| CacheConfig serializer lacks JavaTimeModule, entity caching, `ResponseEntity` in `userProfile`, unknown props, `@JsonIgnore password` saved as null; cache only immutable DTOs; round-trip unit test | 4 (no entity caching, password intact), 5 (DTO cache), 6 (typed mapper + round-trip test) |
| Stale token cache / no `@Cacheable` on entities | 4 (tokens read through), 5 (`CachedReturnTypesTest`), 7 (grep) |
| `ApplicationAuditAware` `ClassCastException`; anonymous / OAuth2; `@DataJpaTest` + security context | 1 |
| `RedisConfig` ignores ssl/timeouts; prod compose TLS | 2 (incl. `docker-compose.prod.yml` cross-edit) |
| `rateLimitRedisTemplate` gets the JSON template → `StringRedisTemplate` | 3 |
| INCR then EXPIRE not atomic → Lua | 3 (Mockito); real Redis → P6 hand-off |
| `UserService` jakarta `@Transactional`, `readOnly`, update doesn't evict | 5 |
| E-mail key normalisation in the cache layer | 4 (`normalizeEmail` on every e-mail lookup; no e-mail-keyed cache remains) |
| P3 note: `AuthCacheService.java:49` logs raw refresh token; P1 removes it | 4, 7 (grep) |
