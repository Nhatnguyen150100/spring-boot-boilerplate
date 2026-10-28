# P3 Auth & Security Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every authentication/authorization finding listed under "P3 auth-security" in the review-remediation spec: typed JWTs, uniform (non-enumerating) auth responses, failure-only login lockout, a safe OAuth2 flow, an explicit whitelist with ADMIN-only actuator metrics, short transactions, validated JWT keys, real auth metrics, and correct DTO validation / e-mail normalisation.

**Architecture:** `JwtService` decodes the signing key once and stamps every token with a `token_type` claim (`access` / `refresh`) plus a unique `jti`. `JwtAuthenticatorFilter` no longer keeps its own whitelist: it authenticates whenever a bearer token is present, swallows bad tokens (the request continues anonymously) and leaves the decision to `SecurityConfig`, which reads the single whitelist in `WhitelistUrlConstant`. `AuthServiceImpl` loses every `@Transactional` (multi-write steps use a `TransactionTemplate`), checks status only after the password matched, answers OTP/lookup endpoints identically for every account state, and counts only *failed* logins per (e-mail, IP) through a new `LoginAttemptService`. OAuth2 (OIDC for Google) resolves users in `CustomOAuth2UserService`, requires `email_verified`, and the handlers redirect to `application.frontend-url` with tokens / an error code in the URL fragment. New settings live in a new strict `AuthProperties` (`application.auth.*`).

**Tech Stack:** Java 21, Spring Boot 3.5.0, Spring Security 6.5, jjwt 0.12.6, Spring Data JPA / Redis, Micrometer, MapStruct 1.6.3, Lombok, JUnit 5, Mockito 5, AssertJ, MockMvc, H2 (tests).

**Spec:** `docs/superpowers/specs/2026-10-28-review-remediation-design.md` (section "### P3 auth-security", plus "Decisions", "Global constraints", the file-ownership table and "Allowed cross-edits").

## Global Constraints

Copied from the spec; every task implicitly includes them.

- Java 21, Spring Boot 3.5.x, Maven. `mvn test` must pass with nothing running (no MySQL, no Redis, no SMTP, no Docker).
- Config rules from CLAUDE.md are binding: framework keys under `spring.*`, app keys under `application.*` bound by `configs/properties/*` with `@Validated`; the six leaf properties classes keep `ignoreUnknownFields = false`; every validation-constrained property gets a **literal** override in `src/test/resources/application-test.yml`; every new env var is added to `.env.example`; profile files hold deltas only and never set `spring.profiles.active`.
- Flyway owns the schema (`src/main/resources/db/...`); any schema change is a new versioned migration, never an edit of an applied one. (This plan makes **no** schema change.)
- TDD: each fix lands with a test that fails before and passes after, unless the change is pure config/docs (then state how it was verified).
- Match surrounding code style: 2-space indent, Lombok, constructor injection, `ResponseBuilder` for HTTP responses.
- Commit per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Decision 3 (binding): restrict `/actuator/prometheus` to ADMIN; do NOT add Bucket4j, spring-session, or structured JSON logging.

### Ownership rules for this plan

P3 owns: `modules/auth/**` (except the entities' audit base), `JwtService`, `JwtServiceInterface`, `JwtFunctionUtil`, `JwtProperties`, `ApplicationConfig`, `SecurityConfig`, `WhitelistUrlConstant`, `JwtAuthenticatorFilter`, `CustomOAuth2UserService`, `handlers/*`, `MonitoringService`, `MetricsConfig`, `UserNotActiveException`, `CustomAuthenticationEntryPoint`, `common/validation/**`, `modules/user/dto/**`. It may also create new files in those areas, create `configs/properties/AuthProperties.java` (new `application.auth.*` settings), add the `application.auth` block to `application.yml`, append to `application-test.yml`, and add test classes. **Do not edit any other file.** Anything else is listed under "Hand-offs" at the end.

P1 and P2 are applied before this plan. This plan relies only on what they promise:
- P1: `AuthCacheService` keeps `updateCachedUser(User)` / `evictCachedUser(String)` (they now evict the `userProfile` DTO cache); `RedisServiceInterface` signatures unchanged; exactly one `StringRedisTemplate` bean (Boot's); `ApplicationAuditAware` has a no-arg constructor.
- P2: `OtpEmailService.sendOtpEmailAsync(String email)`, `OtpFunctionUtil.validateOtp(String email, String otp)`, `OtpFunctionUtil.removeOtp(String email)` keep their signatures; the util derives the namespaced Redis key itself.

## Design decisions taken in this plan (spec left them open)

1. **DELETED re-registration → 409, no reactivation.** `register` checks `existsByEmail` (any status — the same scope as `uk_users_email`) and answers `409 "This email cannot be registered"`; a concurrent duplicate that slips past the check is caught (`DataIntegrityViolationException`) and gets the same 409. The constraint is never surfaced as a 500, and the OTP is sent only after the row is saved.
2. **`UserNotActiveException extends BadRequestException`.** It becomes a 400 immediately through the existing handler; P4's explicit handler (already in P4's plan) makes it 403. It is thrown only *after* the password matched (DaoAuthenticationProvider post-authentication check), so it never enumerates.
3. **`InvalidTokenException extends BadCredentialsException`** (new, `modules/auth/exceptions`). It is a Spring Security `AuthenticationException`, which both the current `GlobalExceptionHandler` (BadCredentials handler) and P4's rewrite (AuthenticationException base type) map to 401.
4. **Login lockout** counts failures only, keyed on `sha256(normalised e-mail)` + client IP (`request.getRemoteAddr()`, which is the real client once P4's `server.tomcat.remoteip.*` settings are in place). Defaults: 5 failures / 15 minutes. Redis errors fail open (same policy as the existing rate limiter). Successful login clears the counter.
5. **OAuth2:** Google is configured with the `openid` scope, so Spring Security calls the **OIDC** user service; at HEAD the custom service was never invoked. This plan wires `oidcUserService(customOAuth2UserService::loadOidcUser)`. A PENDING/INACTIVE/DELETED local account is refused (`#error=account_not_active`); an unverified Google e-mail is refused before any DB lookup.
6. **Email verification skip** requires BOTH `application.auth.skip-email-verification=true` AND an active `dev` profile (`Environment.acceptsProfiles`). Default `false`.
7. **Security rule tests** use `@SpringBootTest` + `@AutoConfigureMockMvc` (real `SecurityConfig`, real filters, real actuator) instead of `@WebMvcTest`: the slice would need `SecurityConfig`'s nine collaborators imported by hand and has no actuator endpoints, which the ADMIN rule needs. Redis-touching beans are `@MockitoBean`-ed so nothing has to run.

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/spring/app/configs/properties/JwtProperties.java` | Modify | `@AssertTrue` Base64 + ≥256-bit key check |
| `src/main/java/com/spring/app/shared/services/JwtService.java` | Rewrite | Key decoded once; `token_type` + `jti` claims; `parseClaims`, `isAccessToken`, `isRefreshToken` |
| `src/main/java/com/spring/app/shared/interfaces/JwtServiceInterface.java` | Rewrite | Token-type constants and new methods |
| `src/main/java/com/spring/app/utils/JwtFunctionUtil.java` | Rewrite | `authenticateAccessToken`: type, blacklist, status checks |
| `src/main/java/com/spring/app/filter/JwtAuthenticatorFilter.java` | Rewrite | No whitelist; bad token → anonymous |
| `src/main/java/com/spring/app/modules/auth/exceptions/InvalidTokenException.java` | Create | 401 token error |
| `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` | Rewrite (over several tasks) | All auth use cases |
| `src/main/java/com/spring/app/modules/auth/services/AuthServiceInterface.java` | Modify | `login(dto, clientIp)`, `issueTokensForOAuth2Login` |
| `src/main/java/com/spring/app/modules/auth/controller/AuthController.java` | Modify | Pass client IP to login |
| `src/main/java/com/spring/app/modules/auth/repositories/UserRepository.java` | Modify | `existsByEmail` |
| `src/main/java/com/spring/app/modules/auth/repositories/RefreshTokenRepository.java` | Modify | `@Transactional` bulk updates, `revokeIfActive` |
| `src/main/java/com/spring/app/modules/auth/services/LoginAttemptService.java` | Create | Failure-only login counter in Redis |
| `src/main/java/com/spring/app/modules/auth/utils/EmailNormalizer.java` | Create | `normalize` (trim + lower-case) and `fingerprint` (SHA-256) |
| `src/main/java/com/spring/app/configs/properties/AuthProperties.java` | Create | `application.auth.*` (strict, validated) |
| `src/main/java/com/spring/app/configs/ApplicationConfig.java` | Modify | UserDetailsService / provider: status after password, normalised lookup |
| `src/main/java/com/spring/app/exceptions/UserNotActiveException.java` | Modify | Extends `BadRequestException` |
| `src/main/java/com/spring/app/shared/services/MonitoringService.java` | Rewrite | New counters |
| `src/main/java/com/spring/app/common/validation/annotations/MaxUtf8Bytes.java` | Create | Byte-length constraint |
| `src/main/java/com/spring/app/common/validation/validators/MaxUtf8BytesValidator.java` | Create | Its validator |
| `src/main/java/com/spring/app/common/validation/annotations/ValidFullName.java` | Create | Unicode full-name constraint |
| `src/main/java/com/spring/app/modules/auth/dto/request/{Login,Register,ResetPassword}RequestDto.java` | Modify | Password rules, full name |
| `src/main/java/com/spring/app/modules/user/dto/requests/UpdateUserDto.java` | Modify | Full name, avatar URL |
| `src/main/java/com/spring/app/shared/services/CustomOAuth2UserService.java` | Rewrite | OIDC + OAuth2, `email_verified`, status, no NPE, no PII logs |
| `src/main/java/com/spring/app/handlers/OAuth2FrontendRedirect.java` | Create | Builds frontend redirect URLs |
| `src/main/java/com/spring/app/handlers/OAuth2AuthenticationSuccessHandler.java` | Rewrite | Delegates to `AuthServiceInterface`, redirects |
| `src/main/java/com/spring/app/handlers/OAuth2AuthenticationFailureHandler.java` | Rewrite | Redirect with error code, no message echo |
| `src/main/java/com/spring/app/constants/WhitelistUrlConstant.java` | Rewrite | Explicit public / admin lists |
| `src/main/java/com/spring/app/configs/SecurityConfig.java` | Modify | Rules, ADMIN actuator, OIDC user service |
| `src/main/resources/application.yml` | Modify | New `application.auth` block only |
| `src/test/resources/application-test.yml` | Modify | Literal `application.auth.*` overrides |
| Tests (all new) | Create | See each task |

`MetricsConfig`, `CustomAuthenticationEntryPoint` and `CustomAccessDeniedHandler` need no change.

---

## Before you start (pre-flight, 2 minutes)

Run these and confirm each prints a match. They pin what P1/P2/P4 promised. If one prints nothing, a previous plan changed a signature: stop and adapt the call sites named in the comment before continuing.

```bash
cd /home/nhatnx/my-project/spring-boot-boilerplate
# P2 – AuthServiceImpl calls these with an e-mail argument
grep -n "public boolean validateOtp(String email, String otp)" src/main/java/com/spring/app/utils/OtpFunctionUtil.java
grep -n "public void removeOtp(String email)" src/main/java/com/spring/app/utils/OtpFunctionUtil.java
grep -n "public void sendOtpEmailAsync(String email)" src/main/java/com/spring/app/shared/services/OtpEmailService.java
# P1 – blacklist + profile-cache eviction
grep -n "void blacklistToken(String token, long expirySeconds)" src/main/java/com/spring/app/shared/interfaces/RedisServiceInterface.java
grep -n "boolean isTokenBlacklisted(String token)" src/main/java/com/spring/app/shared/interfaces/RedisServiceInterface.java
grep -n "public User updateCachedUser(User user)" src/main/java/com/spring/app/shared/services/AuthCacheService.java
# P1 – ApplicationConfig still compiles against this bean
grep -n "class ApplicationAuditAware implements AuditorAware<String>" src/main/java/com/spring/app/configs/ApplicationAuditAware.java
# Rate limiter used by the per-e-mail throttle (P4 runs later and keeps it)
grep -n "public void checkRateLimitAndThrow(ERateLimitEndpoint endpointType, String identifier)" src/main/java/com/spring/app/shared/services/RateLimitManagerService.java
```

Expected: eight matching lines. Never run `mvn` concurrently with another plan's executor (shared `target/`).

---

### Task 1: Validate the JWT signing key at startup and decode it once

Covers spec: "**JwtService.getSignInKey** re-decodes per call; `JwtProperties` only `@NotBlank` → validate Base64 and ≥256-bit at startup, decode once."

**Files:**
- Modify: `src/main/java/com/spring/app/configs/properties/JwtProperties.java:1-38` (whole file)
- Modify: `src/main/java/com/spring/app/shared/services/JwtService.java:1-124` (whole file)
- Test: `src/test/java/com/spring/app/configs/properties/JwtPropertiesTest.java` (create)
- Test: `src/test/java/com/spring/app/shared/services/JwtServiceTest.java` (create)

**Interfaces:**
- Consumes: nothing new.
- Produces: `JwtProperties.MIN_SECRET_KEY_BYTES = 32`; `JwtProperties#isSecretKeyStrong(): boolean` (`@AssertTrue`); explicit constructor `JwtService(JwtProperties)` that throws `io.jsonwebtoken.security.WeakKeyException` for a key < 256 bits; JwtService keeps private final `SecretKey signingKey` and `JwtParser parser`. Task 2 extends `JwtService`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/spring/app/configs/properties/JwtPropertiesTest.java`:

```java
package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class JwtPropertiesTest {

  private static final String STRONG_KEY_MESSAGE =
      "application.security.jwt.secret-key must be Base64 and decode to at least 256 bits (32 bytes)";

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private Set<String> violations(String secretKey) {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(secretKey);
    return validator.validate(properties).stream()
        .map(ConstraintViolation::getMessage)
        .collect(Collectors.toSet());
  }

  @Test
  void acceptsTheTestProfileKey() {
    assertThat(violations("dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==")).isEmpty();
  }

  @Test
  void acceptsExactly256Bits() {
    assertThat(violations(Base64.getEncoder().encodeToString(new byte[32]))).isEmpty();
  }

  @Test
  void rejectsAKeyThatIsNotBase64() {
    assertThat(violations("not base64 !!")).containsExactly(STRONG_KEY_MESSAGE);
  }

  @Test
  void rejectsAKeyShorterThan256Bits() {
    assertThat(violations(Base64.getEncoder().encodeToString(new byte[31]))).containsExactly(STRONG_KEY_MESSAGE);
  }

  @Test
  void blankKeyReportsOnlyTheRequiredMessage() {
    assertThat(violations("   ")).containsExactly("application.security.jwt.secret-key is required");
  }
}
```

Create `src/test/java/com/spring/app/shared/services/JwtServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;

import io.jsonwebtoken.security.WeakKeyException;

class JwtServiceTest {

  static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  private final JwtService jwtService = new JwtService(properties(SECRET));

  static JwtProperties properties(String secretKey) {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(secretKey);
    return properties;
  }

  static User user(String email) {
    User user = User.builder().email(email).password("irrelevant").status(EUserStatus.ACTIVE).build();
    user.setId(UUID.randomUUID());
    return user;
  }

  @Test
  void signsAndReadsBackTheSubject() {
    String token = jwtService.generateToken(user("a@example.com"));

    assertThat(jwtService.extractUsername(token)).isEqualTo("a@example.com");
  }

  @Test
  void refusesToStartWithAKeyShorterThan256Bits() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new JwtService(properties(shortKey))).isInstanceOf(WeakKeyException.class);
  }

  @Test
  void decodesTheKeyOnceAtConstruction() {
    JwtProperties properties = properties(SECRET);
    JwtService service = new JwtService(properties);

    // Changing the property afterwards must not change the key in use.
    properties.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));
    String token = service.generateToken(user("a@example.com"));

    assertThat(service.extractUsername(token)).isEqualTo("a@example.com");
    // Verifiable with the ORIGINAL key => it was signed with the original key.
    assertThat(jwtService.extractUsername(token)).isEqualTo("a@example.com");
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=JwtPropertiesTest,JwtServiceTest`
Expected: FAIL — `rejectsAKeyThatIsNotBase64` and `rejectsAKeyShorterThan256Bits` find no violation; `refusesToStartWithAKeyShorterThan256Bits` gets no exception; `decodesTheKeyOnceAtConstruction` fails with `io.jsonwebtoken.security.SignatureException` on the last assertion.

- [ ] **Step 3: Implement**

Replace the whole of `src/main/java/com/spring/app/configs/properties/JwtProperties.java` with:

```java
package com.spring.app.configs.properties;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * JWT signing and lifetime settings.
 *
 * <p>
 * {@code secretKey} has no default on purpose: the {@code prod} profile leaves
 * the placeholder without a fallback, so a deployment that forgets to set
 * {@code JWT_SECRET_KEY} fails at startup rather than signing tokens with a
 * value that leaked into source control.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.security.jwt", ignoreUnknownFields = false)
public class JwtProperties {

  /** HS256 needs a key of at least 256 bits. */
  public static final int MIN_SECRET_KEY_BYTES = 32;

  /** Base64-encoded secret. Must decode to at least 256 bits for HS256. */
  @NotBlank(message = "application.security.jwt.secret-key is required")
  private String secretKey;

  /** Access-token lifetime in milliseconds. */
  @Min(value = 60000, message = "application.security.jwt.expiration must be >= 60000ms")
  private long expiration = 86400000;

  /** Refresh-token lifetime in milliseconds. */
  @Min(value = 60000, message = "application.security.jwt.refresh-expiration must be >= 60000ms")
  private long refreshExpiration = 604800000;

  /**
   * Fails startup when the secret is not Base64 or is shorter than 256 bits,
   * instead of failing on the first login. A blank value is reported by
   * {@code @NotBlank} alone.
   */
  @AssertTrue(message = "application.security.jwt.secret-key must be Base64 and decode to at least 256 bits (32 bytes)")
  public boolean isSecretKeyStrong() {
    if (secretKey == null || secretKey.isBlank()) {
      return true;
    }
    try {
      return Base64.getDecoder().decode(secretKey.trim()).length >= MIN_SECRET_KEY_BYTES;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
```

Replace the whole of `src/main/java/com/spring/app/shared/services/JwtService.java` with:

```java
package com.spring.app.shared.services;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.shared.interfaces.JwtServiceInterface;

@Service
public class JwtService implements JwtServiceInterface {

  private final JwtProperties jwtProperties;
  private final SecretKey signingKey;
  private final JwtParser parser;

  /**
   * Decodes the signing key once. {@link JwtProperties#isSecretKeyStrong()}
   * has already rejected a non-Base64 or short secret at startup;
   * {@link Keys#hmacShaKeyFor} re-checks the 256-bit floor for callers that
   * build the properties by hand.
   */
  public JwtService(JwtProperties jwtProperties) {
    this.jwtProperties = jwtProperties;
    this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtProperties.getSecretKey().trim()));
    this.parser = Jwts.parser().verifyWith(signingKey).build();
  }

  /**
   * Retrieves the username from the given JWT token.
   *
   * @param token A JWT token.
   * @return The username from the token.
   */
  @Override
  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  @Override
  public UUID extractUserId(String token) {
    return extractClaim(token, claims -> claims.get("id", UUID.class));
  }

  @Override
  public ERole extractRole(String token) {
    return extractClaim(token, claims -> claims.get("role", ERole.class));
  }

  @Override
  public EUserStatus extractUserStatus(String token) {
    return extractClaim(token, claims -> claims.get("status", EUserStatus.class));
  }

  @Override
  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    final Claims claims = extractAllClaims(token);
    return claimsResolver.apply(claims);
  }

  @Override
  public String generateToken(User user) {
    Map<String, Object> extraClaims = getHashObject(user);
    return buildToken(extraClaims, user, jwtProperties.getExpiration());
  }

  @Override
  public String generateRefreshToken(User user) {
    Map<String, Object> extraClaims = getHashObject(user);
    return buildToken(extraClaims, user, jwtProperties.getRefreshExpiration());
  }

  @Override
  public boolean isTokenValid(String token, UserDetails userDetails) {
    final String username = extractUsername(token);
    return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
  }

  @Override
  public long getRemainingExpirationSeconds(String token) {
    Date expiration = extractClaim(token, Claims::getExpiration);
    long remainingMs = expiration.getTime() - System.currentTimeMillis();
    return Math.max(0, remainingMs / 1000);
  }

  private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, long expiration) {
    return Jwts.builder()
      .claims(extraClaims)
      .subject(userDetails.getUsername())
      .issuedAt(new Date(System.currentTimeMillis()))
      .expiration(new Date(System.currentTimeMillis() + expiration))
      .signWith(signingKey, Jwts.SIG.HS256)
      .compact();
  }

  private Map<String, Object> getHashObject(User user) {
    Map<String, Object> extraClaims = new HashMap<>();
    extraClaims.put("id", user.getId());
    extraClaims.put("role", user.getRole());
    extraClaims.put("email", user.getEmail());
    extraClaims.put("status", user.getStatus());
    return extraClaims;
  }

  private boolean isTokenExpired(String token) {
    return extractClaim(token, Claims::getExpiration).before(new Date());
  }

  private Claims extractAllClaims(String token) {
    return parser.parseSignedClaims(token).getPayload();
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=JwtPropertiesTest,JwtServiceTest,BaseSpringApplicationTests`
Expected: exit 0, no failures (`contextLoads` proves the test-profile key passes the new constraint and `JwtService` builds).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/configs/properties/JwtProperties.java \
  src/main/java/com/spring/app/shared/services/JwtService.java \
  src/test/java/com/spring/app/configs/properties/JwtPropertiesTest.java \
  src/test/java/com/spring/app/shared/services/JwtServiceTest.java
git commit -m "$(cat <<'EOF'
fix(jwt): validate the signing key at startup and decode it once

JwtProperties now rejects a secret that is not Base64 or is shorter than
256 bits at binding time. JwtService decodes the key and builds the
parser once in its constructor instead of on every call.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Typed tokens — refresh JWTs are no longer access tokens

Covers spec: "**JwtFunctionUtil.java:73** access & refresh tokens same key/claims, no `token_type` claim → refresh JWT accepted as Bearer access token for 7 days ... Add `typ`/`token_type` claim, reject non-access tokens in `JwtAuthenticatorFilter`, accept only refresh type at `/auth/refresh-token`"; "Logout with an expired/garbled token → 500 via JJWT exception — handle as 401"; "JWT filter hits DB every request via `loadUserByUsername` — acceptable, but status must be checked; keep, document"; the filter half of "single source of truth for the whitelist" (the filter stops consulting it).

**Files:**
- Modify: `src/main/java/com/spring/app/shared/interfaces/JwtServiceInterface.java:1-32` (whole file)
- Modify: `src/main/java/com/spring/app/shared/services/JwtService.java` (methods `generateToken`, `generateRefreshToken`, `buildToken`; add `parseClaims`, `isAccessToken`, `isRefreshToken`, `hasType`)
- Modify: `src/main/java/com/spring/app/utils/JwtFunctionUtil.java:1-81` (whole file)
- Modify: `src/main/java/com/spring/app/filter/JwtAuthenticatorFilter.java:1-74` (whole file)
- Create: `src/main/java/com/spring/app/modules/auth/exceptions/InvalidTokenException.java`
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (imports; constants; `logout` lines 174-196; `refreshToken` lines 198-218; new `parseAccessToken`)
- Test: `src/test/java/com/spring/app/shared/services/JwtServiceTest.java` (extend)
- Test: `src/test/java/com/spring/app/utils/JwtFunctionUtilTest.java` (create)
- Test: `src/test/java/com/spring/app/filter/JwtAuthenticatorFilterTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (create)

**Interfaces:**
- Consumes: `JwtService(JwtProperties)` (Task 1); `RedisServiceInterface#isTokenBlacklisted(String)`, `#blacklistToken(String, long)` (P1, unchanged).
- Produces:
  - `JwtServiceInterface.TOKEN_TYPE_CLAIM = "token_type"`, `ACCESS_TOKEN_TYPE = "access"`, `REFRESH_TOKEN_TYPE = "refresh"`.
  - `Claims JwtServiceInterface#parseClaims(String token)` — throws `io.jsonwebtoken.JwtException` / `IllegalArgumentException`.
  - `boolean JwtServiceInterface#isAccessToken(String)`, `#isRefreshToken(String)` — never throw.
  - `JwtFunctionUtil(JwtServiceInterface, UserDetailsService, RedisServiceInterface)`; `void JwtFunctionUtil#authenticateAccessToken(String jwt, HttpServletRequest request)` (replaces `authenticateUser`); `extractTokenFromHeader`, `isAuthenticationNotSet` unchanged.
  - `JwtAuthenticatorFilter(JwtFunctionUtil)`.
  - `com.spring.app.modules.auth.exceptions.InvalidTokenException extends BadCredentialsException` with `(String)` constructor.
  - `AuthServiceImpl` package-private constants `INVALID_ACCESS_TOKEN = "Invalid or expired token"`, `INVALID_REFRESH_TOKEN = "Invalid or expired refresh token"`; private `Claims parseAccessToken(String)`.
  - Test harness `AuthServiceImplTest` (fields listed in Step 1) that later tasks extend.

- [ ] **Step 1: Write the failing tests**

Append to `JwtServiceTest` (inside the class, after the last test):

```java
  @Test
  void accessAndRefreshTokensCarryDifferentTypes() {
    User user = user("a@example.com");
    String access = jwtService.generateToken(user);
    String refresh = jwtService.generateRefreshToken(user);

    assertThat(jwtService.isAccessToken(access)).isTrue();
    assertThat(jwtService.isRefreshToken(access)).isFalse();
    assertThat(jwtService.isRefreshToken(refresh)).isTrue();
    assertThat(jwtService.isAccessToken(refresh)).isFalse();
    assertThat(jwtService.parseClaims(access).get("token_type", String.class)).isEqualTo("access");
  }

  @Test
  void typeChecksReturnFalseInsteadOfThrowing() {
    assertThat(jwtService.isAccessToken("not-a-jwt")).isFalse();
    assertThat(jwtService.isRefreshToken("")).isFalse();
    assertThat(jwtService.isAccessToken(null)).isFalse();
  }

  @Test
  void tokensMintedBackToBackAreDistinct() {
    User user = user("a@example.com");

    assertThat(jwtService.generateRefreshToken(user)).isNotEqualTo(jwtService.generateRefreshToken(user));
  }
```

Create `src/test/java/com/spring/app/utils/JwtFunctionUtilTest.java`:

```java
package com.spring.app.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.JwtService;

import io.jsonwebtoken.JwtException;

class JwtFunctionUtilTest {

  private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  private final JwtService jwtService = new JwtService(properties());
  private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
  private final RedisServiceInterface redisService = mock(RedisServiceInterface.class);
  private final JwtFunctionUtil jwtFunction = new JwtFunctionUtil(jwtService, userDetailsService, redisService);
  private final MockHttpServletRequest request = new MockHttpServletRequest();

  private static JwtProperties properties() {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(SECRET);
    return properties;
  }

  private static User user(EUserStatus status) {
    User user = User.builder().email("user@example.com").password("irrelevant").status(status).build();
    user.setId(UUID.randomUUID());
    return user;
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void activeUserWithAnAccessTokenIsAuthenticated() {
    User user = user(EUserStatus.ACTIVE);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);

    jwtFunction.authenticateAccessToken(jwtService.generateToken(user), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
  }

  @Test
  void refreshTokenDoesNotAuthenticate() {
    jwtFunction.authenticateAccessToken(jwtService.generateRefreshToken(user(EUserStatus.ACTIVE)), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verifyNoInteractions(userDetailsService);
  }

  @Test
  void blacklistedTokenDoesNotAuthenticate() {
    User user = user(EUserStatus.ACTIVE);
    String token = jwtService.generateToken(user);
    when(redisService.isTokenBlacklisted(token)).thenReturn(true);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);

    jwtFunction.authenticateAccessToken(token, request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void nonActiveUserDoesNotAuthenticate() {
    User user = user(EUserStatus.INACTIVE);
    when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(user);

    jwtFunction.authenticateAccessToken(jwtService.generateToken(user), request);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void malformedTokenThrowsForTheFilterToHandle() {
    assertThatThrownBy(() -> jwtFunction.authenticateAccessToken("not-a-jwt", request))
        .isInstanceOf(JwtException.class);
  }
}
```

Create `src/test/java/com/spring/app/filter/JwtAuthenticatorFilterTest.java`:

```java
package com.spring.app.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.spring.app.utils.JwtFunctionUtil;

import io.jsonwebtoken.ExpiredJwtException;

class JwtAuthenticatorFilterTest {

  private final JwtFunctionUtil jwtFunction = mock(JwtFunctionUtil.class);
  private final JwtAuthenticatorFilter filter = new JwtAuthenticatorFilter(jwtFunction);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockFilterChain run(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain;
  }

  @Test
  void expiredTokenFallsThroughAsAnonymousInsteadOfFailingTheRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    when(jwtFunction.extractTokenFromHeader(request)).thenReturn("expired");
    when(jwtFunction.isAuthenticationNotSet()).thenReturn(true);
    doThrow(new ExpiredJwtException(null, null, "expired"))
        .when(jwtFunction).authenticateAccessToken("expired", request);

    MockFilterChain chain = run(request);

    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void unknownSubjectFallsThroughAsAnonymous() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    when(jwtFunction.extractTokenFromHeader(request)).thenReturn("token");
    when(jwtFunction.isAuthenticationNotSet()).thenReturn(true);
    doThrow(new UsernameNotFoundException("gone"))
        .when(jwtFunction).authenticateAccessToken("token", request);

    assertThat(run(request).getRequest()).isSameAs(request);
  }

  @Test
  void requestWithoutABearerTokenSkipsAuthentication() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");

    assertThat(run(request).getRequest()).isSameAs(request);
    verify(jwtFunction, never()).authenticateAccessToken(any(), any());
  }
}
```

Create `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (this is the shared harness; later tasks add fields, helpers and tests to it):

```java
package com.spring.app.modules.auth.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.mapper.AuthMapperImpl;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.AuthCacheService;
import com.spring.app.shared.services.JwtService;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.shared.services.RateLimitManagerService;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Plain Mockito: no Spring context. {@code @InjectMocks} picks the Lombok
 * constructor and fills every parameter from the {@code @Mock}/{@code @Spy}
 * fields by type, so adding a constructor dependency only needs a new field
 * here. Real collaborators (JWT, mapper, BCrypt, metrics) are {@code @Spy}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

  static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLW91dHNpZGUtdW5pdC10ZXN0cw==";

  final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Spy JwtProperties jwtProperties = jwtProperties();
  @Spy JwtService jwtService = new JwtService(jwtProperties);
  @Spy MonitoringService monitoringService = new MonitoringService(meterRegistry);
  @Spy AuthMapper authMapper = new AuthMapperImpl();
  @Spy PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

  @Mock UserRepository userRepository;
  @Mock RefreshTokenRepository refreshTokenRepository;
  @Mock AuthenticationManager authenticationManager;
  @Mock JwtFunctionUtil jwtFunction;
  @Mock OtpFunctionUtil otpFunction;
  @Mock OtpEmailService otpEmailService;
  @Mock AuthCacheService authCacheService;
  @Mock RedisServiceInterface redisService;
  @Mock RateLimitManagerService rateLimitManagerService;

  @InjectMocks AuthServiceImpl authService;

  static JwtProperties jwtProperties() {
    JwtProperties properties = new JwtProperties();
    properties.setSecretKey(SECRET);
    return properties;
  }

  static User user(String email, EUserStatus status) {
    User user = User.builder()
        .email(email)
        .password("$2a$04$irrelevantirrelevantirrelevantirrelevantirrelevantirr")
        .fullName("Test User")
        .status(status)
        .build();
    user.setId(UUID.randomUUID());
    return user;
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  // ---- Task 2: token types --------------------------------------------------

  @Test
  void refreshRejectsAnAccessToken() {
    String accessToken = jwtService.generateToken(user("active@example.com", EUserStatus.ACTIVE));

    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(accessToken)))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("Invalid or expired refresh token");
    verifyNoInteractions(refreshTokenRepository);
  }

  @Test
  void refreshRejectsAGarbledToken() {
    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto("not-a-jwt")))
        .isInstanceOf(InvalidTokenException.class);
    verifyNoInteractions(refreshTokenRepository);
  }

  @Test
  void logoutWithoutABearerTokenIsUnauthorizedNotAServerError() {
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(null);

    assertThatThrownBy(() -> authService.logout(new MockHttpServletRequest()))
        .isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void logoutWithAGarbledTokenIsUnauthorizedNotAServerError() {
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn("not-a-jwt");

    assertThatThrownBy(() -> authService.logout(new MockHttpServletRequest()))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("Invalid or expired token");
  }

  @Test
  void logoutRejectsARefreshToken() {
    String refreshToken = jwtService.generateRefreshToken(user("active@example.com", EUserStatus.ACTIVE));
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(refreshToken);

    assertThatThrownBy(() -> authService.logout(new MockHttpServletRequest()))
        .isInstanceOf(InvalidTokenException.class);
    verifyNoInteractions(redisService);
  }

  @Test
  void logoutBlacklistsTheAccessTokenAndRevokesRefreshTokens() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateToken(user);
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(token);
    when(userRepository.findByEmail("active@example.com")).thenReturn(Optional.of(user));

    authService.logout(new MockHttpServletRequest());

    verify(redisService).blacklistToken(eq(token), longThat(seconds -> seconds > 0));
    verify(refreshTokenRepository).revokeAllByUser(user);
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=JwtServiceTest,JwtFunctionUtilTest,JwtAuthenticatorFilterTest,AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: method isAccessToken`, `class InvalidTokenException`, `method authenticateAccessToken`, constructor `JwtAuthenticatorFilter(JwtFunctionUtil)`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/spring/app/modules/auth/exceptions/InvalidTokenException.java`:

```java
package com.spring.app.modules.auth.exceptions;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * A bearer or refresh token that is missing, malformed, expired, revoked or of
 * the wrong type. It is a Spring Security {@code AuthenticationException}, so
 * the exception handler answers 401 (never 500) without a dedicated mapping.
 */
public class InvalidTokenException extends BadCredentialsException {

  public InvalidTokenException(String message) {
    super(message);
  }
}
```

Replace the whole of `src/main/java/com/spring/app/shared/interfaces/JwtServiceInterface.java` with:

```java
package com.spring.app.shared.interfaces;

import java.util.UUID;
import java.util.function.Function;

import org.springframework.security.core.userdetails.UserDetails;

import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;

import io.jsonwebtoken.Claims;

public interface JwtServiceInterface {

  /** Claim naming what a token may be used for. */
  String TOKEN_TYPE_CLAIM = "token_type";
  /** Bearer token for API calls. */
  String ACCESS_TOKEN_TYPE = "access";
  /** Only accepted by {@code POST /api/v1/auth/refresh-token}. */
  String REFRESH_TOKEN_TYPE = "refresh";

  String extractUsername(String token);

  UUID extractUserId(String token);

  ERole extractRole(String token);

  EUserStatus extractUserStatus(String token);

  <T> T extractClaim(String token, Function<Claims, T> claimsResolver);

  /**
   * Verifies signature and expiry and returns the claims.
   *
   * @throws io.jsonwebtoken.JwtException  for an expired, forged or malformed token
   * @throws IllegalArgumentException      for a null or empty token
   */
  Claims parseClaims(String token);

  /** True only for a valid, unexpired token of type {@code access}. Never throws. */
  boolean isAccessToken(String token);

  /** True only for a valid, unexpired token of type {@code refresh}. Never throws. */
  boolean isRefreshToken(String token);

  String generateToken(User user);

  String generateRefreshToken(User user);

  boolean isTokenValid(String token, UserDetails userDetails);

  long getRemainingExpirationSeconds(String token);
}
```

In `src/main/java/com/spring/app/shared/services/JwtService.java`:

1. Add the import `import io.jsonwebtoken.JwtException;` next to the other `io.jsonwebtoken` imports.
2. Replace the methods `generateToken`, `generateRefreshToken` and `buildToken` with:

```java
  @Override
  public String generateToken(User user) {
    return buildToken(user, ACCESS_TOKEN_TYPE, jwtProperties.getExpiration());
  }

  @Override
  public String generateRefreshToken(User user) {
    return buildToken(user, REFRESH_TOKEN_TYPE, jwtProperties.getRefreshExpiration());
  }
```

```java
  private String buildToken(User user, String tokenType, long expiration) {
    Map<String, Object> claims = getHashObject(user);
    claims.put(TOKEN_TYPE_CLAIM, tokenType);
    long now = System.currentTimeMillis();
    return Jwts.builder()
      .claims(claims)
      // Unique per token: iat/exp have second resolution, so two tokens minted
      // for one user in the same second would otherwise be byte-identical.
      .id(UUID.randomUUID().toString())
      .subject(user.getUsername())
      .issuedAt(new Date(now))
      .expiration(new Date(now + expiration))
      .signWith(signingKey, Jwts.SIG.HS256)
      .compact();
  }
```

3. Add these methods right after `extractClaim`:

```java
  @Override
  public Claims parseClaims(String token) {
    return parser.parseSignedClaims(token).getPayload();
  }

  @Override
  public boolean isAccessToken(String token) {
    return hasType(token, ACCESS_TOKEN_TYPE);
  }

  @Override
  public boolean isRefreshToken(String token) {
    return hasType(token, REFRESH_TOKEN_TYPE);
  }
```

4. Replace `extractAllClaims` with `hasType` (and make `extractClaim` call `parseClaims`):

```java
  @Override
  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    return claimsResolver.apply(parseClaims(token));
  }
```

```java
  private boolean hasType(String token, String expectedType) {
    try {
      return expectedType.equals(parseClaims(token).get(TOKEN_TYPE_CLAIM, String.class));
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }
```

Delete the now-unused `private Claims extractAllClaims(String token)` method.

Replace the whole of `src/main/java/com/spring/app/utils/JwtFunctionUtil.java` with:

```java
package com.spring.app.utils;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtFunctionUtil {

  private final JwtServiceInterface jwtService;
  private final UserDetailsService userDetailsService;
  private final RedisServiceInterface redisService;

  /**
   * Extracts the JWT token from the Authorization header of the given HTTP
   * request.
   *
   * @param request the HTTP request containing the Authorization header
   * @return the extracted JWT token, or null if the header is not present or
   *         invalid
   */
  public String extractTokenFromHeader(HttpServletRequest request) {
    final String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      return authHeader.substring(7);
    }
    return null;
  }

  /**
   * Checks if the authentication in the security context is not set.
   *
   * @return true if the authentication is not set, false otherwise
   */
  public boolean isAuthenticationNotSet() {
    return SecurityContextHolder.getContext().getAuthentication() == null;
  }

  /**
   * Authenticates the request from a bearer access token.
   *
   * <p>
   * The context is left empty (the request stays anonymous) when the token is
   * a refresh token, has been blacklisted by logout, or belongs to an account
   * that is not ACTIVE.
   * </p>
   *
   * <p>
   * This loads the user from the database on every authenticated request. That
   * is deliberate: a status change (INACTIVE, DELETED) takes effect on the next
   * request instead of when the token expires.
   * </p>
   *
   * @throws io.jsonwebtoken.JwtException for an expired, forged or malformed token
   * @throws IllegalArgumentException      for an empty token
   * @throws org.springframework.security.core.userdetails.UsernameNotFoundException
   *                                       when the subject no longer exists
   */
  public void authenticateAccessToken(String jwt, HttpServletRequest request) {
    Claims claims = jwtService.parseClaims(jwt);
    if (!JwtServiceInterface.ACCESS_TOKEN_TYPE.equals(
        claims.get(JwtServiceInterface.TOKEN_TYPE_CLAIM, String.class))) {
      return;
    }
    if (redisService.isTokenBlacklisted(jwt)) {
      return;
    }

    UserDetails userDetails = userDetailsService.loadUserByUsername(claims.getSubject());
    if (!(userDetails instanceof User user) || user.getStatus() != EUserStatus.ACTIVE) {
      return;
    }

    UsernamePasswordAuthenticationToken authToken =
        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authToken);
  }
}
```

Replace the whole of `src/main/java/com/spring/app/filter/JwtAuthenticatorFilter.java` with:

```java
package com.spring.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.lang.NonNull;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.spring.app.utils.JwtFunctionUtil;

import io.jsonwebtoken.JwtException;

import java.io.IOException;

/**
 * Authenticates a request from its bearer access token, when there is one.
 *
 * <p>
 * This filter does not know which URLs are public. It never rejects a request
 * itself: an unusable token simply leaves the request anonymous, and the
 * rules in {@code SecurityConfig} (fed by {@code WhitelistUrlConstant}) then
 * decide - 401 through {@code CustomAuthenticationEntryPoint} on a protected
 * route, normal processing on a public one.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticatorFilter extends OncePerRequestFilter {

  private final JwtFunctionUtil jwtFunction;

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    String jwt = jwtFunction.extractTokenFromHeader(request);

    if (jwt != null && jwtFunction.isAuthenticationNotSet()) {
      try {
        jwtFunction.authenticateAccessToken(jwt, request);
      } catch (JwtException | IllegalArgumentException | AuthenticationException e) {
        SecurityContextHolder.clearContext();
        log.debug("Ignoring unusable bearer token: {}", e.getClass().getSimpleName());
      }
    }

    filterChain.doFilter(request, response);
  }
}
```

In `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java`:

1. Add imports:

```java
import com.spring.app.modules.auth.exceptions.InvalidTokenException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
```

2. Add these constants as the first lines inside the class body (before `@Value("${spring.profiles.active}")`):

```java
  static final String INVALID_ACCESS_TOKEN = "Invalid or expired token";
  static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";
```

3. Replace the whole `logout` method (HEAD lines 174-196) with:

```java
  @Override
  @Transactional
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = jwtFunction.extractTokenFromHeader(request);
    if (token == null) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    Claims claims = parseAccessToken(token);
    User user = userRepository.findByEmail(claims.getSubject())
        .orElseThrow(() -> new InvalidTokenException(INVALID_ACCESS_TOKEN));

    long remainingSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
    if (remainingSeconds > 0) {
      redisService.blacklistToken(token, remainingSeconds);
    }
    refreshTokenRepository.revokeAllByUser(user);

    SecurityContextHolder.clearContext();
    log.info("User {} logged out", user.getId());
    return ResponseBuilder.success("User logged out successfully");
  }
```

4. In `refreshToken` (HEAD lines 198-218), insert as the first statement of the method body:

```java
    if (!jwtService.isRefreshToken(dto.refreshToken())) {
      throw new InvalidTokenException(INVALID_REFRESH_TOKEN);
    }
```

5. Add this private method directly above `private void validateEmailUniqueness`:

```java
  /** Parses a bearer token for logout. Anything unusable is a 401, never a 500. */
  private Claims parseAccessToken(String token) {
    Claims claims;
    try {
      claims = jwtService.parseClaims(token);
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    if (!JwtServiceInterface.ACCESS_TOKEN_TYPE.equals(
        claims.get(JwtServiceInterface.TOKEN_TYPE_CLAIM, String.class))) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    return claims;
  }
```

- [ ] **Step 4: Confirm nothing else called the removed method**

Run: `grep -rn "authenticateUser\|extractAllClaims\|getSignInKey" src/main/java`
Expected: no output.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=JwtServiceTest,JwtFunctionUtilTest,JwtAuthenticatorFilterTest,AuthServiceImplTest,BaseSpringApplicationTests`
Expected: exit 0, no failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/shared/interfaces/JwtServiceInterface.java \
  src/main/java/com/spring/app/shared/services/JwtService.java \
  src/main/java/com/spring/app/utils/JwtFunctionUtil.java \
  src/main/java/com/spring/app/filter/JwtAuthenticatorFilter.java \
  src/main/java/com/spring/app/modules/auth/exceptions/InvalidTokenException.java \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/test/java/com/spring/app/shared/services/JwtServiceTest.java \
  src/test/java/com/spring/app/utils/JwtFunctionUtilTest.java \
  src/test/java/com/spring/app/filter/JwtAuthenticatorFilterTest.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(jwt): type access and refresh tokens; bad bearer tokens are 401

Tokens carry token_type (access|refresh) and a jti. The JWT filter only
accepts access tokens of ACTIVE, non-blacklisted users and no longer
throws on expired or garbled tokens: the request stays anonymous and the
security rules answer 401. refresh-token accepts only refresh tokens;
logout turns an unusable token into InvalidTokenException (401).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Status checks and account enumeration

Covers spec: "**refreshToken** doesn't check user status"; "**AuthServiceImpl.java:259** uniqueness check skips DELETED users but `uk_users_email` covers all rows → re-registration 500 (and OTP already sent) ... never hit the constraint"; "**Account enumeration**: login on PENDING/INACTIVE returns 'User with email X is not active' before password check (`ApplicationConfig.java:45-46` ...; post-auth check at 89-95 is unreachable). resend-otp/activate/reset-password return 404 'User not found with email'. Make responses uniform; check status only after password matches."

**Files:**
- Modify: `src/main/java/com/spring/app/configs/ApplicationConfig.java:39-51` (`userDetailsService`) and `:83-97` (`authenticationProvider`)
- Modify: `src/main/java/com/spring/app/exceptions/UserNotActiveException.java:1-12` (whole file)
- Modify: `src/main/java/com/spring/app/modules/auth/repositories/UserRepository.java:17` (add one method after it)
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (whole file)
- Test: `src/test/java/com/spring/app/configs/ApplicationConfigTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (extend)

**Interfaces:**
- Consumes: `InvalidTokenException`, `JwtServiceInterface#isRefreshToken`, `parseAccessToken` (Task 2); `AuthCacheService#updateCachedUser(User)` (P1: evicts the `userProfile` DTO).
- Produces:
  - `UserRepository#existsByEmail(String): boolean`.
  - `UserNotActiveException extends BadRequestException`, constructors `(String)` and `(String, Throwable)`.
  - `ApplicationConfig`: `userDetailsService()` returns any non-DELETED user (no status check); `authenticationProvider()` throws `UserNotActiveException("Account is not active")` only after the password matched; unknown e-mail/DELETED/wrong password all → `BadCredentialsException`.
  - `AuthServiceImpl` constants `INVALID_CREDENTIALS = "Invalid email or password"`, `INVALID_OTP = "Invalid or expired OTP"`, `REGISTRATION_REJECTED = "This email cannot be registered"`; `register` uses `existsByEmail` + `saveAndFlush`.
  - Test helpers in `AuthServiceImplTest`: `givenUser(User)`, `givenStoredRefreshToken(String, User)`, `registerDto(String)`, `savedUser()`, `login(String, String)`, `givenAuthenticationSucceedsFor(User)`, `data(ResponseEntity<?>)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/spring/app/configs/ApplicationConfigTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

/**
 * Drives the real DaoAuthenticationProvider built by ApplicationConfig, with a
 * mocked repository and real BCrypt.
 */
class ApplicationConfigTest {

  private static final String PASSWORD = "Str0ng!Pass";

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ApplicationConfig config = new ApplicationConfig(userRepository, mock(ApplicationAuditAware.class));
  private final PasswordEncoder encoder = config.passwordEncoder();

  private User user(String email, EUserStatus status) {
    User user = User.builder().email(email).password(encoder.encode(PASSWORD)).status(status).build();
    user.setId(UUID.randomUUID());
    when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    return user;
  }

  private Authentication authenticate(String email, String password) {
    AuthenticationProvider provider = config.authenticationProvider();
    return provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
  }

  @Test
  void wrongPasswordOnAPendingAccountLooksLikeAnyBadLogin() {
    user("pending@example.com", EUserStatus.PENDING);

    assertThatThrownBy(() -> authenticate("pending@example.com", "Wr0ng!Pass"))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void unknownEmailLooksLikeAWrongPassword() {
    when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authenticate("nobody@example.com", PASSWORD))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void deletedAccountLooksLikeAWrongPasswordEvenWithTheRightPassword() {
    user("deleted@example.com", EUserStatus.DELETED);

    assertThatThrownBy(() -> authenticate("deleted@example.com", PASSWORD))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void correctPasswordOnAPendingAccountReportsNotActiveWithoutTheEmail() {
    user("pending@example.com", EUserStatus.PENDING);

    assertThatThrownBy(() -> authenticate("pending@example.com", PASSWORD))
        .isInstanceOf(UserNotActiveException.class)
        .hasMessage("Account is not active");
  }

  @Test
  void activeAccountAuthenticates() {
    User user = user("active@example.com", EUserStatus.ACTIVE);

    assertThat(authenticate("active@example.com", PASSWORD).getPrincipal()).isSameAs(user);
  }
}
```

In `AuthServiceImplTest`:

1. Add imports:

```java
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ConflictException;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.auth.entities.RefreshToken;
```

2. Add below `clearContext()`:

```java
  @BeforeEach
  void setUp() {
    // AuthServiceImpl reads spring.profiles.active through @Value until Task 5.
    ReflectionTestUtils.setField(authService, "activeProfile", "test");
  }

  private void givenUser(User user) {
    when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
  }

  private RefreshToken givenStoredRefreshToken(String token, User owner) {
    RefreshToken stored = RefreshToken.builder()
        .token(token)
        .user(owner)
        .expiryDate(Instant.now().plusSeconds(3600))
        .build();
    when(refreshTokenRepository.findByToken(token)).thenReturn(Optional.of(stored));
    return stored;
  }

  static RegisterRequestDto registerDto(String email) {
    return new RegisterRequestDto(email, "Str0ng!Pass", "New User", "+84 912 345 678", "1 Main St");
  }

  private User savedUser() {
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).saveAndFlush(captor.capture());
    return captor.getValue();
  }

  private ResponseEntity<?> login(String email, String password) {
    return authService.login(new LoginRequestDto(email, password));
  }

  private void givenAuthenticationSucceedsFor(User user) {
    when(authenticationManager.authenticate(any()))
        .thenReturn(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
  }

  private static Object data(ResponseEntity<?> response) {
    return ((BaseResponse<?>) response.getBody()).getData();
  }
```

3. Append these tests at the end of the class:

```java
  // ---- Task 3: status checks and enumeration -----------------------------------

  @Test
  void registerRejectsAnEmailHeldByADeletedAccount() {
    when(userRepository.existsByEmail("gone@example.com")).thenReturn(true);

    assertThatThrownBy(() -> authService.register(registerDto("gone@example.com")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("This email cannot be registered");
    verify(userRepository, never()).saveAndFlush(any());
    verifyNoInteractions(otpEmailService);
  }

  @Test
  void registerTurnsAConcurrentDuplicateIntoAConflictAndSendsNoOtp() {
    when(userRepository.saveAndFlush(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("uk_users_email"));

    assertThatThrownBy(() -> authService.register(registerDto("race@example.com")))
        .isInstanceOf(ConflictException.class)
        .hasMessage("This email cannot be registered");
    verifyNoInteractions(otpEmailService);
  }

  @Test
  void registerSendsTheOtpAfterTheUserIsSaved() {
    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.PENDING);
    verify(otpEmailService).sendOtpEmailAsync("new@example.com");
  }

  @Test
  void resendOtpAnswersIdenticallyWhateverTheAccountState() {
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    givenUser(user("pending@example.com", EUserStatus.PENDING));

    Object unknown = data(authService.resendOtp("nobody@example.com"));
    Object active = data(authService.resendOtp("active@example.com"));
    Object pending = data(authService.resendOtp("pending@example.com"));

    assertThat(unknown).isEqualTo(active).isEqualTo(pending);
    verify(otpEmailService).sendOtpEmailAsync("pending@example.com");
    verify(otpEmailService, never()).sendOtpEmailAsync("active@example.com");
    verify(otpEmailService, never()).sendOtpEmailAsync("nobody@example.com");
  }

  @Test
  void forgotPasswordAnswersIdenticallyAndOnlyMailsActiveAccounts() {
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    givenUser(user("pending@example.com", EUserStatus.PENDING));

    Object unknown = data(authService.forgotPassword("nobody@example.com"));
    Object active = data(authService.forgotPassword("active@example.com"));
    Object pending = data(authService.forgotPassword("pending@example.com"));

    assertThat(unknown).isEqualTo(active).isEqualTo(pending);
    verify(otpEmailService).sendOtpEmailAsync("active@example.com");
    verify(otpEmailService, never()).sendOtpEmailAsync("pending@example.com");
  }

  @Test
  void activationFailsIdenticallyForUnknownEmailWrongStatusAndWrongOtp() {
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    givenUser(user("pending@example.com", EUserStatus.PENDING));
    when(otpFunction.validateOtp("nobody@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("active@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("pending@example.com", "123456")).thenReturn(false);

    for (String email : List.of("nobody@example.com", "active@example.com", "pending@example.com")) {
      assertThatThrownBy(() -> authService.activeAccount(new ActiveAccountRequestDto(email, "123456")))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("Invalid or expired OTP");
    }
    verify(userRepository, never()).save(any());
  }

  @Test
  void activationActivatesAPendingAccountAndEvictsItsProfile() {
    User pending = user("pending@example.com", EUserStatus.PENDING);
    givenUser(pending);
    when(otpFunction.validateOtp("pending@example.com", "123456")).thenReturn(true);

    authService.activeAccount(new ActiveAccountRequestDto("pending@example.com", "123456"));

    assertThat(pending.getStatus()).isEqualTo(EUserStatus.ACTIVE);
    verify(userRepository).save(pending);
    verify(otpFunction).removeOtp("pending@example.com");
    verify(authCacheService).updateCachedUser(pending);
  }

  @Test
  void passwordResetFailsIdenticallyForUnknownNonActiveAndWrongOtp() {
    givenUser(user("pending@example.com", EUserStatus.PENDING));
    givenUser(user("active@example.com", EUserStatus.ACTIVE));
    when(otpFunction.validateOtp("nobody@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("pending@example.com", "123456")).thenReturn(true);
    when(otpFunction.validateOtp("active@example.com", "123456")).thenReturn(false);

    for (String email : List.of("nobody@example.com", "pending@example.com", "active@example.com")) {
      assertThatThrownBy(() -> authService.resetPassword(
          new ResetPasswordRequestDto(email, "123456", "NewStr0ng!Pass")))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("Invalid or expired OTP");
    }
    verify(refreshTokenRepository, never()).revokeAllByUser(any());
  }

  @Test
  void refreshIsRefusedForANonActiveAccount() {
    User inactive = user("inactive@example.com", EUserStatus.INACTIVE);
    String token = jwtService.generateRefreshToken(inactive);
    givenStoredRefreshToken(token, inactive);

    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(token)))
        .isInstanceOf(InvalidTokenException.class);
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void refreshIsRefusedForARevokedOrUnknownToken() {
    User active = user("active@example.com", EUserStatus.ACTIVE);
    String revoked = jwtService.generateRefreshToken(active);
    givenStoredRefreshToken(revoked, active).setRevoked(true);
    String unknown = jwtService.generateRefreshToken(active);

    for (String token : List.of(revoked, unknown)) {
      assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(token)))
          .isInstanceOf(InvalidTokenException.class)
          .hasMessage("Invalid or expired refresh token");
    }
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  void loginGivesTheSameAnswerForUnknownEmailAndWrongPassword() {
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("Bad credentials"))
        .thenThrow(new UsernameNotFoundException("nobody"));

    assertThatThrownBy(() -> login("active@example.com", "Wr0ng!Pass"))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Invalid email or password");
    assertThatThrownBy(() -> login("nobody@example.com", "Wr0ng!Pass"))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Invalid email or password");
  }

  @Test
  void loginReportsANonActiveAccountOnlyWhenTheProviderDoes() {
    when(authenticationManager.authenticate(any())).thenThrow(new UserNotActiveException("Account is not active"));

    assertThatThrownBy(() -> login("pending@example.com", "Str0ng!Pass"))
        .isInstanceOf(UserNotActiveException.class)
        .hasMessage("Account is not active");
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=ApplicationConfigTest,AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: method existsByEmail(String)`. (After adding only the repository method, the behavioural tests fail: e.g. `wrongPasswordOnAPendingAccountLooksLikeAnyBadLogin` gets `InternalAuthenticationServiceException`, `resendOtpAnswersIdentically...` gets `ResourceNotFoundException`.)

- [ ] **Step 3: Implement**

In `src/main/java/com/spring/app/modules/auth/repositories/UserRepository.java`, add after `boolean existsByEmailAndStatusNot(String email, EUserStatus status);`:

```java

  /** Any row, DELETED included - the same scope as the uk_users_email constraint. */
  boolean existsByEmail(String email);
```

Replace the whole of `src/main/java/com/spring/app/exceptions/UserNotActiveException.java` with:

```java
package com.spring.app.exceptions;

/**
 * The credentials were correct but the account is not ACTIVE (PENDING,
 * INACTIVE). Thrown only after the password matched, so it reveals nothing to
 * someone who does not know the password. Extends {@link BadRequestException}
 * so it is a client error even before GlobalExceptionHandler maps it
 * explicitly (403).
 */
public class UserNotActiveException extends BadRequestException {

  public UserNotActiveException(String message) {
    super(message);
  }

  public UserNotActiveException(String message, Throwable cause) {
    super(message);
    initCause(cause);
  }
}
```

In `src/main/java/com/spring/app/configs/ApplicationConfig.java`, replace `userDetailsService()` (lines 34-51, Javadoc included) with:

```java
  /**
   * A UserDetailsService that fetches users from the database by email.
   *
   * <p>
   * It does NOT check the account status: DaoAuthenticationProvider loads the
   * user before it compares the password, so rejecting PENDING/INACTIVE here
   * told an anonymous caller that the account exists. The status is checked
   * after the password in {@link #authenticationProvider()}, and on every
   * bearer request in {@code JwtFunctionUtil}. DELETED accounts are treated as
   * not found.
   * </p>
   *
   * @return A UserDetailsService that can be used in a DaoAuthenticationProvider.
   */
  @Bean
  UserDetailsService userDetailsService() {
    return email -> userRepository.findByEmail(email)
        .filter(user -> user.getStatus() != EUserStatus.DELETED)
        .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
  }
```

and replace `authenticationProvider()` (lines 75-97, Javadoc included) with:

```java
  /**
   * Provides an AuthenticationProvider that uses the UserDetailsService and
   * PasswordEncoder to authenticate users.
   *
   * <p>
   * {@code hideUserNotFoundExceptions} keeps its default (true): an unknown or
   * DELETED e-mail fails with the same BadCredentialsException as a wrong
   * password. The account status is checked only after the password matched.
   * </p>
   *
   * @return An AuthenticationProvider that can be used to authenticate users.
   */
  @Bean
  AuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService());
    authProvider.setPasswordEncoder(passwordEncoder());
    authProvider.setPostAuthenticationChecks(user -> {
      if (user instanceof User appUser && appUser.getStatus() != EUserStatus.ACTIVE) {
        throw new UserNotActiveException("Account is not active");
      }
    });
    return authProvider;
  }
```

Replace the whole of `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` with:

```java
package com.spring.app.modules.auth.services.impl;

import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ConflictException;
import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.AuthCacheService;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.shared.services.RateLimitManagerService;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

  static final String INVALID_CREDENTIALS = "Invalid email or password";
  static final String INVALID_OTP = "Invalid or expired OTP";
  static final String INVALID_ACCESS_TOKEN = "Invalid or expired token";
  static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";
  static final String REGISTRATION_REJECTED = "This email cannot be registered";

  @Value("${spring.profiles.active}")
  private String activeProfile;

  private final JwtProperties jwtProperties;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtServiceInterface jwtService;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final AuthMapper authMapper;
  private final JwtFunctionUtil jwtFunction;
  private final OtpFunctionUtil otpFunction;
  private final MonitoringService monitoringService;
  private final OtpEmailService otpEmailService;
  private final AuthCacheService authCacheService;
  private final RedisServiceInterface redisService;
  private final RateLimitManagerService rateLimitManagerService;

  /**
   * Per-email throttle for OTP-sending and OTP-checking actions. Complements
   * the per-IP limit in {@link com.spring.app.filter.RateLimitFilter} so a
   * single account cannot be brute-forced / email-bombed from many IPs.
   */
  private void throttleByEmail(String email) {
    if (email != null && !email.isBlank()) {
      rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, "email:" + email.toLowerCase());
    }
  }

  @Override
  @Transactional
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();
    try {
      String emailRegister = dto.email();
      throttleByEmail(emailRegister);

      // uk_users_email covers every row, DELETED included, so any existing row
      // owns the address. Checking only non-DELETED rows let the INSERT hit the
      // constraint (500) after the OTP had already been sent.
      if (userRepository.existsByEmail(emailRegister)) {
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      boolean isDevMode = activeProfile.equals("dev");
      User newUser = User.builder().email(emailRegister).password(passwordEncoder.encode(dto.password()))
          .status(isDevMode ? EUserStatus.ACTIVE : EUserStatus.PENDING).fullName(dto.fullName()).build();
      try {
        userRepository.saveAndFlush(newUser);
      } catch (DataIntegrityViolationException e) {
        // Lost a race with a concurrent registration of the same address.
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      // Only once the row exists: an OTP for an account that failed to save is useless.
      if (!isDevMode) {
        otpEmailService.sendOtpEmailAsync(emailRegister);
      }

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();
      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> resendOtp(String email) {
    throttleByEmail(email);
    // Same answer whether the account is unknown, ACTIVE or PENDING.
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If the account is awaiting activation, a new OTP has been sent");
  }

  @Override
  @Transactional
  public ResponseEntity<?> activeAccount(ActiveAccountRequestDto dto) {
    String email = dto.email();
    throttleByEmail(email);

    // Unknown e-mail, wrong status and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> pending = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING);
    if (!otpValid || pending.isEmpty()) {
      throw new BadRequestException(INVALID_OTP);
    }

    User user = pending.get();
    user.setStatus(EUserStatus.ACTIVE);
    userRepository.save(user);
    otpFunction.removeOtp(email);
    authCacheService.updateCachedUser(user);
    return ResponseBuilder.success("Account activated successfully");
  }

  @Override
  @Transactional
  public ResponseEntity<?> login(LoginRequestDto dto) {
    Timer.Sample timer = monitoringService.startLoginTimer();
    try {
      throttleByEmail(dto.email());
      User user = authenticate(dto.email(), dto.password());

      String accessToken = jwtService.generateToken(user);
      String refreshToken = createAndStoreRefreshToken(user);

      var response = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);

      monitoringService.incrementLoginAttempts();
      return ResponseBuilder.success("Login successful", response);

    } finally {
      monitoringService.stopLoginTimer(timer);
    }
  }

  @Override
  @Transactional
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = jwtFunction.extractTokenFromHeader(request);
    if (token == null) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    Claims claims = parseAccessToken(token);
    User user = userRepository.findByEmail(claims.getSubject())
        .orElseThrow(() -> new InvalidTokenException(INVALID_ACCESS_TOKEN));

    long remainingSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
    if (remainingSeconds > 0) {
      redisService.blacklistToken(token, remainingSeconds);
    }
    refreshTokenRepository.revokeAllByUser(user);

    SecurityContextHolder.clearContext();
    log.info("User {} logged out", user.getId());
    return ResponseBuilder.success("User logged out successfully");
  }

  @Override
  @Transactional
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    String presented = dto.refreshToken();
    if (!jwtService.isRefreshToken(presented)) {
      throw new InvalidTokenException(INVALID_REFRESH_TOKEN);
    }
    RefreshToken oldToken = refreshTokenRepository.findByToken(presented)
        .filter(token -> !token.isRevoked())
        .filter(token -> token.getExpiryDate() != null && token.getExpiryDate().isAfter(Instant.now()))
        .orElseThrow(() -> new InvalidTokenException(INVALID_REFRESH_TOKEN));

    User user = oldToken.getUser();
    // A DELETED / INACTIVE / PENDING account must not keep minting tokens.
    if (user.getStatus() != EUserStatus.ACTIVE) {
      throw new InvalidTokenException(INVALID_REFRESH_TOKEN);
    }

    oldToken.setRevoked(true);
    refreshTokenRepository.save(oldToken);

    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = createAndStoreRefreshToken(user);

    var response = authMapper.newTokenToTokenResponseDto(accessToken, newRefreshToken);
    return ResponseBuilder.success("Token refreshed successfully", response);
  }

  @Override
  public ResponseEntity<?> forgotPassword(String email) {
    throttleByEmail(email);
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If your email is registered, you will receive an OTP shortly");
  }

  @Override
  @Transactional
  public ResponseEntity<?> resetPassword(ResetPasswordRequestDto dto) {
    String email = dto.email();
    throttleByEmail(email);

    // Unknown e-mail, non-ACTIVE account and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> active = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE);
    if (!otpValid || active.isEmpty()) {
      throw new BadRequestException(INVALID_OTP);
    }

    User user = active.get();
    user.setPassword(passwordEncoder.encode(dto.newPassword()));
    userRepository.save(user);
    otpFunction.removeOtp(email);
    refreshTokenRepository.revokeAllByUser(user);
    return ResponseBuilder.success("Password reset successfully");
  }

  /** Parses a bearer token for logout. Anything unusable is a 401, never a 500. */
  private Claims parseAccessToken(String token) {
    Claims claims;
    try {
      claims = jwtService.parseClaims(token);
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    if (!JwtServiceInterface.ACCESS_TOKEN_TYPE.equals(
        claims.get(JwtServiceInterface.TOKEN_TYPE_CLAIM, String.class))) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    return claims;
  }

  private User authenticate(String email, String password) {
    try {
      Authentication authenticated = authenticationManager
          .authenticate(new UsernamePasswordAuthenticationToken(email, password));
      return (User) authenticated.getPrincipal();
    } catch (AuthenticationException ex) {
      // Unknown e-mail, DELETED account and wrong password look the same.
      // UserNotActiveException is not an AuthenticationException: it is thrown
      // only after the password matched and propagates unchanged.
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }
  }

  private String createAndStoreRefreshToken(User user) {
    String refreshToken = jwtService.generateRefreshToken(user);

    RefreshToken tokenEntity = authMapper.userToRefreshToken(user, refreshToken,
        Instant.now().plusMillis(jwtProperties.getRefreshExpiration()));

    refreshTokenRepository.save(tokenEntity);
    return refreshToken;
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=ApplicationConfigTest,AuthServiceImplTest,BaseSpringApplicationTests`
Expected: exit 0, no failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/configs/ApplicationConfig.java \
  src/main/java/com/spring/app/exceptions/UserNotActiveException.java \
  src/main/java/com/spring/app/modules/auth/repositories/UserRepository.java \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/test/java/com/spring/app/configs/ApplicationConfigTest.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(auth): stop account enumeration; check status after the password

Login checks account status only after the password matched, and unknown
or DELETED e-mails fail like a wrong password. resend-otp, activate,
forgot-password and reset-password answer identically for every account
state. refresh-token refuses non-ACTIVE accounts. Registration treats any
existing row (DELETED included) as taken and never hits uk_users_email;
the OTP is sent only after the row is saved.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Auth metrics

Covers spec: "**Metrics**: `auth.login.attempts` incremented only on success (`MonitoringService.java:24`, `AuthServiceImpl.java:166`). Rename to success, add failed-login, OTP-failure, refresh, logout, OAuth2, rate-limit rejection counters." (OAuth2 is wired in Task 9, the login-lock reason in Task 8.)

**Files:**
- Modify: `src/main/java/com/spring/app/shared/services/MonitoringService.java:1-69` (whole file)
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (methods `throttleByEmail`, `activeAccount`, `login`, `logout`, `refreshToken`, `resetPassword`, `authenticate`; new `rejectRefresh`)
- Test: `src/test/java/com/spring/app/shared/services/MonitoringServiceTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (extend)

**Interfaces:**
- Consumes: Task 3's `AuthServiceImpl`.
- Produces (`MonitoringService`, constructor unchanged `MonitoringService(MeterRegistry)`):
  - `void recordLoginSuccess()` → `auth.login.success` (replaces `incrementLoginAttempts()`, which is removed)
  - `void recordLoginFailure(String reason)` → `auth.login.failed{reason}` (`bad_credentials`, `not_active`, `locked`)
  - `void incrementRegistrationAttempts()` → `auth.registration.attempts` (unchanged)
  - `void recordOtpFailure(String purpose)` → `auth.otp.failed{purpose}` (`activation`, `password_reset`)
  - `void recordTokenRefresh(boolean success)` → `auth.token.refresh{result=success|failure}`
  - `void recordLogout()` → `auth.logout`
  - `void recordOAuth2Login(boolean success)` → `auth.oauth2.login{result=success|failure}`
  - `void recordRateLimitRejection(String bucket)` → `auth.ratelimit.rejected{bucket}`
  - timers `auth.login.duration` / `auth.registration.duration` unchanged.
  - `AuthServiceImpl`: private `InvalidTokenException rejectRefresh()`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/spring/app/shared/services/MonitoringServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MonitoringServiceTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MonitoringService monitoring = new MonitoringService(registry);

  private double count(String name, String... tags) {
    Counter counter = registry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void loginSuccessHasItsOwnNameAndTheMisleadingAttemptsCounterIsGone() {
    monitoring.recordLoginSuccess();

    assertThat(count("auth.login.success")).isEqualTo(1.0);
    assertThat(registry.find("auth.login.attempts").counter()).isNull();
  }

  @Test
  void loginFailuresAreTaggedByReason() {
    monitoring.recordLoginFailure("bad_credentials");
    monitoring.recordLoginFailure("bad_credentials");
    monitoring.recordLoginFailure("locked");

    assertThat(count("auth.login.failed", "reason", "bad_credentials")).isEqualTo(2.0);
    assertThat(count("auth.login.failed", "reason", "locked")).isEqualTo(1.0);
  }

  @Test
  void otpFailuresAreTaggedByPurpose() {
    monitoring.recordOtpFailure("activation");
    monitoring.recordOtpFailure("password_reset");

    assertThat(count("auth.otp.failed", "purpose", "activation")).isEqualTo(1.0);
    assertThat(count("auth.otp.failed", "purpose", "password_reset")).isEqualTo(1.0);
  }

  @Test
  void refreshAndOAuth2AreTaggedByResult() {
    monitoring.recordTokenRefresh(true);
    monitoring.recordTokenRefresh(false);
    monitoring.recordOAuth2Login(false);

    assertThat(count("auth.token.refresh", "result", "success")).isEqualTo(1.0);
    assertThat(count("auth.token.refresh", "result", "failure")).isEqualTo(1.0);
    assertThat(count("auth.oauth2.login", "result", "failure")).isEqualTo(1.0);
  }

  @Test
  void logoutAndRateLimitRejectionsAreCounted() {
    monitoring.recordLogout();
    monitoring.recordRateLimitRejection("login");

    assertThat(count("auth.logout")).isEqualTo(1.0);
    assertThat(count("auth.ratelimit.rejected", "bucket", "login")).isEqualTo(1.0);
  }
}
```

In `AuthServiceImplTest`:

1. Add imports:

```java
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.spring.app.exceptions.RateLimitExceededException;

import io.micrometer.core.instrument.Counter;
```

2. Add helper below `data(...)`:

```java
  private double count(String name, String... tags) {
    Counter counter = meterRegistry.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }
```

3. Append tests:

```java
  // ---- Task 4: metrics ---------------------------------------------------------

  @Test
  void successfulLoginIsCountedAsSuccess() {
    givenAuthenticationSucceedsFor(user("active@example.com", EUserStatus.ACTIVE));

    login("active@example.com", "Str0ng!Pass");

    assertThat(count("auth.login.success")).isEqualTo(1.0);
    assertThat(count("auth.login.failed")).isZero();
  }

  @Test
  void failedLoginsAreCountedWithTheirReason() {
    when(authenticationManager.authenticate(any()))
        .thenThrow(new BadCredentialsException("x"))
        .thenThrow(new UserNotActiveException("Account is not active"));

    catchThrowable(() -> login("a@example.com", "Wr0ng!Pass"));
    catchThrowable(() -> login("a@example.com", "Str0ng!Pass"));

    assertThat(count("auth.login.failed", "reason", "bad_credentials")).isEqualTo(1.0);
    assertThat(count("auth.login.failed", "reason", "not_active")).isEqualTo(1.0);
    assertThat(count("auth.login.success")).isZero();
  }

  @Test
  void wrongOtpsAreCountedPerPurpose() {
    catchThrowable(() -> authService.activeAccount(new ActiveAccountRequestDto("nobody@example.com", "123456")));
    catchThrowable(() -> authService.resetPassword(
        new ResetPasswordRequestDto("nobody@example.com", "123456", "NewStr0ng!Pass")));

    assertThat(count("auth.otp.failed", "purpose", "activation")).isEqualTo(1.0);
    assertThat(count("auth.otp.failed", "purpose", "password_reset")).isEqualTo(1.0);
  }

  @Test
  void refreshOutcomesAreCounted() {
    catchThrowable(() -> authService.refreshToken(new RefreshTokenDto("not-a-jwt")));
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateRefreshToken(user);
    givenStoredRefreshToken(token, user);

    authService.refreshToken(new RefreshTokenDto(token));

    assertThat(count("auth.token.refresh", "result", "failure")).isEqualTo(1.0);
    assertThat(count("auth.token.refresh", "result", "success")).isEqualTo(1.0);
  }

  @Test
  void logoutIsCounted() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    when(jwtFunction.extractTokenFromHeader(any())).thenReturn(jwtService.generateToken(user));
    givenUser(user);

    authService.logout(new MockHttpServletRequest());

    assertThat(count("auth.logout")).isEqualTo(1.0);
  }

  @Test
  void perEmailThrottleRejectionIsCounted() {
    doThrow(new RateLimitExceededException("slow down"))
        .when(rateLimitManagerService).checkRateLimitAndThrow(any(), anyString());

    assertThatThrownBy(() -> authService.forgotPassword("user@example.com"))
        .isInstanceOf(RateLimitExceededException.class);
    assertThat(count("auth.ratelimit.rejected", "bucket", "auth_email")).isEqualTo(1.0);
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=MonitoringServiceTest,AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: method recordLoginSuccess()` (and the other new methods).

- [ ] **Step 3: Implement**

Replace the whole of `src/main/java/com/spring/app/shared/services/MonitoringService.java` with:

```java
package com.spring.app.shared.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Authentication metrics. Prometheus appends {@code _total} to counters.
 * <ul>
 * <li>{@code auth.login.success}</li>
 * <li>{@code auth.login.failed{reason=bad_credentials|not_active|locked}}</li>
 * <li>{@code auth.registration.attempts} - successful registrations</li>
 * <li>{@code auth.otp.failed{purpose=activation|password_reset}}</li>
 * <li>{@code auth.token.refresh{result=success|failure}}</li>
 * <li>{@code auth.logout}</li>
 * <li>{@code auth.oauth2.login{result=success|failure}}</li>
 * <li>{@code auth.ratelimit.rejected{bucket}}</li>
 * <li>timers {@code auth.login.duration}, {@code auth.registration.duration}</li>
 * </ul>
 */
@Service
@Slf4j
public class MonitoringService {

  private static final String RESULT = "result";

  private final MeterRegistry meterRegistry;

  private final Counter loginSuccessCounter;
  private final Counter registrationCounter;
  private final Counter logoutCounter;
  private final Timer loginTimer;
  private final Timer registrationTimer;

  public MonitoringService(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.loginSuccessCounter = Counter.builder("auth.login.success")
        .description("Successful logins")
        .register(meterRegistry);
    this.registrationCounter = Counter.builder("auth.registration.attempts")
        .description("Successful registrations")
        .register(meterRegistry);
    this.logoutCounter = Counter.builder("auth.logout")
        .description("Logouts")
        .register(meterRegistry);
    this.loginTimer = Timer.builder("auth.login.duration")
        .description("Login request duration")
        .register(meterRegistry);
    this.registrationTimer = Timer.builder("auth.registration.duration")
        .description("Registration request duration")
        .register(meterRegistry);
  }

  public void recordLoginSuccess() {
    loginSuccessCounter.increment();
  }

  public void recordLoginFailure(String reason) {
    tagged("auth.login.failed", "Failed logins", "reason", reason).increment();
  }

  public void incrementRegistrationAttempts() {
    registrationCounter.increment();
  }

  public void recordOtpFailure(String purpose) {
    tagged("auth.otp.failed", "Rejected OTPs", "purpose", purpose).increment();
  }

  public void recordTokenRefresh(boolean success) {
    tagged("auth.token.refresh", "Refresh-token exchanges", RESULT, outcome(success)).increment();
  }

  public void recordLogout() {
    logoutCounter.increment();
  }

  public void recordOAuth2Login(boolean success) {
    tagged("auth.oauth2.login", "OAuth2 logins", RESULT, outcome(success)).increment();
  }

  public void recordRateLimitRejection(String bucket) {
    tagged("auth.ratelimit.rejected", "Requests refused by a rate limit", "bucket", bucket).increment();
  }

  public Timer.Sample startLoginTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopLoginTimer(Timer.Sample sample) {
    sample.stop(loginTimer);
  }

  public Timer.Sample startRegistrationTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopRegistrationTimer(Timer.Sample sample) {
    sample.stop(registrationTimer);
  }

  public void recordLoginDuration(long duration, TimeUnit unit) {
    loginTimer.record(duration, unit);
  }

  public void recordRegistrationDuration(long duration, TimeUnit unit) {
    registrationTimer.record(duration, unit);
  }

  /** Micrometer returns the already-registered counter for the same name and tags. */
  private Counter tagged(String name, String description, String tagKey, String tagValue) {
    return Counter.builder(name).description(description).tag(tagKey, tagValue).register(meterRegistry);
  }

  private static String outcome(boolean success) {
    return success ? "success" : "failure";
  }
}
```

In `AuthServiceImpl.java`:

1. Add imports:

```java
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.exceptions.UserNotActiveException;
```

2. Replace `throttleByEmail` (keep its Javadoc) with:

```java
  private void throttleByEmail(String email) {
    if (email == null || email.isBlank()) {
      return;
    }
    try {
      rateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, "email:" + email.toLowerCase());
    } catch (RateLimitExceededException e) {
      monitoringService.recordRateLimitRejection("auth_email");
      throw e;
    }
  }
```

3. In `activeAccount`, replace the failure block with:

```java
    if (!otpValid || pending.isEmpty()) {
      monitoringService.recordOtpFailure("activation");
      throw new BadRequestException(INVALID_OTP);
    }
```

4. In `resetPassword`, replace the failure block with:

```java
    if (!otpValid || active.isEmpty()) {
      monitoringService.recordOtpFailure("password_reset");
      throw new BadRequestException(INVALID_OTP);
    }
```

5. In `login`, replace `monitoringService.incrementLoginAttempts();` with `monitoringService.recordLoginSuccess();`.

6. In `logout`, insert `monitoringService.recordLogout();` on the line before `log.info("User {} logged out", user.getId());`.

7. Replace the whole `refreshToken` method with:

```java
  @Override
  @Transactional
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    String presented = dto.refreshToken();
    if (!jwtService.isRefreshToken(presented)) {
      throw rejectRefresh();
    }
    RefreshToken oldToken = refreshTokenRepository.findByToken(presented)
        .filter(token -> !token.isRevoked())
        .filter(token -> token.getExpiryDate() != null && token.getExpiryDate().isAfter(Instant.now()))
        .orElseThrow(this::rejectRefresh);

    User user = oldToken.getUser();
    // A DELETED / INACTIVE / PENDING account must not keep minting tokens.
    if (user.getStatus() != EUserStatus.ACTIVE) {
      throw rejectRefresh();
    }

    oldToken.setRevoked(true);
    refreshTokenRepository.save(oldToken);

    String accessToken = jwtService.generateToken(user);
    String newRefreshToken = createAndStoreRefreshToken(user);

    var response = authMapper.newTokenToTokenResponseDto(accessToken, newRefreshToken);
    monitoringService.recordTokenRefresh(true);
    return ResponseBuilder.success("Token refreshed successfully", response);
  }
```

8. Add directly below `parseAccessToken`:

```java
  private InvalidTokenException rejectRefresh() {
    monitoringService.recordTokenRefresh(false);
    return new InvalidTokenException(INVALID_REFRESH_TOKEN);
  }
```

9. Replace `authenticate` with:

```java
  private User authenticate(String email, String password) {
    try {
      Authentication authenticated = authenticationManager
          .authenticate(new UsernamePasswordAuthenticationToken(email, password));
      return (User) authenticated.getPrincipal();
    } catch (UserNotActiveException ex) {
      // Thrown only after the password matched (ApplicationConfig).
      monitoringService.recordLoginFailure("not_active");
      throw ex;
    } catch (AuthenticationException ex) {
      // Unknown e-mail, DELETED account and wrong password look the same.
      monitoringService.recordLoginFailure("bad_credentials");
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }
  }
```

- [ ] **Step 4: Confirm the old counter is gone**

Run: `grep -rn "incrementLoginAttempts\|auth.login.attempts" src/main/java`
Expected: no output.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=MonitoringServiceTest,AuthServiceImplTest`
Expected: exit 0, no failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/MonitoringService.java \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/test/java/com/spring/app/shared/services/MonitoringServiceTest.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
feat(metrics): count auth successes and failures separately

auth.login.attempts only ever counted successes; it is now
auth.login.success, next to auth.login.failed{reason},
auth.otp.failed{purpose}, auth.token.refresh{result}, auth.logout,
auth.oauth2.login{result} and auth.ratelimit.rejected{bucket}.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Make skipping e-mail verification an explicit dev-only opt-in

Covers spec: "**Dev-mode check**: `@Value("${spring.profiles.active}")` + `activeProfile.equals("dev")` skips e-mail verification; use `Environment.acceptsProfiles(Profiles.of("dev"))` and make the skip an explicit opt-in property (e.g. `application.auth.skip-email-verification`, default false) so a misconfigured deployment never silently skips it."

**Files:**
- Create: `src/main/java/com/spring/app/configs/properties/AuthProperties.java`
- Modify: `src/main/resources/application.yml:145` (insert block after this line)
- Modify: `src/test/resources/application-test.yml:41` (insert block after this line)
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (remove `@Value activeProfile`; add fields; `register`; new `isEmailVerificationSkipped`)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (extend)

**Interfaces:**
- Consumes: Task 4's `AuthServiceImpl`.
- Produces: `AuthProperties` (`@ConfigurationProperties(prefix = "application.auth", ignoreUnknownFields = false)`, Lombok `@Data`) with `boolean skipEmailVerification = false` (`isSkipEmailVerification()` / `setSkipEmailVerification(boolean)`). Tasks 8 and 9 add fields. `AuthServiceImpl` gains constructor dependencies `AuthProperties authProperties`, `Environment environment` (first two fields).

- [ ] **Step 1: Write the failing tests**

In `AuthServiceImplTest`:

1. Add imports:

```java
import org.springframework.mock.env.MockEnvironment;

import com.spring.app.configs.properties.AuthProperties;
```

2. Add fields next to the other `@Spy` fields:

```java
  @Spy AuthProperties authProperties = new AuthProperties();
  @Spy MockEnvironment environment = new MockEnvironment();
```

3. Delete the whole `@BeforeEach void setUp()` method added in Task 3 (the `activeProfile` field no longer exists after this task), and delete the now-unused import `org.springframework.test.util.ReflectionTestUtils` and `org.junit.jupiter.api.BeforeEach` if nothing else uses it.

4. Append tests:

```java
  // ---- Task 5: e-mail verification opt-in ---------------------------------------

  @Test
  void registrationRequiresEmailVerificationByDefaultEvenInDev() {
    environment.setActiveProfiles("dev");

    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.PENDING);
    verify(otpEmailService).sendOtpEmailAsync("new@example.com");
  }

  @Test
  void skippingVerificationNeedsTheFlagAndTheDevProfile() {
    authProperties.setSkipEmailVerification(true);
    environment.setActiveProfiles("dev");

    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.ACTIVE);
    verifyNoInteractions(otpEmailService);
  }

  @Test
  void theFlagIsIgnoredOutsideTheDevProfile() {
    authProperties.setSkipEmailVerification(true);
    environment.setActiveProfiles("prod");

    authService.register(registerDto("new@example.com"));

    assertThat(savedUser().getStatus()).isEqualTo(EUserStatus.PENDING);
    verify(otpEmailService).sendOtpEmailAsync("new@example.com");
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: class AuthProperties`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/spring/app/configs/properties/AuthProperties.java`:

```java
package com.spring.app.configs.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/**
 * Authentication behaviour, bound from {@code application.auth.*}.
 *
 * <p>
 * Strict binding: a typo'd key under {@code application.auth} fails startup.
 * </p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "application.auth", ignoreUnknownFields = false)
public class AuthProperties {

  /**
   * Register new accounts as ACTIVE without the e-mail OTP step. A local-dev
   * convenience only: honoured solely while the {@code dev} profile is active
   * as well, so a deployment that inherits the flag by mistake still verifies
   * e-mail.
   */
  private boolean skipEmailVerification = false;
}
```

In `src/main/resources/application.yml`, insert after line 145 (`  oauth2-allowed-email-domains: ${OAUTH2_ALLOWED_EMAIL_DOMAINS:}`):

```yaml

  # Authentication behaviour (configs/properties/AuthProperties.java).
  auth:
    # Dev convenience: new accounts start ACTIVE, without the e-mail OTP. Only
    # honoured while the dev profile is ALSO active; ignored (with a WARN) in
    # every other profile.
    skip-email-verification: ${AUTH_SKIP_EMAIL_VERIFICATION:false}
```

In `src/test/resources/application-test.yml`, insert after line 41 (`    fail-fast: false`):

```yaml
  auth:
    skip-email-verification: false
```

In `AuthServiceImpl.java`:

1. Remove the import `org.springframework.beans.factory.annotation.Value` and the two lines:

```java
  @Value("${spring.profiles.active}")
  private String activeProfile;
```

2. Add imports:

```java
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.spring.app.configs.properties.AuthProperties;
```

3. Make these the first two fields (above `private final JwtProperties jwtProperties;`):

```java
  private final AuthProperties authProperties;
  private final Environment environment;
```

4. Add this method directly below `throttleByEmail`:

```java
  /**
   * The OTP step is skipped only when BOTH application.auth.skip-email-verification
   * is true AND the dev profile is active.
   */
  private boolean isEmailVerificationSkipped() {
    if (!authProperties.isSkipEmailVerification()) {
      return false;
    }
    if (environment.acceptsProfiles(Profiles.of("dev"))) {
      return true;
    }
    log.warn("application.auth.skip-email-verification=true is ignored: the dev profile is not active");
    return false;
  }
```

5. Replace the whole `register` method with:

```java
  @Override
  @Transactional
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();
    try {
      String emailRegister = dto.email();
      throttleByEmail(emailRegister);

      // uk_users_email covers every row, DELETED included, so any existing row
      // owns the address. Checking only non-DELETED rows let the INSERT hit the
      // constraint (500) after the OTP had already been sent.
      if (userRepository.existsByEmail(emailRegister)) {
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      boolean skipVerification = isEmailVerificationSkipped();
      User newUser = User.builder().email(emailRegister).password(passwordEncoder.encode(dto.password()))
          .status(skipVerification ? EUserStatus.ACTIVE : EUserStatus.PENDING).fullName(dto.fullName()).build();
      try {
        userRepository.saveAndFlush(newUser);
      } catch (DataIntegrityViolationException e) {
        // Lost a race with a concurrent registration of the same address.
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      // Only once the row exists: an OTP for an account that failed to save is useless.
      if (!skipVerification) {
        otpEmailService.sendOtpEmailAsync(emailRegister);
      }

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();
      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }
```

- [ ] **Step 4: Confirm the profile check is gone from Java code**

Run: `grep -rn "spring.profiles.active\|activeProfile" src/main/java`
Expected: no output.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=AuthServiceImplTest,BaseSpringApplicationTests`
Expected: exit 0, no failures (`contextLoads` proves `application.auth` binds strictly with the test override).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/configs/properties/AuthProperties.java \
  src/main/resources/application.yml src/test/resources/application-test.yml \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(auth): skipping e-mail verification is an explicit dev-only opt-in

Registration used to skip the OTP whenever spring.profiles.active was
the string "dev". It now needs application.auth.skip-email-verification
(default false, AUTH_SKIP_EMAIL_VERIFICATION) AND an active dev profile
(Environment.acceptsProfiles).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: DTO validation and e-mail normalisation

Covers spec: "**Passwords > 72 bytes** → BCrypt throws → 500. Add max length (`@Size(max=72)` bytes-aware validator) on register/reset. Remove `@StrongPassword` from `LoginRequestDto`"; "**DTO validation**: full-name regex `^[a-zA-Z\s]+$` (RegisterRequestDto, UpdateUserDto) rejects 'Nguyễn', "O'Brien", 'Jean-Luc' → Unicode letters, space, apostrophe, hyphen, dot. `register` validates `phone`/`address` then drops them (AuthServiceImpl.java:99) — persist them. Validate `avatarUrl` as URL. Normalize e-mail (trim + lower-case) on every auth entry point."; "**Logging** ... P3 removes PII from its own logs and rate-limit WARN lines it owns" (the per-e-mail throttle identifier becomes a hash, so `RateLimitManagerService`'s WARN line no longer prints addresses).

The byte cap is also put on `LoginRequestDto.password`: at login BCrypt's `matches` hits the same 72-byte limit, so without it an over-long login password is a 500 too. It does not lock anyone out (no stored password can be longer).

**Files:**
- Create: `src/main/java/com/spring/app/common/validation/annotations/MaxUtf8Bytes.java`
- Create: `src/main/java/com/spring/app/common/validation/validators/MaxUtf8BytesValidator.java`
- Create: `src/main/java/com/spring/app/common/validation/annotations/ValidFullName.java`
- Create: `src/main/java/com/spring/app/modules/auth/utils/EmailNormalizer.java`
- Modify: `src/main/java/com/spring/app/modules/auth/dto/request/LoginRequestDto.java:1-20` (whole file)
- Modify: `src/main/java/com/spring/app/modules/auth/dto/request/RegisterRequestDto.java:1-38` (whole file)
- Modify: `src/main/java/com/spring/app/modules/auth/dto/request/ResetPasswordRequestDto.java:1-25` (whole file)
- Modify: `src/main/java/com/spring/app/modules/user/dto/requests/UpdateUserDto.java:1-44` (whole file)
- Modify: `src/main/java/com/spring/app/configs/ApplicationConfig.java` (`userDetailsService`)
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (`throttleByEmail`, `register`, `resendOtp`, `activeAccount`, `login`, `forgotPassword`, `resetPassword`)
- Test: `src/test/java/com/spring/app/common/validation/AuthDtoValidationTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/auth/utils/EmailNormalizerTest.java` (create)
- Test: `src/test/java/com/spring/app/configs/ApplicationConfigTest.java` (extend)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (extend)

**Interfaces:**
- Consumes: Task 5's `AuthServiceImpl`.
- Produces:
  - `@MaxUtf8Bytes(int value, String message)` on `CharSequence`; `null` is valid.
  - `@ValidFullName` (composed: `@Size(2..100)` + Unicode `@Pattern`); `null` is valid, combine with `@NotBlank` where required.
  - `EmailNormalizer.normalize(String): String` (`null` → `null`; else `trim().toLowerCase(Locale.ROOT)`); `EmailNormalizer.fingerprint(String): String` (64 lower-case hex chars, SHA-256 of the normalised address; `"none"` for `null`). Used by Tasks 8 and 9.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/spring/app/common/validation/AuthDtoValidationTest.java`:

```java
package com.spring.app.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.user.dto.requests.UpdateUserDto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class AuthDtoValidationTest {

  private static final String NAME_MESSAGE = "Full name may only contain letters, spaces, apostrophes, hyphens and dots";
  private static final String BYTES_MESSAGE = "Password must be at most 72 bytes";
  private static final String AVATAR_MESSAGE = "Avatar URL must be a valid http(s) URL";

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static <T> Set<String> messages(T dto) {
    return validator.validate(dto).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
  }

  private static RegisterRequestDto register(String fullName, String password) {
    return new RegisterRequestDto("user@example.com", password, fullName, null, null);
  }

  @ParameterizedTest
  @ValueSource(strings = { "Nguyễn Văn A", "O'Brien", "Jean-Luc Picard", "J. R. R. Tolkien", "Zoë Saldaña", "D’Angelo" })
  void acceptsRealWorldNames(String name) {
    assertThat(messages(register(name, "Str0ng!Pass"))).isEmpty();
    assertThat(messages(UpdateUserDto.builder().fullName(name).build())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = { "John123", "<script>", "-John", "Robert'); DROP TABLE users;--" })
  void rejectsNamesWithDigitsOrMarkup(String name) {
    assertThat(messages(register(name, "Str0ng!Pass"))).contains(NAME_MESSAGE);
    assertThat(messages(UpdateUserDto.builder().fullName(name).build())).contains(NAME_MESSAGE);
  }

  @Test
  void passwordsAreCappedAt72BytesNot72Characters() {
    String seventyTwoBytes = "Aa1!" + "a".repeat(68);
    String seventyFourBytes = "Aa1!" + "ä".repeat(35); // 39 characters, 74 UTF-8 bytes
    assertThat(seventyFourBytes).hasSize(39);

    assertThat(messages(register("Valid Name", seventyTwoBytes))).isEmpty();
    assertThat(messages(register("Valid Name", seventyFourBytes))).containsExactly(BYTES_MESSAGE);
    assertThat(messages(new ResetPasswordRequestDto("user@example.com", "123456", seventyFourBytes)))
        .containsExactly(BYTES_MESSAGE);
    assertThat(messages(new LoginRequestDto("user@example.com", "a".repeat(73)))).containsExactly(BYTES_MESSAGE);
  }

  @Test
  void loginDoesNotEnforceThePasswordPolicy() {
    assertThat(messages(new LoginRequestDto("user@example.com", "weak"))).isEmpty();
  }

  @Test
  void profileUpdateAcceptsHttpAvatars() {
    assertThat(messages(UpdateUserDto.builder().avatarUrl("https://cdn.example.com/a.png").build())).isEmpty();
    assertThat(messages(UpdateUserDto.builder().avatarUrl("http://cdn.example.com/a.png").build())).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = { "javascript:alert(1)", "ftp://example.com/a.png", "not a url" })
  void profileUpdateRejectsNonHttpAvatarUrls(String url) {
    assertThat(messages(UpdateUserDto.builder().avatarUrl(url).build())).contains(AVATAR_MESSAGE);
  }
}
```

Create `src/test/java/com/spring/app/modules/auth/utils/EmailNormalizerTest.java`:

```java
package com.spring.app.modules.auth.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

  @Test
  void trimsAndLowerCases() {
    assertThat(EmailNormalizer.normalize("  User.Name@Example.COM \t")).isEqualTo("user.name@example.com");
  }

  @Test
  void keepsNull() {
    assertThat(EmailNormalizer.normalize(null)).isNull();
  }

  @Test
  void fingerprintIsStableAcrossCasingAndHidesTheAddress() {
    String a = EmailNormalizer.fingerprint("User@Example.com");
    String b = EmailNormalizer.fingerprint(" user@example.com ");

    assertThat(a).isEqualTo(b).matches("[0-9a-f]{64}").doesNotContain("user");
    assertThat(EmailNormalizer.fingerprint("other@example.com")).isNotEqualTo(a);
  }
}
```

Append to `ApplicationConfigTest`:

```java
  @Test
  void looksUsersUpByTheNormalizedEmail() {
    user("user@example.com", EUserStatus.ACTIVE);

    assertThat(config.userDetailsService().loadUserByUsername("  User@Example.COM ").getUsername())
        .isEqualTo("user@example.com");
  }
```

In `AuthServiceImplTest`:

1. Add imports:

```java
import static org.mockito.Mockito.times;

import org.springframework.security.core.Authentication;

import com.spring.app.enums.ERateLimitEndpoint;
```

2. Append tests:

```java
  // ---- Task 6: normalisation and persisted fields --------------------------------

  @Test
  void registerStoresTheNormalizedEmailAndKeepsPhoneAndAddress() {
    authService.register(new RegisterRequestDto(
        "  New.User@Example.COM ", "Str0ng!Pass", "Nguyễn Văn A", "+84 912 345 678", "1 Main St"));

    User saved = savedUser();
    assertThat(saved.getEmail()).isEqualTo("new.user@example.com");
    assertThat(saved.getFullName()).isEqualTo("Nguyễn Văn A");
    assertThat(saved.getPhone()).isEqualTo("+84 912 345 678");
    assertThat(saved.getAddress()).isEqualTo("1 Main St");
    verify(userRepository).existsByEmail("new.user@example.com");
    verify(otpEmailService).sendOtpEmailAsync("new.user@example.com");
  }

  @Test
  void loginAuthenticatesWithTheNormalizedEmail() {
    givenAuthenticationSucceedsFor(user("user@example.com", EUserStatus.ACTIVE));

    login(" User@Example.COM ", "Str0ng!Pass");

    ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
    verify(authenticationManager).authenticate(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("user@example.com");
  }

  @Test
  void otpFlowsUseTheNormalizedEmail() {
    authService.resendOtp(" User@Example.COM ");
    authService.forgotPassword("USER@example.com");
    catchThrowable(() -> authService.activeAccount(new ActiveAccountRequestDto("User@Example.com", "123456")));
    catchThrowable(() -> authService.resetPassword(
        new ResetPasswordRequestDto("User@Example.com", "123456", "NewStr0ng!Pass")));

    verify(userRepository, times(4)).findByEmail("user@example.com");
    verify(otpFunction, times(2)).validateOtp("user@example.com", "123456");
  }

  @Test
  void perEmailThrottleKeyIsAHashNotTheAddress() {
    authService.forgotPassword("user@example.com");
    authService.forgotPassword("USER@Example.com");

    ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
    verify(rateLimitManagerService, times(2)).checkRateLimitAndThrow(eq(ERateLimitEndpoint.AUTH), ids.capture());
    assertThat(ids.getAllValues().get(0))
        .startsWith("email:")
        .doesNotContain("user@example.com")
        .isEqualTo(ids.getAllValues().get(1));
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=AuthDtoValidationTest,EmailNormalizerTest,ApplicationConfigTest,AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: class EmailNormalizer`. (With only that class added: `acceptsRealWorldNames` fails for "Nguyễn Văn A", `passwordsAreCappedAt72Bytes...` finds no violation, `loginDoesNotEnforceThePasswordPolicy` reports the StrongPassword message, `registerStoresTheNormalizedEmail...` sees the raw e-mail and null phone.)

- [ ] **Step 3: Implement the constraints and the normaliser**

Create `src/main/java/com/spring/app/common/validation/annotations/MaxUtf8Bytes.java`:

```java
package com.spring.app.common.validation.annotations;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

import com.spring.app.common.validation.validators.MaxUtf8BytesValidator;

/**
 * Caps a string by its UTF-8 byte length rather than its character count.
 * BCrypt only accepts 72 bytes, and one character can take up to 4 bytes, so
 * {@code @Size(max = 72)} would let a 40-character password with accents
 * through and make the encoder throw (500). {@code null} is valid.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

  int value();

  String message() default "must be at most {value} bytes when UTF-8 encoded";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
```

Create `src/main/java/com/spring/app/common/validation/validators/MaxUtf8BytesValidator.java`:

```java
package com.spring.app.common.validation.validators;

import java.nio.charset.StandardCharsets;

import com.spring.app.common.validation.annotations.MaxUtf8Bytes;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, CharSequence> {

  private int maxBytes;

  @Override
  public void initialize(MaxUtf8Bytes annotation) {
    this.maxBytes = annotation.value();
  }

  @Override
  public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
    return value == null || value.toString().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
  }
}
```

Create `src/main/java/com/spring/app/common/validation/annotations/ValidFullName.java`:

```java
package com.spring.app.common.validation.annotations;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.lang.annotation.*;

/**
 * A person's name: starts with a letter, then any Unicode letters (and
 * combining marks), spaces, apostrophes (' and U+2019), hyphens and dots.
 * Accepts "Nguyễn Văn A", "O'Brien", "Jean-Luc", "J. R. R. Tolkien".
 * {@code null} is valid; add {@code @NotBlank} where the name is required.
 */
@Documented
@Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
@Pattern(regexp = "^[\\p{L}\\p{M}][\\p{L}\\p{M} '\\u2019.\\-]*$",
    message = "Full name may only contain letters, spaces, apostrophes, hyphens and dots")
@Constraint(validatedBy = {})
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidFullName {

  String message() default "Full name is not valid";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
```

Create `src/main/java/com/spring/app/modules/auth/utils/EmailNormalizer.java`:

```java
package com.spring.app.modules.auth.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Canonical form of an e-mail address for every auth entry point, so that
 * lookups, OTP keys and throttle keys agree whatever casing the client sent.
 */
public final class EmailNormalizer {

  private EmailNormalizer() {
  }

  /** {@code null} stays {@code null}; otherwise trimmed and lower-cased (Locale.ROOT). */
  public static String normalize(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * SHA-256 (hex) of the normalised address. Used wherever an e-mail would
   * otherwise end up in a Redis key or a log line.
   */
  public static String fingerprint(String email) {
    String normalized = normalize(email);
    if (normalized == null) {
      return "none";
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
```

- [ ] **Step 4: Update the DTOs**

Replace the whole of `src/main/java/com/spring/app/modules/auth/dto/request/LoginRequestDto.java` with:

```java
package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.MaxUtf8Bytes;
import com.spring.app.common.validation.annotations.ValidEmail;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * No {@code @StrongPassword} here on purpose: the policy applies when a
 * password is set, not when it is used, so tightening it later cannot lock
 * existing users out. The byte cap matches BCrypt's 72-byte input limit.
 */
public record LoginRequestDto(
    @ValidEmail
    @Schema(description = "Email address of the user", example = "user1@gmail.com")
    String email,

    @NotBlank(message = "Password is required")
    @MaxUtf8Bytes(value = 72, message = "Password must be at most 72 bytes")
    @Schema(description = "Password of the user", example = "StrongP@ss123")
    String password

) {
}
```

Replace the whole of `src/main/java/com/spring/app/modules/auth/dto/request/RegisterRequestDto.java` with:

```java
package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.MaxUtf8Bytes;
import com.spring.app.common.validation.annotations.StrongPassword;
import com.spring.app.common.validation.annotations.ValidEmail;
import com.spring.app.common.validation.annotations.ValidFullName;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequestDto(

    @ValidEmail
    @Schema(description = "Email address of the user", example = "user1@gmail.com")
    String email,

    @NotBlank(message = "Password is required")
    @StrongPassword
    @MaxUtf8Bytes(value = 72, message = "Password must be at most 72 bytes")
    @Schema(description = "Password of the user", example = "StrongP@ss123")
    String password,

    @NotBlank(message = "Full name is required")
    @ValidFullName
    @Schema(description = "Full name of the user", example = "Nguyễn Văn A")
    String fullName,

    @Size(max = 15, message = "Phone number must be less than 15 characters")
    @Pattern(regexp = "^[+]?[0-9\\s\\-()]+$", message = "Invalid phone number format")
    @Schema(description = "Phone number of the user", example = "+1234567890")
    String phone,

    @Size(max = 255, message = "Address must be less than 255 characters")
    @Schema(description = "Address of the user", example = "123 Main St, City, Country")
    String address

) {
}
```

Replace the whole of `src/main/java/com/spring/app/modules/auth/dto/request/ResetPasswordRequestDto.java` with:

```java
package com.spring.app.modules.auth.dto.request;

import com.spring.app.common.validation.annotations.MaxUtf8Bytes;
import com.spring.app.common.validation.annotations.StrongPassword;
import com.spring.app.common.validation.annotations.ValidEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record ResetPasswordRequestDto(
  @ValidEmail
  @Schema(description = "Registered email address", example = "user@example.com")
  String email,

  @NotBlank(message = "OTP is required")
  @Size(min = 6, max = 6, message = "OTP must be exactly 6 characters")
  @Schema(description = "OTP received via email", example = "123456")
  String otp,

  @NotBlank(message = "New password is required")
  @StrongPassword
  @MaxUtf8Bytes(value = 72, message = "Password must be at most 72 bytes")
  @Schema(description = "New password", example = "NewStrongP@ss123")
  String newPassword
) {}
```

Replace the whole of `src/main/java/com/spring/app/modules/user/dto/requests/UpdateUserDto.java` with:

```java
package com.spring.app.modules.user.dto.requests;

import java.time.LocalDate;

import org.hibernate.validator.constraints.URL;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.spring.app.common.validation.annotations.ValidFullName;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
@Schema(description = "Data Transfer Object for updating user profile information")
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateUserDto(
  @ValidFullName
  @Schema(description = "Full name of user", example = "Nguyễn Văn A")
  String fullName,

  @Size(max = 15, message = "Phone number must be less than 15 characters")
  @Pattern(regexp = "^[+]?[0-9\\s\\-()]+$", message = "Invalid phone number format")
  @Schema(description = "Phone number", example = "0987654321")
  String phone,

  @Size(max = 255, message = "Avatar URL must be less than 255 characters")
  @URL(regexp = "^https?://.+", message = "Avatar URL must be a valid http(s) URL")
  @Schema(description = "Avatar URL", example = "https://example.com/avatar.jpg")
  String avatarUrl,

  @Size(max = 255, message = "Address must be less than 255 characters")
  @Schema(description = "Address", example = "123 Main St, City, Country")
  String address,

  @Past(message = "Date of birth must be in the past")
  @JsonFormat(pattern = "yyyy-MM-dd")
  @Schema(description = "Date of birth (yyyy-MM-dd)", example = "1990-01-15")
  LocalDate dateOfBirth,

  @Size(max = 512, message = "Description must be less than 512 characters")
  @Schema(description = "Description", example = "I'm a software engineer")
  String description
) {}
```

- [ ] **Step 5: Normalise at every auth entry point**

In `src/main/java/com/spring/app/configs/ApplicationConfig.java`, add `import com.spring.app.modules.auth.utils.EmailNormalizer;` and change the body of `userDetailsService()` to:

```java
    return email -> userRepository.findByEmail(EmailNormalizer.normalize(email))
        .filter(user -> user.getStatus() != EUserStatus.DELETED)
        .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
```

In `AuthServiceImpl.java`, add `import com.spring.app.modules.auth.utils.EmailNormalizer;` and replace these methods.

`throttleByEmail` (update its Javadoc too):

```java
  /**
   * Per-email throttle for OTP-sending and OTP-checking actions. Complements
   * the per-IP limit in {@link com.spring.app.filter.RateLimitFilter} so a
   * single account cannot be brute-forced / email-bombed from many IPs. The
   * identifier is a hash: it ends up in Redis keys and rate-limit log lines.
   */
  private void throttleByEmail(String email) {
    if (email == null || email.isBlank()) {
      return;
    }
    try {
      rateLimitManagerService.checkRateLimitAndThrow(
          ERateLimitEndpoint.AUTH, "email:" + EmailNormalizer.fingerprint(email));
    } catch (RateLimitExceededException e) {
      monitoringService.recordRateLimitRejection("auth_email");
      throw e;
    }
  }
```

`register`:

```java
  @Override
  @Transactional
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();
    try {
      String email = EmailNormalizer.normalize(dto.email());
      throttleByEmail(email);

      // uk_users_email covers every row, DELETED included, so any existing row
      // owns the address. Checking only non-DELETED rows let the INSERT hit the
      // constraint (500) after the OTP had already been sent.
      if (userRepository.existsByEmail(email)) {
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      boolean skipVerification = isEmailVerificationSkipped();
      User newUser = User.builder()
          .email(email)
          .password(passwordEncoder.encode(dto.password()))
          .fullName(dto.fullName())
          .phone(dto.phone())
          .address(dto.address())
          .status(skipVerification ? EUserStatus.ACTIVE : EUserStatus.PENDING)
          .build();
      try {
        userRepository.saveAndFlush(newUser);
      } catch (DataIntegrityViolationException e) {
        // Lost a race with a concurrent registration of the same address.
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      // Only once the row exists: an OTP for an account that failed to save is useless.
      if (!skipVerification) {
        otpEmailService.sendOtpEmailAsync(email);
      }

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();
      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }
```

`resendOtp`:

```java
  @Override
  public ResponseEntity<?> resendOtp(String rawEmail) {
    String email = EmailNormalizer.normalize(rawEmail);
    throttleByEmail(email);
    // Same answer whether the account is unknown, ACTIVE or PENDING.
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If the account is awaiting activation, a new OTP has been sent");
  }
```

`activeAccount` — change only its first line to:

```java
    String email = EmailNormalizer.normalize(dto.email());
```

`login`:

```java
  @Override
  @Transactional
  public ResponseEntity<?> login(LoginRequestDto dto) {
    Timer.Sample timer = monitoringService.startLoginTimer();
    try {
      String email = EmailNormalizer.normalize(dto.email());
      throttleByEmail(email);
      User user = authenticate(email, dto.password());

      String accessToken = jwtService.generateToken(user);
      String refreshToken = createAndStoreRefreshToken(user);

      var response = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);

      monitoringService.recordLoginSuccess();
      return ResponseBuilder.success("Login successful", response);

    } finally {
      monitoringService.stopLoginTimer(timer);
    }
  }
```

`forgotPassword`:

```java
  @Override
  public ResponseEntity<?> forgotPassword(String rawEmail) {
    String email = EmailNormalizer.normalize(rawEmail);
    throttleByEmail(email);
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If your email is registered, you will receive an OTP shortly");
  }
```

`resetPassword` — change only its first line to:

```java
    String email = EmailNormalizer.normalize(dto.email());
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=AuthDtoValidationTest,ValidEmailTest,EmailNormalizerTest,ApplicationConfigTest,AuthServiceImplTest`
Expected: exit 0, no failures (`ValidEmailTest` is unchanged and must stay green: normalisation happens in the service, not in the DTOs).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spring/app/common/validation \
  src/main/java/com/spring/app/modules/auth/utils/EmailNormalizer.java \
  src/main/java/com/spring/app/modules/auth/dto/request/LoginRequestDto.java \
  src/main/java/com/spring/app/modules/auth/dto/request/RegisterRequestDto.java \
  src/main/java/com/spring/app/modules/auth/dto/request/ResetPasswordRequestDto.java \
  src/main/java/com/spring/app/modules/user/dto/requests/UpdateUserDto.java \
  src/main/java/com/spring/app/configs/ApplicationConfig.java \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/test/java/com/spring/app/common/validation/AuthDtoValidationTest.java \
  src/test/java/com/spring/app/modules/auth/utils/EmailNormalizerTest.java \
  src/test/java/com/spring/app/configs/ApplicationConfigTest.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(validation): Unicode names, 72-byte passwords, normalised e-mails

Full names accept Unicode letters, apostrophes, hyphens and dots.
Passwords are capped at 72 UTF-8 bytes (BCrypt's limit) instead of
crashing the encoder; login no longer applies the strength policy.
avatarUrl must be an http(s) URL. Every auth entry point trims and
lower-cases the e-mail; register persists phone and address; the
per-e-mail throttle key is a SHA-256 so addresses stay out of logs.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Short transactions and atomic refresh-token rotation

Covers spec: "**Transactions**: `@Transactional` on `login`/`register` holds a Hikari connection through Redis INCRs, BCrypt, JWT signing → with virtual threads pool of 20 is the concurrency cap. Move throttling/authentication outside the transaction." Applied to every `AuthServiceImpl` method (activate/logout/refresh/reset held the connection over Redis and BCrypt too). Also closes the refresh replay race: two concurrent refreshes with the same token could both succeed.

**Files:**
- Modify: `src/main/java/com/spring/app/modules/auth/repositories/RefreshTokenRepository.java:1-29` (whole file)
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (remove every `@Transactional`; class Javadoc; new field; `refreshToken`; `resetPassword`)
- Test: `src/test/java/com/spring/app/modules/auth/repositories/RefreshTokenRepositoryTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (extend)

**Interfaces:**
- Consumes: Task 6's `AuthServiceImpl`; Boot's auto-configured `TransactionTemplate` bean.
- Produces:
  - `RefreshTokenRepository#revokeIfActive(String token): int` (1 = this caller revoked it, 0 = already revoked/unknown); `revokeAllByUser` and `revokeIfActive` are `@Transactional` on the repository.
  - `AuthServiceImpl` has no `@Transactional`; new last field `TransactionTemplate transactionTemplate`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/spring/app/modules/auth/repositories/RefreshTokenRepositoryTest.java`:

```java
package com.spring.app.modules.auth.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;

/**
 * JPA slice on H2 (test profile, Flyway off). ApplicationConfig is not part of
 * the slice, so the nested config switches auditing on for the NOT NULL
 * created_at / updated_at columns.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(RefreshTokenRepositoryTest.AuditingConfig.class)
class RefreshTokenRepositoryTest {

  @TestConfiguration
  @EnableJpaAuditing
  static class AuditingConfig {
  }

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private RefreshTokenRepository refreshTokenRepository;

  private User saveUser(String email, EUserStatus status) {
    return userRepository.save(User.builder().email(email).password("x").status(status).build());
  }

  @Test
  void revokeIfActiveLetsExactlyOneCallerWin() {
    User user = saveUser("u@example.com", EUserStatus.ACTIVE);
    refreshTokenRepository.save(RefreshToken.builder()
        .token("tok-1").user(user).expiryDate(Instant.now().plusSeconds(60)).build());

    assertThat(refreshTokenRepository.revokeIfActive("tok-1")).isEqualTo(1);
    assertThat(refreshTokenRepository.revokeIfActive("tok-1")).isZero();
    assertThat(refreshTokenRepository.revokeIfActive("unknown")).isZero();
    assertThat(refreshTokenRepository.findByToken("tok-1")).get().extracting(RefreshToken::isRevoked).isEqualTo(true);
  }

  @Test
  void existsByEmailSeesDeletedAccounts() {
    saveUser("gone@example.com", EUserStatus.DELETED);

    assertThat(userRepository.existsByEmail("gone@example.com")).isTrue();
    assertThat(userRepository.existsByEmail("nobody@example.com")).isFalse();
  }
}
```

In `AuthServiceImplTest`:

1. Add imports:

```java
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;

import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
```

2. Add fields (the manager must be declared before the template):

```java
  final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
  @Spy TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
```

3. In `givenStoredRefreshToken`, add before `return stored;`:

```java
    when(refreshTokenRepository.revokeIfActive(token)).thenReturn(1);
```

4. Append tests:

```java
  // ---- Task 7: transactions -------------------------------------------------------

  @Test
  void noAuthMethodHoldsATransactionAcrossRedisOrBcrypt() {
    assertThat(AuthServiceImpl.class.isAnnotationPresent(
        org.springframework.transaction.annotation.Transactional.class)).isFalse();
    for (Method method : AuthServiceImpl.class.getDeclaredMethods()) {
      assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
          .as(method.getName())
          .isFalse();
    }
  }

  @Test
  void refreshRotatesTheTokenOnce() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateRefreshToken(user);
    givenStoredRefreshToken(token, user);

    authService.refreshToken(new RefreshTokenDto(token));

    verify(refreshTokenRepository).revokeIfActive(token);
    verify(refreshTokenRepository).save(any(RefreshToken.class));
    verify(transactionManager).commit(any());
  }

  @Test
  void refreshThatLosesTheRotationRaceIsRejected() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    String token = jwtService.generateRefreshToken(user);
    givenStoredRefreshToken(token, user);
    when(refreshTokenRepository.revokeIfActive(token)).thenReturn(0);

    assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenDto(token)))
        .isInstanceOf(InvalidTokenException.class);
    verify(refreshTokenRepository, never()).save(any());
    verify(transactionManager).rollback(any());
  }

  @Test
  void passwordResetHashesBeforeOpeningTheTransaction() {
    User user = user("active@example.com", EUserStatus.ACTIVE);
    givenUser(user);
    when(otpFunction.validateOtp("active@example.com", "123456")).thenReturn(true);

    authService.resetPassword(new ResetPasswordRequestDto("active@example.com", "123456", "NewStr0ng!Pass"));

    InOrder order = inOrder(passwordEncoder, transactionManager, userRepository, refreshTokenRepository);
    order.verify(passwordEncoder).encode("NewStr0ng!Pass");
    order.verify(transactionManager).getTransaction(any());
    order.verify(userRepository).save(user);
    order.verify(refreshTokenRepository).revokeAllByUser(user);
    order.verify(transactionManager).commit(any());
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=RefreshTokenRepositoryTest,AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: method revokeIfActive(String)`. (With only the repository method added: `noAuthMethodHoldsATransaction...` fails on `register`, `refreshRotatesTheTokenOnce` never sees `revokeIfActive`.)

- [ ] **Step 3: Implement**

Replace the whole of `src/main/java/com/spring/app/modules/auth/repositories/RefreshTokenRepository.java` with:

```java
package com.spring.app.modules.auth.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByToken(String token);

  List<RefreshToken> findAllByUserAndIsRevokedFalse(User user);

  /**
   * Bulk JPQL needs a transaction; AuthServiceImpl no longer opens one around
   * a whole request, so the method brings its own (or joins the caller's).
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE RefreshToken t SET t.isRevoked = true WHERE t.user = :user AND t.isRevoked = false")
  void revokeAllByUser(@Param("user") User user);

  /**
   * Revokes one token only if it is still active, in a single conditional
   * UPDATE. Returns 1 for the caller that revoked it and 0 for everyone else,
   * so two concurrent refreshes with the same token cannot both succeed.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE RefreshToken t SET t.isRevoked = true WHERE t.token = :token AND t.isRevoked = false")
  int revokeIfActive(@Param("token") String token);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM RefreshToken t WHERE t.expiryDate < :now OR t.isRevoked = true")
  void deleteExpiredOrRevokedTokens(@Param("now") Instant now);
}
```

In `AuthServiceImpl.java`:

1. Delete every `@Transactional` annotation line (on `register`, `activeAccount`, `login`, `logout`, `refreshToken`, `resetPassword`) and the import `org.springframework.transaction.annotation.Transactional`.
2. Add `import org.springframework.transaction.support.TransactionTemplate;`.
3. Add as the LAST field (after `private final RateLimitManagerService rateLimitManagerService;`):

```java
  private final TransactionTemplate transactionTemplate;
```

4. Add this Javadoc directly above `@Service`:

```java
/**
 * Authentication use cases.
 *
 * <p>
 * <b>Transactions:</b> no method here is {@code @Transactional}. An open
 * transaction pins a Hikari connection, and with virtual threads the pool
 * (not Tomcat's thread count) is the concurrency cap - so BCrypt, JWT signing
 * and Redis round-trips must never run inside one. A single write relies on
 * the repository's own transaction; a step with several writes wraps only
 * those writes in {@link TransactionTemplate}.
 * </p>
 */
```

5. Replace the whole `refreshToken` method with:

```java
  @Override
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    String presented = dto.refreshToken();
    if (!jwtService.isRefreshToken(presented)) {
      throw rejectRefresh();
    }
    RefreshToken stored = refreshTokenRepository.findByToken(presented)
        .filter(token -> !token.isRevoked())
        .filter(token -> token.getExpiryDate() != null && token.getExpiryDate().isAfter(Instant.now()))
        .orElseThrow(this::rejectRefresh);

    User user = stored.getUser();
    // A DELETED / INACTIVE / PENDING account must not keep minting tokens.
    if (user.getStatus() != EUserStatus.ACTIVE) {
      throw rejectRefresh();
    }

    TokenResponseDto tokens = transactionTemplate.execute(status -> {
      // Conditional UPDATE: of two concurrent refreshes with this token, one wins.
      if (refreshTokenRepository.revokeIfActive(presented) != 1) {
        throw rejectRefresh();
      }
      return authMapper.newTokenToTokenResponseDto(jwtService.generateToken(user), createAndStoreRefreshToken(user));
    });
    monitoringService.recordTokenRefresh(true);
    return ResponseBuilder.success("Token refreshed successfully", tokens);
  }
```

and add `import com.spring.app.modules.auth.dto.response.TokenResponseDto;`.

6. Replace the whole `resetPassword` method with:

```java
  @Override
  public ResponseEntity<?> resetPassword(ResetPasswordRequestDto dto) {
    String email = EmailNormalizer.normalize(dto.email());
    throttleByEmail(email);

    // Unknown e-mail, non-ACTIVE account and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> active = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE);
    if (!otpValid || active.isEmpty()) {
      monitoringService.recordOtpFailure("password_reset");
      throw new BadRequestException(INVALID_OTP);
    }

    User user = active.get();
    String encodedPassword = passwordEncoder.encode(dto.newPassword()); // BCrypt outside the transaction
    transactionTemplate.executeWithoutResult(status -> {
      user.setPassword(encodedPassword);
      userRepository.save(user);
      refreshTokenRepository.revokeAllByUser(user);
    });
    otpFunction.removeOtp(email);
    return ResponseBuilder.success("Password reset successfully");
  }
```

- [ ] **Step 4: Confirm no transaction annotation is left in the auth service**

Run: `grep -n "Transactional" src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java`
Expected: no output.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=RefreshTokenRepositoryTest,AuthServiceImplTest,BaseSpringApplicationTests`
Expected: exit 0, no failures (`contextLoads` proves `TransactionTemplate` is injectable and `revokeIfActive`'s JPQL is valid).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/modules/auth/repositories/RefreshTokenRepository.java \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/test/java/com/spring/app/modules/auth/repositories/RefreshTokenRepositoryTest.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
perf(auth): keep DB transactions off Redis, BCrypt and JWT signing

AuthServiceImpl has no @Transactional any more: single writes use the
repository's transaction, multi-write steps (password reset, token
rotation) use a TransactionTemplate around just the writes. Refresh
rotation revokes the presented token with a conditional UPDATE, so two
concurrent refreshes with one token cannot both succeed.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Login lockout counts failures only, per e-mail + IP

Covers spec: "**Login lockout DoS**: `AuthServiceImpl.java:77-81,158` throttles by `email:`+email with AUTH bucket and counts successes → ~1000 requests lock a victim out for a day. Count failures only, key on email+IP." Also the `locked` login-failure and `login` rate-limit metrics.

**Files:**
- Create: `src/main/java/com/spring/app/modules/auth/services/LoginAttemptService.java`
- Modify: `src/main/java/com/spring/app/configs/properties/AuthProperties.java` (add two fields)
- Modify: `src/main/resources/application.yml` (the `application.auth` block from Task 5)
- Modify: `src/test/resources/application-test.yml` (the `application.auth` block from Task 5)
- Modify: `src/main/java/com/spring/app/modules/auth/services/AuthServiceInterface.java:20`
- Modify: `src/main/java/com/spring/app/modules/auth/controller/AuthController.java:47-51`
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (new field; `login`; `authenticate`)
- Test: `src/test/java/com/spring/app/modules/auth/services/LoginAttemptServiceTest.java` (create)
- Test: `src/test/java/com/spring/app/configs/properties/AuthPropertiesTest.java` (create)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (extend)

**Interfaces:**
- Consumes: Boot's `StringRedisTemplate` bean (P1 guarantees exactly one); `EmailNormalizer.fingerprint` (Task 6); `RateLimitExceededException(String)` (exists, 429).
- Produces:
  - `AuthProperties#getLoginMaxFailures(): int` (default 5, `@Min(1)`), `#getLoginFailureWindow(): Duration` (default 15m, `@NotNull @DurationMin(seconds = 1)`).
  - `LoginAttemptService(StringRedisTemplate, AuthProperties)`: `boolean isBlocked(String email, String clientIp)`, `void recordFailure(String email, String clientIp)`, `void reset(String email, String clientIp)`, package-private `static String key(String email, String clientIp)` = `"auth:login-failures:" + fingerprint(email) + ":" + clientIp`. All fail open on Redis errors.
  - `AuthServiceInterface#login(LoginRequestDto dto, String clientIp)` (replaces `login(LoginRequestDto)`).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/spring/app/modules/auth/services/LoginAttemptServiceTest.java`:

```java
package com.spring.app.modules.auth.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.spring.app.configs.properties.AuthProperties;

class LoginAttemptServiceTest {

  private static final String EMAIL = "user@example.com";
  private static final String IP = "203.0.113.7";

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final AuthProperties properties = new AuthProperties();
  private final LoginAttemptService service = new LoginAttemptService(redis, properties);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    properties.setLoginMaxFailures(3);
    properties.setLoginFailureWindow(Duration.ofMinutes(15));
  }

  @Test
  void keyCombinesAHashOfTheEmailWithTheClientIp() {
    String key = LoginAttemptService.key(EMAIL, IP);

    assertThat(key).startsWith("auth:login-failures:").endsWith(":" + IP).doesNotContain(EMAIL);
    assertThat(LoginAttemptService.key("USER@example.com", IP)).isEqualTo(key);
    assertThat(LoginAttemptService.key(EMAIL, "198.51.100.1")).isNotEqualTo(key);
  }

  @Test
  void notBlockedWithoutFailures() {
    assertThat(service.isBlocked(EMAIL, IP)).isFalse();
  }

  @Test
  void notBlockedBelowTheThreshold() {
    when(values.get(LoginAttemptService.key(EMAIL, IP))).thenReturn("2");

    assertThat(service.isBlocked(EMAIL, IP)).isFalse();
  }

  @Test
  void blockedAtTheThreshold() {
    when(values.get(LoginAttemptService.key(EMAIL, IP))).thenReturn("3");

    assertThat(service.isBlocked(EMAIL, IP)).isTrue();
  }

  @Test
  void recordFailureIncrementsWithTheWindowAsTtlInOneScript() {
    service.recordFailure(EMAIL, IP);

    verify(redis).execute(any(RedisScript.class), eq(List.of(LoginAttemptService.key(EMAIL, IP))), eq("900000"));
  }

  @Test
  void resetDeletesTheCounter() {
    service.reset(EMAIL, IP);

    verify(redis).delete(LoginAttemptService.key(EMAIL, IP));
  }

  @Test
  void redisOutageFailsOpen() {
    when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
    doThrow(new RedisConnectionFailureException("down")).when(redis).execute(any(RedisScript.class), anyList(), any());

    assertThat(service.isBlocked(EMAIL, IP)).isFalse();
    assertThatCode(() -> service.recordFailure(EMAIL, IP)).doesNotThrowAnyException();
  }
}
```

Create `src/test/java/com/spring/app/configs/properties/AuthPropertiesTest.java`:

```java
package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class AuthPropertiesTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private Set<String> violations(AuthProperties properties) {
    return validator.validate(properties).stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
  }

  @Test
  void defaultsAreValid() {
    assertThat(violations(new AuthProperties())).isEmpty();
  }

  @Test
  void atLeastOneFailureMustBeAllowed() {
    AuthProperties properties = new AuthProperties();
    properties.setLoginMaxFailures(0);

    assertThat(violations(properties)).containsExactly("application.auth.login-max-failures must be >= 1");
  }

  @Test
  void theFailureWindowMustBePositive() {
    AuthProperties properties = new AuthProperties();
    properties.setLoginFailureWindow(Duration.ZERO);

    assertThat(violations(properties)).containsExactly("application.auth.login-failure-window must be at least 1s");
  }
}
```

In `AuthServiceImplTest`:

1. Add import `import com.spring.app.modules.auth.services.LoginAttemptService;`.
2. Add a constant and a mock:

```java
  static final String CLIENT_IP = "203.0.113.7";

  @Mock LoginAttemptService loginAttemptService;
```

3. Replace the `login` helper body so it passes the IP:

```java
  private ResponseEntity<?> login(String email, String password) {
    return authService.login(new LoginRequestDto(email, password), CLIENT_IP);
  }
```

4. Append tests:

```java
  // ---- Task 8: failure-only login lockout ------------------------------------------

  @Test
  void loginIsRefusedWhileTheEmailAndIpPairIsLocked() {
    when(loginAttemptService.isBlocked("user@example.com", CLIENT_IP)).thenReturn(true);

    assertThatThrownBy(() -> login("User@Example.com", "Str0ng!Pass"))
        .isInstanceOf(RateLimitExceededException.class);
    verifyNoInteractions(authenticationManager);
    assertThat(count("auth.login.failed", "reason", "locked")).isEqualTo(1.0);
    assertThat(count("auth.ratelimit.rejected", "bucket", "login")).isEqualTo(1.0);
  }

  @Test
  void aWrongPasswordIsRecordedForTheEmailAndIp() {
    when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("x"));

    catchThrowable(() -> login("user@example.com", "Wr0ng!Pass"));

    verify(loginAttemptService).recordFailure("user@example.com", CLIENT_IP);
  }

  @Test
  void aSuccessfulLoginIsNotCountedAndClearsEarlierFailures() {
    givenAuthenticationSucceedsFor(user("user@example.com", EUserStatus.ACTIVE));

    login("user@example.com", "Str0ng!Pass");

    verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
    verify(loginAttemptService).reset("user@example.com", CLIENT_IP);
  }

  @Test
  void aCorrectPasswordOnAnInactiveAccountIsNotAFailedGuess() {
    when(authenticationManager.authenticate(any())).thenThrow(new UserNotActiveException("Account is not active"));

    catchThrowable(() -> login("pending@example.com", "Str0ng!Pass"));

    verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
  }

  @Test
  void loginNoLongerConsumesThePerEmailBucket() {
    givenAuthenticationSucceedsFor(user("user@example.com", EUserStatus.ACTIVE));

    login("user@example.com", "Str0ng!Pass");

    verify(rateLimitManagerService, never()).checkRateLimitAndThrow(any(), anyString());
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=LoginAttemptServiceTest,AuthPropertiesTest,AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: class LoginAttemptService`, `method setLoginMaxFailures(int)`, and `login(LoginRequestDto,String)`.

- [ ] **Step 3: Implement the properties**

In `src/main/java/com/spring/app/configs/properties/AuthProperties.java`, add imports:

```java
import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
```

and add these fields after `skipEmailVerification`:

```java

  /**
   * Failed logins allowed per (e-mail, client IP) inside
   * {@link #loginFailureWindow} before further attempts are refused (429).
   * Successful logins are never counted.
   */
  @Min(value = 1, message = "application.auth.login-max-failures must be >= 1")
  private int loginMaxFailures = 5;

  /** How long failed logins are remembered; the lock lifts when it expires. */
  @NotNull(message = "application.auth.login-failure-window is required")
  @DurationMin(seconds = 1, message = "application.auth.login-failure-window must be at least 1s")
  private Duration loginFailureWindow = Duration.ofMinutes(15);
```

In `src/main/resources/application.yml`, append to the `application.auth` block (after the `skip-email-verification` line):

```yaml
    # Failed logins allowed per (e-mail, client IP) before further attempts are
    # refused until the window expires. Successful logins are never counted.
    login-max-failures: ${AUTH_LOGIN_MAX_FAILURES:5}
    login-failure-window: ${AUTH_LOGIN_FAILURE_WINDOW:15m}
```

In `src/test/resources/application-test.yml`, append to the `application.auth` block:

```yaml
    login-max-failures: 5
    login-failure-window: 15m
```

- [ ] **Step 4: Implement `LoginAttemptService`**

Create `src/main/java/com/spring/app/modules/auth/services/LoginAttemptService.java`:

```java
package com.spring.app.modules.auth.services;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.modules.auth.utils.EmailNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Counts FAILED logins per (e-mail, client IP) in Redis.
 *
 * <p>
 * The old throttle counted every login per e-mail alone, so anyone could lock
 * a user out by sending ~1000 requests with that address. Keying on the pair
 * means a stranger only locks out their own IP, and a successful login clears
 * the counter. The e-mail is hashed so addresses never appear in Redis keys.
 * </p>
 *
 * <p>
 * Redis errors fail open (the attempt is allowed), the same policy as the
 * per-IP rate limiter.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

  private static final String KEY_PREFIX = "auth:login-failures:";

  /** INCR and arm the TTL in one round trip, so the key can never lose its expiry. */
  private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
      "local count = redis.call('INCR', KEYS[1]) "
          + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
          + "return count",
      Long.class);

  private final StringRedisTemplate redisTemplate;
  private final AuthProperties authProperties;

  public boolean isBlocked(String email, String clientIp) {
    try {
      String failures = redisTemplate.opsForValue().get(key(email, clientIp));
      return failures != null && Long.parseLong(failures) >= authProperties.getLoginMaxFailures();
    } catch (RuntimeException e) {
      log.warn("Login-failure counter unavailable, allowing the attempt: {}", e.getMessage());
      return false;
    }
  }

  public void recordFailure(String email, String clientIp) {
    try {
      redisTemplate.execute(INCREMENT_WITH_TTL, List.of(key(email, clientIp)),
          String.valueOf(authProperties.getLoginFailureWindow().toMillis()));
    } catch (RuntimeException e) {
      log.warn("Could not record a failed login: {}", e.getMessage());
    }
  }

  public void reset(String email, String clientIp) {
    try {
      redisTemplate.delete(key(email, clientIp));
    } catch (RuntimeException e) {
      log.warn("Could not clear the login-failure counter: {}", e.getMessage());
    }
  }

  static String key(String email, String clientIp) {
    return KEY_PREFIX + EmailNormalizer.fingerprint(email) + ":" + clientIp;
  }
}
```

- [ ] **Step 5: Wire it into login**

In `src/main/java/com/spring/app/modules/auth/services/AuthServiceInterface.java`, replace `ResponseEntity<?> login(LoginRequestDto dto);` with:

```java
  /**
   * @param clientIp the caller's address ({@code request.getRemoteAddr()});
   *                 failed attempts are counted per e-mail + IP
   */
  ResponseEntity<?> login(LoginRequestDto dto, String clientIp);
```

In `src/main/java/com/spring/app/modules/auth/controller/AuthController.java`, replace the `login` method (lines 47-51) with:

```java
  @Operation(summary = "Login")
  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto dto, HttpServletRequest request) {
    // getRemoteAddr() is the real client once Tomcat's RemoteIpValve has
    // processed X-Forwarded-For from a trusted proxy (server.tomcat.remoteip.*).
    return authService.login(dto, request.getRemoteAddr());
  }
```

In `AuthServiceImpl.java`:

1. Add `import com.spring.app.modules.auth.services.LoginAttemptService;`.
2. Add the field directly above `private final TransactionTemplate transactionTemplate;`:

```java
  private final LoginAttemptService loginAttemptService;
```

3. Replace the whole `login` method with:

```java
  @Override
  public ResponseEntity<?> login(LoginRequestDto dto, String clientIp) {
    Timer.Sample timer = monitoringService.startLoginTimer();
    try {
      String email = EmailNormalizer.normalize(dto.email());
      // Only FAILED attempts count, per e-mail + client IP: a stranger can no
      // longer lock a user out by spamming logins with the user's address.
      if (loginAttemptService.isBlocked(email, clientIp)) {
        monitoringService.recordLoginFailure("locked");
        monitoringService.recordRateLimitRejection("login");
        throw new RateLimitExceededException("Too many failed login attempts. Please try again later.");
      }

      User user = authenticate(email, dto.password(), clientIp);
      loginAttemptService.reset(email, clientIp);

      String accessToken = jwtService.generateToken(user);
      String refreshToken = createAndStoreRefreshToken(user);

      var response = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);
      monitoringService.recordLoginSuccess();
      return ResponseBuilder.success("Login successful", response);
    } finally {
      monitoringService.stopLoginTimer(timer);
    }
  }
```

4. Replace the whole `authenticate` method with:

```java
  private User authenticate(String email, String password, String clientIp) {
    try {
      Authentication authenticated = authenticationManager
          .authenticate(new UsernamePasswordAuthenticationToken(email, password));
      return (User) authenticated.getPrincipal();
    } catch (UserNotActiveException ex) {
      // Thrown only after the password matched (ApplicationConfig): not a guess.
      monitoringService.recordLoginFailure("not_active");
      throw ex;
    } catch (AuthenticationException ex) {
      // Unknown e-mail, DELETED account and wrong password look the same.
      loginAttemptService.recordFailure(email, clientIp);
      monitoringService.recordLoginFailure("bad_credentials");
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }
  }
```

- [ ] **Step 6: Confirm there is no other caller of the old signature**

Run: `grep -rn "\.login(" src/main/java`
Expected: exactly one line, in `AuthController.java`, calling `authService.login(dto, request.getRemoteAddr())`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=LoginAttemptServiceTest,AuthPropertiesTest,AuthServiceImplTest,BaseSpringApplicationTests`
Expected: exit 0, no failures (`contextLoads` proves the new strict properties bind from the literal test overrides and `StringRedisTemplate` resolves uniquely).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/spring/app/modules/auth/services/LoginAttemptService.java \
  src/main/java/com/spring/app/configs/properties/AuthProperties.java \
  src/main/resources/application.yml src/test/resources/application-test.yml \
  src/main/java/com/spring/app/modules/auth/services/AuthServiceInterface.java \
  src/main/java/com/spring/app/modules/auth/controller/AuthController.java \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/test/java/com/spring/app/modules/auth/services/LoginAttemptServiceTest.java \
  src/test/java/com/spring/app/configs/properties/AuthPropertiesTest.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(auth): lock logins on failed attempts per e-mail + IP only

Login used the per-e-mail AUTH bucket and counted successes, so ~1000
requests with someone's address locked them out for a day. Now only
failed attempts count, keyed on sha256(e-mail) + client IP, and a
successful login clears the counter (application.auth.login-max-failures
/ login-failure-window, default 5 / 15m).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: OAuth2 — verified e-mail, status checks, frontend redirect

Covers spec: "**CustomOAuth2UserService.java:107** `Map.of` NPE on null avatar; all exceptions wrapped as `RuntimeException` instead of `OAuth2AuthenticationException` → 500 instead of failure handler. Also links by e-mail without checking Google `email_verified` (pre-account hijack); logs PII at INFO (line 92)."; "**OAuth2AuthenticationSuccessHandler.java:74** issues tokens without checking status (DELETED/INACTIVE get tokens; PENDING bypasses OTP). Handler talks to repositories directly, duplicates `createAndStoreRefreshToken`, injects concrete `JwtService`. Writes tokens as JSON into the browser callback page — redirect to `application.frontend-url` instead (tokens in URL fragment) so a SPA can receive them. OAuth2 state lives in in-memory HttpSession — document 'sticky sessions required'"; "**OAuth2AuthenticationFailureHandler.java:44** echoes exception message"; the OAuth2 counter from the Metrics bullet; e-mail normalisation for OAuth2.

Also fixes a latent bug: the random password for new OAuth2 users was `RandomStringUtils.secureStrong().next(20)` — 20 arbitrary code points, up to 80 UTF-8 bytes, which BCrypt rejects. It becomes `nextAlphanumeric(32)`.

**Files:**
- Modify: `src/main/java/com/spring/app/shared/services/CustomOAuth2UserService.java:1-113` (whole file)
- Create: `src/main/java/com/spring/app/handlers/OAuth2FrontendRedirect.java`
- Modify: `src/main/java/com/spring/app/handlers/OAuth2AuthenticationSuccessHandler.java:1-94` (whole file)
- Modify: `src/main/java/com/spring/app/handlers/OAuth2AuthenticationFailureHandler.java:1-53` (whole file)
- Modify: `src/main/java/com/spring/app/configs/properties/AuthProperties.java` (add one field)
- Modify: `src/main/resources/application.yml`, `src/test/resources/application-test.yml` (the `application.auth` blocks)
- Modify: `src/main/java/com/spring/app/modules/auth/services/AuthServiceInterface.java` (add a method)
- Modify: `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` (whole file — final listing)
- Modify: `src/main/java/com/spring/app/configs/SecurityConfig.java:145-166` (`oauth2Login` and its Javadoc)
- Test: `src/test/java/com/spring/app/shared/services/CustomOAuth2UserServiceTest.java` (create)
- Test: `src/test/java/com/spring/app/handlers/OAuth2HandlersTest.java` (create)
- Test: `src/test/java/com/spring/app/configs/properties/AuthPropertiesTest.java` (extend)
- Test: `src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java` (extend)

**Interfaces:**
- Consumes: `ApplicationProperties#getFrontendUrl()` / `#getOauth2AllowedEmailDomains()` (unchanged, read only); `EmailNormalizer` (Task 6); `MonitoringService#recordOAuth2Login(boolean)` (Task 4); `UserNotActiveException(String)` (Task 3).
- Produces:
  - `AuthProperties#getOauth2RedirectPath(): String` (default `/oauth2/callback`, `@NotBlank`, must start with `/`).
  - `TokenResponseDto AuthServiceInterface#issueTokensForOAuth2Login(String email)` — throws `UserNotActiveException("Account is not active")` for unknown/non-ACTIVE accounts.
  - `CustomOAuth2UserService(UserRepository, PasswordEncoder, ApplicationProperties)`: `OAuth2User loadUser(OAuth2UserRequest)`, `OidcUser loadOidcUser(OidcUserRequest)`, package-private `User resolveUser(Map<String, Object>)` and `OAuth2User toPrincipal(User)`. OAuth2 error codes: `missing_email`, `email_not_verified`, `email_domain_not_allowed`, `account_not_active`, `server_error`.
  - `OAuth2FrontendRedirect(ApplicationProperties, AuthProperties)`: `String success(TokenResponseDto)` → `<frontend-url><path>#access_token=..&refresh_token=..&token_type=Bearer`; `String failure(String errorCode)` → `<frontend-url><path>#error=<code>` (code must match `[a-z0-9_]{1,64}`, else `authentication_failed`).
  - `OAuth2AuthenticationSuccessHandler(AuthServiceInterface, OAuth2FrontendRedirect, MonitoringService)`; `OAuth2AuthenticationFailureHandler(OAuth2FrontendRedirect, MonitoringService)`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/spring/app/shared/services/CustomOAuth2UserServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;

class CustomOAuth2UserServiceTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final ApplicationProperties applicationProperties = new ApplicationProperties();
  private final CustomOAuth2UserService service =
      new CustomOAuth2UserService(userRepository, new BCryptPasswordEncoder(4), applicationProperties);

  @BeforeEach
  void setUp() {
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
      User user = invocation.getArgument(0);
      user.setId(UUID.randomUUID());
      return user;
    });
  }

  /** Google-like attributes with no "picture": exercises the null-avatar path. */
  private static Map<String, Object> google(String email, Object emailVerified) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("sub", "1234567890");
    attributes.put("email", email);
    if (emailVerified != null) {
      attributes.put("email_verified", emailVerified);
    }
    attributes.put("name", "Nguyễn Văn A");
    return attributes;
  }

  private static String errorCode(Throwable thrown) {
    return ((OAuth2AuthenticationException) thrown).getError().getErrorCode();
  }

  private User existing(String email, EUserStatus status) {
    User user = User.builder().email(email).password("x").status(status).build();
    user.setId(UUID.randomUUID());
    when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    return user;
  }

  @Test
  void createsAnActiveUserForANewVerifiedEmailWithoutAnAvatar() {
    when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

    User user = service.resolveUser(google("New@Example.com", true));

    assertThat(user.getEmail()).isEqualTo("new@example.com");
    assertThat(user.getStatus()).isEqualTo(EUserStatus.ACTIVE);
    assertThat(user.getAvatarUrl()).isNull();
    OAuth2User principal = service.toPrincipal(user);
    assertThat(principal.<String>getAttribute("email")).isEqualTo("new@example.com");
    assertThat(principal.getAttributes()).doesNotContainKey("avatarUrl");
  }

  @Test
  void refusesAnUnverifiedEmailBeforeTouchingAnyAccount() {
    assertThatThrownBy(() -> service.resolveUser(google("victim@example.com", false)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("email_not_verified"));
    verifyNoInteractions(userRepository);
  }

  @Test
  void treatsAMissingEmailVerifiedClaimAsUnverified() {
    assertThatThrownBy(() -> service.resolveUser(google("victim@example.com", null)))
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("email_not_verified"));
  }

  @Test
  void acceptsEmailVerifiedSentAsAString() {
    existing("user@example.com", EUserStatus.ACTIVE);

    assertThat(service.resolveUser(google("user@example.com", "true")).getEmail()).isEqualTo("user@example.com");
  }

  @Test
  void linksAnExistingActiveAccount() {
    User user = existing("user@example.com", EUserStatus.ACTIVE);

    assertThat(service.resolveUser(google("user@example.com", true))).isSameAs(user);
    verify(userRepository, never()).save(any());
  }

  @Test
  void refusesAPendingAccountSoGoogleCannotBypassTheOtp() {
    existing("pending@example.com", EUserStatus.PENDING);

    assertThatThrownBy(() -> service.resolveUser(google("pending@example.com", true)))
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("account_not_active"));
  }

  @Test
  void refusesADisallowedDomain() {
    applicationProperties.setOauth2AllowedEmailDomains(List.of("solashi.com"));

    assertThatThrownBy(() -> service.resolveUser(google("user@example.com", true)))
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("email_domain_not_allowed"));
  }

  @Test
  void wrapsUnexpectedFailuresSoTheFailureHandlerRuns() {
    when(userRepository.findByEmail("user@example.com")).thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatThrownBy(() -> service.resolveUser(google("user@example.com", true)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .satisfies(thrown -> assertThat(errorCode(thrown)).isEqualTo("server_error"));
  }
}
```

Create `src/test/java/com/spring/app/handlers/OAuth2HandlersTest.java`:

```java
package com.spring.app.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.services.MonitoringService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OAuth2HandlersTest {

  private static final String CALLBACK = "https://app.example.com/oauth2/callback";

  private final ApplicationProperties applicationProperties = new ApplicationProperties();
  private final AuthProperties authProperties = new AuthProperties();
  private final OAuth2FrontendRedirect redirect = new OAuth2FrontendRedirect(applicationProperties, authProperties);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MonitoringService monitoringService = new MonitoringService(meterRegistry);
  private final AuthServiceInterface authService = mock(AuthServiceInterface.class);
  private final OAuth2AuthenticationSuccessHandler successHandler =
      new OAuth2AuthenticationSuccessHandler(authService, redirect, monitoringService);
  private final OAuth2AuthenticationFailureHandler failureHandler =
      new OAuth2AuthenticationFailureHandler(redirect, monitoringService);

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @BeforeEach
  void setUp() {
    applicationProperties.setFrontendUrl("https://app.example.com/");
  }

  private static Authentication oauth2Login(String email) {
    OAuth2User principal = new DefaultOAuth2User(
        List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("email", email), "email");
    return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
  }

  private double oauth2Count(String result) {
    Counter counter = meterRegistry.find("auth.oauth2.login").tags("result", result).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void successRedirectsToTheFrontendWithTokensInTheFragment() throws Exception {
    when(authService.issueTokensForOAuth2Login("user@example.com"))
        .thenReturn(new TokenResponseDto("access.jwt", "refresh.jwt"));
    request.getSession(true);

    successHandler.onAuthenticationSuccess(request, response, oauth2Login("user@example.com"));

    assertThat(response.getRedirectedUrl())
        .isEqualTo(CALLBACK + "#access_token=access.jwt&refresh_token=refresh.jwt&token_type=Bearer");
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(request.getSession(false)).as("OAuth2 handshake session dropped").isNull();
  }

  @Test
  void successForANonActiveAccountRedirectsWithAnErrorAndNoTokens() throws Exception {
    when(authService.issueTokensForOAuth2Login(any())).thenThrow(new UserNotActiveException("Account is not active"));

    successHandler.onAuthenticationSuccess(request, response, oauth2Login("pending@example.com"));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=account_not_active");
    assertThat(oauth2Count("failure")).isEqualTo(1.0);
  }

  @Test
  void failureRedirectsWithTheErrorCodeAndNeverTheMessage() throws Exception {
    failureHandler.onAuthenticationFailure(request, response,
        new OAuth2AuthenticationException(new OAuth2Error("email_not_verified"), "detail victim@example.com"));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=email_not_verified");
    assertThat(response.getContentAsString()).doesNotContain("victim");
    assertThat(oauth2Count("failure")).isEqualTo(1.0);
  }

  @Test
  void failureWithAnUnexpectedCodeFallsBackToAGenericOne() throws Exception {
    failureHandler.onAuthenticationFailure(request, response,
        new OAuth2AuthenticationException(new OAuth2Error("<script>")));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=authentication_failed");
  }

  @Test
  void nonOAuth2FailureUsesTheGenericCode() throws Exception {
    failureHandler.onAuthenticationFailure(request, response, new BadCredentialsException("secret"));

    assertThat(response.getRedirectedUrl()).isEqualTo(CALLBACK + "#error=authentication_failed");
  }
}
```

Append to `AuthPropertiesTest`:

```java
  @Test
  void theOAuth2RedirectPathMustBeAbsolute() {
    AuthProperties properties = new AuthProperties();
    properties.setOauth2RedirectPath("callback");

    assertThat(violations(properties)).containsExactly("application.auth.oauth2-redirect-path must start with '/'");
  }
```

In `AuthServiceImplTest`, add import `import com.spring.app.modules.auth.dto.response.TokenResponseDto;` and append:

```java
  // ---- Task 9: OAuth2 token issuance -------------------------------------------------

  @Test
  void oauth2LoginIssuesTypedTokensForAnActiveAccount() {
    givenUser(user("user@example.com", EUserStatus.ACTIVE));

    TokenResponseDto tokens = authService.issueTokensForOAuth2Login(" User@Example.com ");

    assertThat(jwtService.isAccessToken(tokens.accessToken())).isTrue();
    assertThat(jwtService.isRefreshToken(tokens.refreshToken())).isTrue();
    verify(refreshTokenRepository).save(any(RefreshToken.class));
    assertThat(count("auth.oauth2.login", "result", "success")).isEqualTo(1.0);
  }

  @Test
  void oauth2LoginRefusesUnknownOrNonActiveAccounts() {
    givenUser(user("pending@example.com", EUserStatus.PENDING));

    for (String email : java.util.Arrays.asList("pending@example.com", "nobody@example.com", null)) {
      assertThatThrownBy(() -> authService.issueTokensForOAuth2Login(email))
          .isInstanceOf(UserNotActiveException.class)
          .hasMessage("Account is not active");
    }
    verify(refreshTokenRepository, never()).save(any());
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=CustomOAuth2UserServiceTest,OAuth2HandlersTest,AuthPropertiesTest,AuthServiceImplTest`
Expected: FAIL at test compilation — `cannot find symbol: class OAuth2FrontendRedirect`, `method resolveUser`, `method issueTokensForOAuth2Login`, `method setOauth2RedirectPath`.

- [ ] **Step 3: Add the redirect-path property**

In `AuthProperties.java`, add imports `jakarta.validation.constraints.NotBlank` and `jakarta.validation.constraints.Pattern`, and this field after `loginFailureWindow`:

```java

  /**
   * SPA route, appended to {@code application.frontend-url}, that receives the
   * OAuth2 result in the URL fragment ({@code #access_token=...} or
   * {@code #error=...}).
   */
  @NotBlank(message = "application.auth.oauth2-redirect-path is required")
  @Pattern(regexp = "^/.*", message = "application.auth.oauth2-redirect-path must start with '/'")
  private String oauth2RedirectPath = "/oauth2/callback";
```

In `src/main/resources/application.yml`, append to the `application.auth` block:

```yaml
    # SPA route, appended to frontend-url, that receives the OAuth2 result in
    # the URL fragment (#access_token=...&refresh_token=... or #error=...).
    oauth2-redirect-path: ${AUTH_OAUTH2_REDIRECT_PATH:/oauth2/callback}
```

In `src/test/resources/application-test.yml`, append to the `application.auth` block:

```yaml
    oauth2-redirect-path: /oauth2/callback
```

- [ ] **Step 4: Rewrite `CustomOAuth2UserService`**

Replace the whole of `src/main/java/com/spring/app/shared/services/CustomOAuth2UserService.java` with:

```java
package com.spring.app.shared.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.utils.EmailNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps a provider sign-in to a local {@link User}.
 *
 * <p>
 * Google is registered with the {@code openid} scope, so Spring Security
 * routes its logins through the OIDC user service, not {@link #loadUser}.
 * {@link #loadOidcUser} is that entry point (wired in SecurityConfig);
 * {@link #loadUser} covers plain OAuth2 providers. Both go through
 * {@link #resolveUser}.
 * </p>
 *
 * <p>
 * Every failure is an {@link OAuth2AuthenticationException}, so it reaches
 * OAuth2AuthenticationFailureHandler (redirect with an error code) instead of
 * escaping as a 500.
 * </p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  static final String ATTR_EMAIL = "email";
  static final String ATTR_EMAIL_VERIFIED = "email_verified";
  static final String ATTR_NAME = "name";
  static final String ATTR_PICTURE = "picture";

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final ApplicationProperties applicationProperties;
  private final OidcUserService oidcDelegate = new OidcUserService();

  @Override
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    OAuth2User oauth2User = super.loadUser(userRequest);
    return toPrincipal(resolveUser(oauth2User.getAttributes()));
  }

  public OidcUser loadOidcUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
    OidcUser oidcUser = oidcDelegate.loadUser(userRequest);
    User user = resolveUser(oidcUser.getAttributes());
    return new DefaultOidcUser(user.getAuthorities(), oidcUser.getIdToken(), oidcUser.getUserInfo(), ATTR_EMAIL);
  }

  User resolveUser(Map<String, Object> attributes) {
    try {
      String email = EmailNormalizer.normalize(asString(attributes.get(ATTR_EMAIL)));
      if (email == null || email.isEmpty()) {
        throw failure("missing_email", "OAuth2 provider did not return an email");
      }
      // Without this, whoever controls an IdP account that merely CLAIMS the
      // address could sign in as (or pre-create) the local account owning it.
      if (!isEmailVerified(attributes.get(ATTR_EMAIL_VERIFIED))) {
        throw failure("email_not_verified", "OAuth2 provider has not verified this email");
      }
      if (!isEmailDomainAllowed(email)) {
        throw failure("email_domain_not_allowed", "OAuth2 email domain is not allowed");
      }

      User user = userRepository.findByEmail(email).orElseGet(() -> createNewUser(attributes, email));
      // PENDING must finish OTP activation; INACTIVE and DELETED must not sign in.
      if (user.getStatus() != EUserStatus.ACTIVE) {
        throw failure("account_not_active", "Account is not active");
      }
      return user;
    } catch (OAuth2AuthenticationException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("OAuth2 user resolution failed", e);
      throw new OAuth2AuthenticationException(new OAuth2Error("server_error"), "OAuth2 login failed", e);
    }
  }

  OAuth2User toPrincipal(User user) {
    // HashMap, not Map.of: Map.of throws on a null value (e.g. no avatar).
    Map<String, Object> attributes = new HashMap<>();
    attributes.put(ATTR_EMAIL, user.getEmail());
    attributes.put("role", user.getRole().name());
    if (user.getId() != null) {
      attributes.put("id", user.getId().toString());
    }
    if (user.getAvatarUrl() != null) {
      attributes.put("avatarUrl", user.getAvatarUrl());
    }
    return new DefaultOAuth2User(user.getAuthorities(), attributes, ATTR_EMAIL);
  }

  private User createNewUser(Map<String, Object> attributes, String email) {
    User newUser = User.builder()
        .email(email)
        // Random 32-byte ASCII password (BCrypt accepts at most 72 bytes): the
        // account signs in through the provider, or after a password reset.
        .password(passwordEncoder.encode(RandomStringUtils.secureStrong().nextAlphanumeric(32)))
        .status(EUserStatus.ACTIVE)
        .avatarUrl(asString(attributes.get(ATTR_PICTURE)))
        .fullName(asString(attributes.get(ATTR_NAME)))
        .build();
    User saved = userRepository.save(newUser);
    log.info("Created user {} from an OAuth2 login", saved.getId());
    return saved;
  }

  private static boolean isEmailVerified(Object claim) {
    return Boolean.TRUE.equals(claim) || (claim instanceof String value && Boolean.parseBoolean(value));
  }

  private boolean isEmailDomainAllowed(String email) {
    List<String> allowed = applicationProperties.getOauth2AllowedEmailDomains();
    if (allowed == null || allowed.isEmpty()) {
      return true; // No whitelist configured -> accept any domain.
    }
    int at = email.lastIndexOf('@');
    if (at < 0) {
      return false;
    }
    String domain = email.substring(at + 1);
    return allowed.stream()
        .map(d -> d.trim().toLowerCase())
        .filter(d -> !d.isEmpty())
        .anyMatch(domain::equals);
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  private static OAuth2AuthenticationException failure(String errorCode, String description) {
    return new OAuth2AuthenticationException(new OAuth2Error(errorCode, description, null), description);
  }
}
```

- [ ] **Step 5: Redirect helper and handlers**

Create `src/main/java/com/spring/app/handlers/OAuth2FrontendRedirect.java`:

```java
package com.spring.app.handlers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;

import lombok.RequiredArgsConstructor;

/**
 * Builds the URL the browser is sent to after an OAuth2 login:
 * {@code application.frontend-url + application.auth.oauth2-redirect-path}.
 *
 * <p>
 * Results travel in the URL <em>fragment</em>: browsers never send it to a
 * server, so the tokens do not reach access logs or Referer headers. Error
 * codes are restricted to {@code [a-z0-9_]}, never a free-text message.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OAuth2FrontendRedirect {

  private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[a-z0-9_]{1,64}");
  private static final String GENERIC_ERROR = "authentication_failed";

  private final ApplicationProperties applicationProperties;
  private final AuthProperties authProperties;

  public String success(TokenResponseDto tokens) {
    return base()
        + "#access_token=" + encode(tokens.accessToken())
        + "&refresh_token=" + encode(tokens.refreshToken())
        + "&token_type=Bearer";
  }

  public String failure(String errorCode) {
    String code = errorCode != null && SAFE_ERROR_CODE.matcher(errorCode).matches() ? errorCode : GENERIC_ERROR;
    return base() + "#error=" + code;
  }

  private String base() {
    String frontendUrl = applicationProperties.getFrontendUrl();
    String trimmed = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
    return trimmed + authProperties.getOauth2RedirectPath();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
```

Replace the whole of `src/main/java/com/spring/app/handlers/OAuth2AuthenticationSuccessHandler.java` with:

```java
package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.shared.services.MonitoringService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues the app's own tokens after a provider login and redirects the browser
 * to the frontend with them in the URL fragment. Token issuance (status check,
 * refresh-token storage) lives in {@link AuthServiceInterface}, not here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

  private final AuthServiceInterface authService;
  private final OAuth2FrontendRedirect frontendRedirect;
  private final MonitoringService monitoringService;

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException {
    String target;
    try {
      String email = authentication.getPrincipal() instanceof OAuth2User user
          ? user.<String>getAttribute("email")
          : null;
      TokenResponseDto tokens = authService.issueTokensForOAuth2Login(email);
      target = frontendRedirect.success(tokens);
    } catch (UserNotActiveException e) {
      monitoringService.recordOAuth2Login(false);
      target = frontendRedirect.failure("account_not_active");
    } catch (RuntimeException e) {
      log.error("Could not issue tokens after an OAuth2 login", e);
      monitoringService.recordOAuth2Login(false);
      target = frontendRedirect.failure("server_error");
    }

    // The session only carried the OAuth2 handshake state; the API is stateless.
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    response.sendRedirect(target);
  }
}
```

Replace the whole of `src/main/java/com/spring/app/handlers/OAuth2AuthenticationFailureHandler.java` with:

```java
package com.spring.app.handlers;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.spring.app.shared.services.MonitoringService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the browser back to the frontend with an error code in the URL
 * fragment. The exception message is never echoed: it can carry the e-mail or
 * provider internals.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final OAuth2FrontendRedirect frontendRedirect;
  private final MonitoringService monitoringService;

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException {
    String errorCode = exception instanceof OAuth2AuthenticationException oauth2
        ? oauth2.getError().getErrorCode()
        : null;
    log.warn("OAuth2 login failed: {}", errorCode != null ? errorCode : exception.getClass().getSimpleName());
    monitoringService.recordOAuth2Login(false);
    response.sendRedirect(frontendRedirect.failure(errorCode));
  }
}
```

- [ ] **Step 6: Token issuance in the auth service**

In `src/main/java/com/spring/app/modules/auth/services/AuthServiceInterface.java`, add the import `import com.spring.app.modules.auth.dto.response.TokenResponseDto;` and, after `resetPassword`:

```java

  /**
   * Issues an access/refresh pair after a successful OAuth2 login.
   *
   * @throws com.spring.app.exceptions.UserNotActiveException when no ACTIVE
   *         account owns the (normalised) e-mail
   */
  TokenResponseDto issueTokensForOAuth2Login(String email);
```

Replace the whole of `src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java` with this final listing (it is Tasks 2-8 plus `ACCOUNT_NOT_ACTIVE` and `issueTokensForOAuth2Login`; if your file differs anywhere else, this listing is authoritative):

```java
package com.spring.app.modules.auth.services.impl;

import java.time.Instant;
import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.spring.app.common.response.ResponseBuilder;
import com.spring.app.configs.properties.AuthProperties;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.enums.EUserStatus;
import com.spring.app.exceptions.BadRequestException;
import com.spring.app.exceptions.ConflictException;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.exceptions.UserNotActiveException;
import com.spring.app.modules.auth.dto.request.ActiveAccountRequestDto;
import com.spring.app.modules.auth.dto.request.LoginRequestDto;
import com.spring.app.modules.auth.dto.request.RefreshTokenDto;
import com.spring.app.modules.auth.dto.request.RegisterRequestDto;
import com.spring.app.modules.auth.dto.request.ResetPasswordRequestDto;
import com.spring.app.modules.auth.dto.response.TokenResponseDto;
import com.spring.app.modules.auth.entities.RefreshToken;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.exceptions.InvalidTokenException;
import com.spring.app.modules.auth.mapper.AuthMapper;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.AuthServiceInterface;
import com.spring.app.modules.auth.services.LoginAttemptService;
import com.spring.app.modules.auth.utils.EmailNormalizer;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.AuthCacheService;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.OtpEmailService;
import com.spring.app.shared.services.RateLimitManagerService;
import com.spring.app.utils.JwtFunctionUtil;
import com.spring.app.utils.OtpFunctionUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Authentication use cases.
 *
 * <p>
 * <b>Transactions:</b> no method here is {@code @Transactional}. An open
 * transaction pins a Hikari connection, and with virtual threads the pool
 * (not Tomcat's thread count) is the concurrency cap - so BCrypt, JWT signing
 * and Redis round-trips must never run inside one. A single write relies on
 * the repository's own transaction; a step with several writes wraps only
 * those writes in {@link TransactionTemplate}.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthServiceInterface {

  static final String INVALID_CREDENTIALS = "Invalid email or password";
  static final String INVALID_OTP = "Invalid or expired OTP";
  static final String INVALID_ACCESS_TOKEN = "Invalid or expired token";
  static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token";
  static final String REGISTRATION_REJECTED = "This email cannot be registered";
  static final String ACCOUNT_NOT_ACTIVE = "Account is not active";

  private final AuthProperties authProperties;
  private final Environment environment;
  private final JwtProperties jwtProperties;
  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final JwtServiceInterface jwtService;
  private final PasswordEncoder passwordEncoder;
  private final AuthenticationManager authenticationManager;
  private final AuthMapper authMapper;
  private final JwtFunctionUtil jwtFunction;
  private final OtpFunctionUtil otpFunction;
  private final MonitoringService monitoringService;
  private final OtpEmailService otpEmailService;
  private final AuthCacheService authCacheService;
  private final RedisServiceInterface redisService;
  private final RateLimitManagerService rateLimitManagerService;
  private final LoginAttemptService loginAttemptService;
  private final TransactionTemplate transactionTemplate;

  /**
   * Per-email throttle for OTP-sending and OTP-checking actions. Complements
   * the per-IP limit in {@link com.spring.app.filter.RateLimitFilter} so a
   * single account cannot be brute-forced / email-bombed from many IPs. The
   * identifier is a hash: it ends up in Redis keys and rate-limit log lines.
   */
  private void throttleByEmail(String email) {
    if (email == null || email.isBlank()) {
      return;
    }
    try {
      rateLimitManagerService.checkRateLimitAndThrow(
          ERateLimitEndpoint.AUTH, "email:" + EmailNormalizer.fingerprint(email));
    } catch (RateLimitExceededException e) {
      monitoringService.recordRateLimitRejection("auth_email");
      throw e;
    }
  }

  /**
   * The OTP step is skipped only when BOTH application.auth.skip-email-verification
   * is true AND the dev profile is active.
   */
  private boolean isEmailVerificationSkipped() {
    if (!authProperties.isSkipEmailVerification()) {
      return false;
    }
    if (environment.acceptsProfiles(Profiles.of("dev"))) {
      return true;
    }
    log.warn("application.auth.skip-email-verification=true is ignored: the dev profile is not active");
    return false;
  }

  @Override
  public ResponseEntity<?> register(RegisterRequestDto dto) {
    Timer.Sample timer = monitoringService.startRegistrationTimer();
    try {
      String email = EmailNormalizer.normalize(dto.email());
      throttleByEmail(email);

      // uk_users_email covers every row, DELETED included, so any existing row
      // owns the address. Checking only non-DELETED rows let the INSERT hit the
      // constraint (500) after the OTP had already been sent.
      if (userRepository.existsByEmail(email)) {
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      boolean skipVerification = isEmailVerificationSkipped();
      User newUser = User.builder()
          .email(email)
          .password(passwordEncoder.encode(dto.password()))
          .fullName(dto.fullName())
          .phone(dto.phone())
          .address(dto.address())
          .status(skipVerification ? EUserStatus.ACTIVE : EUserStatus.PENDING)
          .build();
      try {
        userRepository.saveAndFlush(newUser);
      } catch (DataIntegrityViolationException e) {
        // Lost a race with a concurrent registration of the same address.
        throw new ConflictException(REGISTRATION_REJECTED);
      }

      // Only once the row exists: an OTP for an account that failed to save is useless.
      if (!skipVerification) {
        otpEmailService.sendOtpEmailAsync(email);
      }

      var response = authMapper.userToRegisterResponseDto(newUser);
      monitoringService.incrementRegistrationAttempts();
      return ResponseBuilder.created("User registered successfully", response);
    } finally {
      monitoringService.stopRegistrationTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> resendOtp(String rawEmail) {
    String email = EmailNormalizer.normalize(rawEmail);
    throttleByEmail(email);
    // Same answer whether the account is unknown, ACTIVE or PENDING.
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If the account is awaiting activation, a new OTP has been sent");
  }

  @Override
  public ResponseEntity<?> activeAccount(ActiveAccountRequestDto dto) {
    String email = EmailNormalizer.normalize(dto.email());
    throttleByEmail(email);

    // Unknown e-mail, wrong status and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> pending = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.PENDING);
    if (!otpValid || pending.isEmpty()) {
      monitoringService.recordOtpFailure("activation");
      throw new BadRequestException(INVALID_OTP);
    }

    User user = pending.get();
    user.setStatus(EUserStatus.ACTIVE);
    userRepository.save(user);
    otpFunction.removeOtp(email);
    authCacheService.updateCachedUser(user);
    return ResponseBuilder.success("Account activated successfully");
  }

  @Override
  public ResponseEntity<?> login(LoginRequestDto dto, String clientIp) {
    Timer.Sample timer = monitoringService.startLoginTimer();
    try {
      String email = EmailNormalizer.normalize(dto.email());
      // Only FAILED attempts count, per e-mail + client IP: a stranger can no
      // longer lock a user out by spamming logins with the user's address.
      if (loginAttemptService.isBlocked(email, clientIp)) {
        monitoringService.recordLoginFailure("locked");
        monitoringService.recordRateLimitRejection("login");
        throw new RateLimitExceededException("Too many failed login attempts. Please try again later.");
      }

      User user = authenticate(email, dto.password(), clientIp);
      loginAttemptService.reset(email, clientIp);

      String accessToken = jwtService.generateToken(user);
      String refreshToken = createAndStoreRefreshToken(user);

      var response = authMapper.userToLoginResponseDto(user, accessToken, refreshToken);
      monitoringService.recordLoginSuccess();
      return ResponseBuilder.success("Login successful", response);
    } finally {
      monitoringService.stopLoginTimer(timer);
    }
  }

  @Override
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token = jwtFunction.extractTokenFromHeader(request);
    if (token == null) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    Claims claims = parseAccessToken(token);
    User user = userRepository.findByEmail(claims.getSubject())
        .orElseThrow(() -> new InvalidTokenException(INVALID_ACCESS_TOKEN));

    long remainingSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
    if (remainingSeconds > 0) {
      redisService.blacklistToken(token, remainingSeconds);
    }
    refreshTokenRepository.revokeAllByUser(user);

    SecurityContextHolder.clearContext();
    monitoringService.recordLogout();
    log.info("User {} logged out", user.getId());
    return ResponseBuilder.success("User logged out successfully");
  }

  @Override
  public ResponseEntity<?> refreshToken(RefreshTokenDto dto) {
    String presented = dto.refreshToken();
    if (!jwtService.isRefreshToken(presented)) {
      throw rejectRefresh();
    }
    RefreshToken stored = refreshTokenRepository.findByToken(presented)
        .filter(token -> !token.isRevoked())
        .filter(token -> token.getExpiryDate() != null && token.getExpiryDate().isAfter(Instant.now()))
        .orElseThrow(this::rejectRefresh);

    User user = stored.getUser();
    // A DELETED / INACTIVE / PENDING account must not keep minting tokens.
    if (user.getStatus() != EUserStatus.ACTIVE) {
      throw rejectRefresh();
    }

    TokenResponseDto tokens = transactionTemplate.execute(status -> {
      // Conditional UPDATE: of two concurrent refreshes with this token, one wins.
      if (refreshTokenRepository.revokeIfActive(presented) != 1) {
        throw rejectRefresh();
      }
      return authMapper.newTokenToTokenResponseDto(jwtService.generateToken(user), createAndStoreRefreshToken(user));
    });
    monitoringService.recordTokenRefresh(true);
    return ResponseBuilder.success("Token refreshed successfully", tokens);
  }

  @Override
  public ResponseEntity<?> forgotPassword(String rawEmail) {
    String email = EmailNormalizer.normalize(rawEmail);
    throttleByEmail(email);
    userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE)
        .ifPresent(user -> otpEmailService.sendOtpEmailAsync(email));
    return ResponseBuilder.success("If your email is registered, you will receive an OTP shortly");
  }

  @Override
  public ResponseEntity<?> resetPassword(ResetPasswordRequestDto dto) {
    String email = EmailNormalizer.normalize(dto.email());
    throttleByEmail(email);

    // Unknown e-mail, non-ACTIVE account and wrong OTP all get the same 400.
    boolean otpValid = otpFunction.validateOtp(email, dto.otp());
    Optional<User> active = userRepository.findByEmail(email)
        .filter(user -> user.getStatus() == EUserStatus.ACTIVE);
    if (!otpValid || active.isEmpty()) {
      monitoringService.recordOtpFailure("password_reset");
      throw new BadRequestException(INVALID_OTP);
    }

    User user = active.get();
    String encodedPassword = passwordEncoder.encode(dto.newPassword()); // BCrypt outside the transaction
    transactionTemplate.executeWithoutResult(status -> {
      user.setPassword(encodedPassword);
      userRepository.save(user);
      refreshTokenRepository.revokeAllByUser(user);
    });
    otpFunction.removeOtp(email);
    return ResponseBuilder.success("Password reset successfully");
  }

  @Override
  public TokenResponseDto issueTokensForOAuth2Login(String rawEmail) {
    // Re-checked here even though CustomOAuth2UserService already refused
    // non-ACTIVE accounts: the status may have changed in between.
    User user = Optional.ofNullable(EmailNormalizer.normalize(rawEmail))
        .flatMap(userRepository::findByEmail)
        .filter(candidate -> candidate.getStatus() == EUserStatus.ACTIVE)
        .orElseThrow(() -> new UserNotActiveException(ACCOUNT_NOT_ACTIVE));

    String accessToken = jwtService.generateToken(user);
    String refreshToken = createAndStoreRefreshToken(user);
    monitoringService.recordOAuth2Login(true);
    log.info("OAuth2 login for user {}", user.getId());
    return authMapper.newTokenToTokenResponseDto(accessToken, refreshToken);
  }

  /** Parses a bearer token for logout. Anything unusable is a 401, never a 500. */
  private Claims parseAccessToken(String token) {
    Claims claims;
    try {
      claims = jwtService.parseClaims(token);
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    if (!JwtServiceInterface.ACCESS_TOKEN_TYPE.equals(
        claims.get(JwtServiceInterface.TOKEN_TYPE_CLAIM, String.class))) {
      throw new InvalidTokenException(INVALID_ACCESS_TOKEN);
    }
    return claims;
  }

  private InvalidTokenException rejectRefresh() {
    monitoringService.recordTokenRefresh(false);
    return new InvalidTokenException(INVALID_REFRESH_TOKEN);
  }

  private User authenticate(String email, String password, String clientIp) {
    try {
      Authentication authenticated = authenticationManager
          .authenticate(new UsernamePasswordAuthenticationToken(email, password));
      return (User) authenticated.getPrincipal();
    } catch (UserNotActiveException ex) {
      // Thrown only after the password matched (ApplicationConfig): not a guess.
      monitoringService.recordLoginFailure("not_active");
      throw ex;
    } catch (AuthenticationException ex) {
      // Unknown e-mail, DELETED account and wrong password look the same.
      loginAttemptService.recordFailure(email, clientIp);
      monitoringService.recordLoginFailure("bad_credentials");
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }
  }

  private String createAndStoreRefreshToken(User user) {
    String refreshToken = jwtService.generateRefreshToken(user);

    RefreshToken tokenEntity = authMapper.userToRefreshToken(user, refreshToken,
        Instant.now().plusMillis(jwtProperties.getRefreshExpiration()));

    refreshTokenRepository.save(tokenEntity);
    return refreshToken;
  }
}
```

- [ ] **Step 7: Wire the OIDC user service and document sticky sessions**

In `src/main/java/com/spring/app/configs/SecurityConfig.java`, replace the `oauth2Login` method and its Javadoc (lines 145-166) with:

```java
  /**
   * Configures OAuth2 login settings for the application.
   *
   * <p>
   * Google is registered with the {@code openid} scope, so Spring Security
   * resolves the user through the OIDC user service; both services are set so
   * the custom checks run either way.
   * </p>
   *
   * <p>
   * <b>Sticky sessions required:</b> the authorization request (state, nonce)
   * is kept in the in-memory {@code HttpSession} between the redirect to the
   * provider and the callback. With more than one instance the load balancer
   * must route both to the same one (there is deliberately no spring-session).
   * </p>
   *
   * @param oauth2 The OAuth2LoginConfigurer to configure.
   * @return The configured OAuth2LoginConfigurer.
   */
  private OAuth2LoginConfigurer<HttpSecurity> oauth2Login(OAuth2LoginConfigurer<HttpSecurity> oauth2) {
    return oauth2
        .authorizationEndpoint(authorization -> authorization
            .baseUri(WhitelistUrlConstant.OAUTH2_LOGIN_URL))
        .redirectionEndpoint(redirection -> redirection
            .baseUri(WhitelistUrlConstant.OAUTH2_REDIRECT_URL))
        .userInfoEndpoint(userInfo -> userInfo
            .userService(customOAuth2UserService)
            .oidcUserService(customOAuth2UserService::loadOidcUser))
        .successHandler(oAuth2AuthenticationSuccessHandler)
        .failureHandler(oAuth2AuthenticationFailureHandler);
  }
```

- [ ] **Step 8: Confirm the old patterns are gone**

Run:

```bash
grep -n "Map.of\|new RuntimeException\|log.info(\"Creating new user" src/main/java/com/spring/app/shared/services/CustomOAuth2UserService.java
grep -rn "JwtService\b\|UserRepository\|getMessage()" src/main/java/com/spring/app/handlers
```

Expected: no output from either command.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=CustomOAuth2UserServiceTest,OAuth2HandlersTest,AuthPropertiesTest,AuthServiceImplTest,BaseSpringApplicationTests`
Expected: exit 0, no failures (`contextLoads` proves the new handler graph has no dependency cycle and the OIDC wiring compiles).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/CustomOAuth2UserService.java \
  src/main/java/com/spring/app/handlers \
  src/main/java/com/spring/app/configs/properties/AuthProperties.java \
  src/main/resources/application.yml src/test/resources/application-test.yml \
  src/main/java/com/spring/app/modules/auth/services/AuthServiceInterface.java \
  src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java \
  src/main/java/com/spring/app/configs/SecurityConfig.java \
  src/test/java/com/spring/app/shared/services/CustomOAuth2UserServiceTest.java \
  src/test/java/com/spring/app/handlers/OAuth2HandlersTest.java \
  src/test/java/com/spring/app/configs/properties/AuthPropertiesTest.java \
  src/test/java/com/spring/app/modules/auth/services/impl/AuthServiceImplTest.java
git commit -m "$(cat <<'EOF'
fix(oauth2): verified e-mail, status checks, redirect to the frontend

The custom user service was never called for Google (openid scope routes
through the OIDC service); it is now wired for both. It requires
email_verified, refuses non-ACTIVE accounts, never NPEs on a missing
avatar and raises OAuth2AuthenticationException so failures reach the
failure handler. Tokens are issued by AuthServiceInterface and the
browser is redirected to frontend-url with them (or an error code) in
the URL fragment; exception messages are no longer echoed.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Explicit whitelist, ADMIN-only actuator, method security

Covers spec: "**`/api/v1/auth/**` all public** and JWT filter skips them (`WhitelistUrlConstant.java:12`, `JwtAuthenticatorFilter.java:54-73`). List public auth endpoints explicitly; single source of truth for the whitelist shared by `SecurityConfig` and the filter. Logout with an expired/garbled token → 500 via JJWT exception — handle as 401." (filter part done in Task 2); "**Swagger whitelist**: `springdoc.api-docs.path=/api-docs` but whitelist has only `/v3/api-docs/**` → add `/api-docs/**`"; "**Actuator**: `/actuator/prometheus` requires any user JWT → restrict to ADMIN (`hasRole('ADMIN')`)"; "**No Redis sessions / RBAC**: no `@PreAuthorize` anywhere — enable method security and use it for the ADMIN actuator rule / any admin endpoint". Method security is already enabled (`@EnableMethodSecurity` on `SecurityConfig`); actuator endpoints are not controller methods, so the ADMIN rule is a URL rule, and the test proves `@PreAuthorize` is enforced for future admin endpoints.

**Files:**
- Modify: `src/main/java/com/spring/app/constants/WhitelistUrlConstant.java:1-23` (whole file)
- Modify: `src/main/java/com/spring/app/configs/SecurityConfig.java:90-108` (`authorizeHttpRequests` and its Javadoc)
- Test: `src/test/java/com/spring/app/configs/SecurityRulesIntegrationTest.java` (create)

**Interfaces:**
- Consumes: everything above; `ApplicationConstants.AUTH_BASE_PATH` / `FILES_BASE_PATH` (read only); `ERole.ADMIN` (read only).
- Produces: `WhitelistUrlConstant.PUBLIC_POST_URLS`, `PUBLIC_URLS` (OAuth2 handshake, any method), `PUBLIC_GET_URLS`, `ADMIN_URLS`; `OAUTH2_LOGIN_URL`, `OAUTH2_REDIRECT_URL` unchanged; `AUTH_/UPLOAD_/API_ENDPOINTS_RATELIMIT` kept unchanged for P4/P6.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/configs/SecurityRulesIntegrationTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;
import com.spring.app.configs.properties.JwtProperties;
import com.spring.app.enums.ERole;
import com.spring.app.enums.EUserStatus;
import com.spring.app.modules.auth.entities.User;
import com.spring.app.modules.auth.repositories.RefreshTokenRepository;
import com.spring.app.modules.auth.repositories.UserRepository;
import com.spring.app.modules.auth.services.LoginAttemptService;
import com.spring.app.shared.interfaces.JwtServiceInterface;
import com.spring.app.shared.interfaces.RedisServiceInterface;
import com.spring.app.shared.services.JwtService;

/**
 * The real SecurityConfig, JWT filter, rate-limit filter, exception handling
 * and actuator, on H2. Redis is never contacted: RedisServiceInterface (token
 * blacklist + rate-limit counters) and LoginAttemptService are mocks, so
 * `mvn test` needs nothing running. @AutoConfigureObservability keeps the
 * Prometheus registry that Spring Boot otherwise disables in tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "springdoc.api-docs.enabled=true",
    "management.endpoints.web.exposure.include=health,info,prometheus"
})
@Import(SecurityRulesIntegrationTest.ProbeController.class)
class SecurityRulesIntegrationTest {

  /** Test-only endpoints. Nested in a test class, so not component-scanned elsewhere. */
  @RestController
  @RequestMapping("/api/v1/test-probe")
  public static class ProbeController {

    @GetMapping("/me")
    public String me(Authentication authentication) {
      return authentication.getName();
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String admin() {
      return "admin-ok";
    }
  }

  private static final String PASSWORD = "Str0ng!Pass";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private JwtServiceInterface jwtService;
  @Autowired private JwtProperties jwtProperties;
  @Autowired private PasswordEncoder passwordEncoder;

  @MockitoBean private RedisServiceInterface redisService;
  @MockitoBean private LoginAttemptService loginAttemptService;

  private User activeUser;
  private User adminUser;
  private User inactiveUser;

  @BeforeEach
  void setUp() {
    activeUser = save("active@example.com", ERole.USER, EUserStatus.ACTIVE);
    adminUser = save("admin@example.com", ERole.ADMIN, EUserStatus.ACTIVE);
    inactiveUser = save("inactive@example.com", ERole.USER, EUserStatus.INACTIVE);
  }

  @AfterEach
  void tearDown() {
    refreshTokenRepository.deleteAll();
    userRepository.deleteAll();
  }

  private User save(String email, ERole role, EUserStatus status) {
    return userRepository.save(User.builder()
        .email(email).password(passwordEncoder.encode(PASSWORD)).fullName("Test User")
        .role(role).status(status).build());
  }

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  // ---- JWT filter ----------------------------------------------------------------

  @Test
  void anAccessTokenAuthenticatesAProtectedRequest() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(jwtService.generateToken(activeUser))))
        .andExpect(status().isOk())
        .andExpect(content().string("active@example.com"));
  }

  @Test
  void noTokenIs401() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void aRefreshTokenIsNotABearerToken() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me")
            .header(AUTHORIZATION, bearer(jwtService.generateRefreshToken(activeUser))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aGarbledTokenIs401NotAServerError() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer("not-a-jwt")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anExpiredTokenIs401() throws Exception {
    JwtProperties expired = new JwtProperties();
    expired.setSecretKey(jwtProperties.getSecretKey());
    expired.setExpiration(-60_000);
    String token = new JwtService(expired).generateToken(activeUser);

    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aBlacklistedTokenIs401() throws Exception {
    String token = jwtService.generateToken(activeUser);
    when(redisService.isTokenBlacklisted(token)).thenReturn(true);

    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(token)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aTokenOfANonActiveAccountIs401() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(jwtService.generateToken(inactiveUser))))
        .andExpect(status().isUnauthorized());
  }

  // ---- whitelist -------------------------------------------------------------------

  @Test
  void logoutNeedsAValidAccessToken() throws Exception {
    mockMvc.perform(delete("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/v1/auth/logout").header(AUTHORIZATION, bearer("not-a-jwt")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutWithAValidTokenBlacklistsIt() throws Exception {
    String token = jwtService.generateToken(activeUser);

    mockMvc.perform(delete("/api/v1/auth/logout").header(AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
    verify(redisService).blacklistToken(eq(token), anyLong());
  }

  @Test
  void publicAuthEndpointsNeedNoToken() throws Exception {
    // Reaches the controller: bean validation answers 400, not the entry point's 401.
    mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void onlyTheListedMethodIsPublic() throws Exception {
    mockMvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
  }

  @Test
  void loginEndToEndIssuesAWorkingAccessToken() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"Active@Example.com\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");

    verify(loginAttemptService).reset("active@example.com", "127.0.0.1");
    mockMvc.perform(get("/api/v1/test-probe/me").header(AUTHORIZATION, bearer(accessToken)))
        .andExpect(status().isOk());
  }

  @Test
  void swaggerApiDocsArePublic() throws Exception {
    mockMvc.perform(get("/api-docs")).andExpect(status().isOk());
  }

  @Test
  void actuatorInfoAndHealthArePublic() throws Exception {
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    int health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
    assertThat(health).as("health may be 200 or 503 without Redis, but never 401/403").isNotIn(401, 403);
  }

  // ---- RBAC ------------------------------------------------------------------------

  @Test
  void prometheusIsAdminOnly() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, bearer(jwtService.generateToken(activeUser))))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/actuator/prometheus").header(AUTHORIZATION, bearer(jwtService.generateToken(adminUser))))
        .andExpect(status().isOk());
  }

  @Test
  void methodSecurityIsEnforced() throws Exception {
    mockMvc.perform(get("/api/v1/test-probe/admin").header(AUTHORIZATION, bearer(jwtService.generateToken(activeUser))))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/test-probe/admin").header(AUTHORIZATION, bearer(jwtService.generateToken(adminUser))))
        .andExpect(status().isOk())
        .andExpect(content().string("admin-ok"));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=SecurityRulesIntegrationTest`
Expected: FAIL — `onlyTheListedMethodIsPublic` (GET on `/api/v1/auth/login` is still permitted, so the request reaches MVC and is not a 401), `swaggerApiDocsArePublic` (401 instead of 200), `prometheusIsAdminOnly` (USER gets 200 instead of 403). The JWT-filter tests and `logoutNeedsAValidAccessToken` already pass (Task 2 made the service answer 401), which is expected.

- [ ] **Step 3: Implement**

Replace the whole of `src/main/java/com/spring/app/constants/WhitelistUrlConstant.java` with:

```java
package com.spring.app.constants;

/**
 * Single source of truth for which URLs skip authentication and which need
 * ADMIN. Only {@code SecurityConfig} reads the PUBLIC_* / ADMIN_* arrays;
 * {@code JwtAuthenticatorFilter} keeps no copy (it authenticates whenever a
 * bearer token is present and leaves the decision to these rules).
 */
public final class WhitelistUrlConstant {

  private WhitelistUrlConstant() {
  }

  public static final String OAUTH2_LOGIN_URL    = ApplicationConstants.AUTH_BASE_PATH + "/oauth2/authorization";
  public static final String OAUTH2_REDIRECT_URL = ApplicationConstants.AUTH_BASE_PATH + "/oauth2/code/google";

  // Rate-limit path groups, read by the rate-limit code (not by SecurityConfig).
  public static final String[] AUTH_ENDPOINTS_RATELIMIT   = { ApplicationConstants.AUTH_BASE_PATH + "/**" };
  public static final String[] UPLOAD_ENDPOINTS_RATELIMIT = { ApplicationConstants.FILES_BASE_PATH + "/**" };
  public static final String[] API_ENDPOINTS_RATELIMIT    = {};

  /**
   * Anonymous auth endpoints, POST only. logout is deliberately absent: it
   * needs a valid access token.
   */
  public static final String[] PUBLIC_POST_URLS = {
      ApplicationConstants.AUTH_BASE_PATH + "/register",
      ApplicationConstants.AUTH_BASE_PATH + "/resend-otp",
      ApplicationConstants.AUTH_BASE_PATH + "/activate",
      ApplicationConstants.AUTH_BASE_PATH + "/login",
      ApplicationConstants.AUTH_BASE_PATH + "/refresh-token",
      ApplicationConstants.AUTH_BASE_PATH + "/forgot-password",
      ApplicationConstants.AUTH_BASE_PATH + "/reset-password"
  };

  /** OAuth2 handshake endpoints (any method). */
  public static final String[] PUBLIC_URLS = {
      OAUTH2_LOGIN_URL + "/**",
      OAUTH2_REDIRECT_URL
  };

  public static final String[] PUBLIC_GET_URLS = {
      "/api-docs/**",        // springdoc.api-docs.path (springdoc config is owned by P4)
      "/v3/api-docs/**",
      "/swagger-ui/**",
      "/swagger-ui.html",
      "/actuator/health",
      "/actuator/health/**",
      "/actuator/info"
  };

  /** Every other actuator endpoint (prometheus, ...) is ADMIN-only. */
  public static final String[] ADMIN_URLS = {
      "/actuator/**"
  };
}
```

In `src/main/java/com/spring/app/configs/SecurityConfig.java`, add `import com.spring.app.enums.ERole;` and replace `authorizeHttpRequests` and its Javadoc (lines 90-108) with:

```java
  /**
   * Configures authorization settings for the application.
   *
   * <p>
   * Order matters (first match wins): the explicit public lists from
   * {@link WhitelistUrlConstant}, then ADMIN for the remaining actuator
   * endpoints, then "authenticated" for everything else. Method security
   * ({@code @EnableMethodSecurity}) is on for {@code @PreAuthorize} on future
   * admin endpoints; actuator endpoints are not controller methods, hence the
   * URL rule.
   * </p>
   *
   * @param auth The AuthorizationManagerRequestMatcherRegistry to configure.
   */
  private void authorizeHttpRequests(
      AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
    auth
      .requestMatchers(HttpMethod.POST, WhitelistUrlConstant.PUBLIC_POST_URLS).permitAll()
      .requestMatchers(WhitelistUrlConstant.PUBLIC_URLS).permitAll()
      .requestMatchers(HttpMethod.GET, WhitelistUrlConstant.PUBLIC_GET_URLS).permitAll()
      .requestMatchers(WhitelistUrlConstant.ADMIN_URLS).hasRole(ERole.ADMIN.name())
      .anyRequest().authenticated();
  }
```

- [ ] **Step 4: Confirm the filter no longer reads the whitelist**

Run: `grep -rn "WhitelistUrlConstant" src/main/java`
Expected: `SecurityConfig.java`, `WhitelistUrlConstant.java` itself and `RateLimitFilter.java` (it reads the `*_RATELIMIT` arrays until P4 moves them); no line in `JwtAuthenticatorFilter.java`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=SecurityRulesIntegrationTest`
Expected: exit 0, no failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/constants/WhitelistUrlConstant.java \
  src/main/java/com/spring/app/configs/SecurityConfig.java \
  src/test/java/com/spring/app/configs/SecurityRulesIntegrationTest.java
git commit -m "$(cat <<'EOF'
fix(security): explicit whitelist, ADMIN-only actuator metrics

/api/v1/auth/** is no longer public as a whole: only the seven anonymous
POST endpoints and the OAuth2 handshake are; logout needs a valid access
token. /api-docs (the configured springdoc path) is whitelisted.
/actuator/** apart from health and info requires ROLE_ADMIN. An
integration test drives the real filter chain, actuator and
@PreAuthorize on H2 with Redis mocked.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Full verification

**Files:** none modified (verification only).

- [ ] **Step 1: Run the whole suite**

Run: `mvn -q test`
Expected: exit 0, no failures, with nothing running locally (no MySQL, Redis, SMTP, Docker).

- [ ] **Step 2: Spot-check the spec items by grep**

```bash
cd /home/nhatnx/my-project/spring-boot-boilerplate
grep -rn "spring.profiles.active" src/main/java                                   # expect: nothing
grep -n "Transactional" src/main/java/com/spring/app/modules/auth/services/impl/AuthServiceImpl.java   # expect: nothing
grep -rn "auth.login.attempts\|incrementLoginAttempts" src/main/java               # expect: nothing
grep -n "StrongPassword" src/main/java/com/spring/app/modules/auth/dto/request/LoginRequestDto.java   # expect: nothing
grep -rn "a-zA-Z\\\\s" src/main/java/com/spring/app/modules                        # expect: nothing (old name regex)
grep -n "\"/v3/api-docs/\*\*\"\|\"/api-docs/\*\*\"" src/main/java/com/spring/app/constants/WhitelistUrlConstant.java   # expect: both
grep -rn "token_type\|TOKEN_TYPE_CLAIM" src/main/java | head                        # expect: JwtServiceInterface, JwtService, JwtFunctionUtil, AuthServiceImpl
```

- [ ] **Step 3: Confirm config completeness for the new keys**

```bash
grep -n "skip-email-verification\|login-max-failures\|login-failure-window\|oauth2-redirect-path" \
  src/main/resources/application.yml src/test/resources/application-test.yml
```

Expected: four lines in each file; the test-file values are literals (no `${`).

- [ ] **Step 4: No commit needed** (nothing changed). If Step 1 or 2 fails, fix inside the owning task's files and amend with a new commit, not a rewrite of history.

---

## Hand-offs (files this plan does not own)

| Owner | What | Why |
|---|---|---|
| **P4** (`GlobalExceptionHandler`) | Keep the explicit `UserNotActiveException` → 403 handler from P4 Task 1. Note `UserNotActiveException` now `extends BadRequestException` (a more specific handler wins, so 403 still applies). `InvalidTokenException` (`com.spring.app.modules.auth.exceptions`) extends `BadCredentialsException` → covered by P4's `AuthenticationException` → 401 mapping; P4 Task 10 Step 2 is not needed for it. | Spec allowed cross-edit. |
| **P4** (`RateLimitFilter`) | Call `MonitoringService#recordRateLimitRejection(String bucket)` (e.g. `bucket.name().toLowerCase()`) when the per-IP filter rejects a request. | Spec Metrics bullet: "rate-limit rejection counters"; P3 wires only the rejections it owns (per-e-mail throttle, login lock). |
| **P4** (`RateLimitManagerService`) | Keep `checkRateLimitAndThrow(ERateLimitEndpoint, String)` (called by `AuthServiceImpl`), and keep "allow on Redis error" behaviour: `SecurityRulesIntegrationTest` mocks `RedisServiceInterface` and expects every request to pass the rate limiter. | Test stability. |
| **P4** (`management` yml) | Keep `prometheus` exposed on the main port (the ADMIN rule and `SecurityRulesIntegrationTest` assume it); P4's `show-details: when_authorized` + `roles: ADMIN` matches P3's rule. Since the JWT filter now authenticates on public routes too, an ADMIN token on `/actuator/health` will see details. | Consistency. |
| **P6** (`.env.example`) | Add `AUTH_SKIP_EMAIL_VERIFICATION=false`, `AUTH_LOGIN_MAX_FAILURES=5`, `AUTH_LOGIN_FAILURE_WINDOW=15m`, `AUTH_OAUTH2_REDIRECT_PATH=/oauth2/callback` (with one-line comments). | Global constraint "every new env var is added to `.env.example`". |
| **P6** (docker-compose dev override) | If the dev compose should keep "no OTP" convenience, set `AUTH_SKIP_EMAIL_VERIFICATION=true` there (it only works with the dev profile). | Spec P6 compose bullet. |
| **P6** (CLAUDE.md / README) | (1) Strict binding now covers **seven** leaf classes: add `AuthProperties` (`application.auth.*`). (2) Metric names are `auth.login.success`, `auth.login.failed{reason}`, `auth.registration.attempts`, `auth.otp.failed{purpose}`, `auth.token.refresh{result}`, `auth.logout`, `auth.oauth2.login{result}`, `auth.ratelimit.rejected{bucket}`, timers `auth.login.duration`, `auth.registration.duration` — P6's draft lists only some. (3) Whitelist: `JwtAuthenticatorFilter` does NOT read `WhitelistUrlConstant` (P6's draft says "used by both"); only `SecurityConfig` does. (4) RBAC: `ADMIN` for every `/actuator/**` except health/info; method security enabled, no `@PreAuthorize` endpoint yet. (5) OAuth2: Google via OIDC, `email_verified` required, PENDING/INACTIVE/DELETED refused, redirect to `frontend-url + application.auth.oauth2-redirect-path` with tokens in the fragment; sticky sessions required with >1 instance. (6) The JWT filter loads the user from the DB on every authenticated request (status takes effect immediately). (7) Login lockout: 5 failures / 15 min per (e-mail, IP), successes not counted; Redis outage fails open. (8) `application.frontend-url` IS used (OAuth2 redirect) — keep it (P6 Task 3 Step 13). | Spec P6 docs bullet. |
| **P6** (dead code) | Candidates after P3 (verify with grep first): `JwtServiceInterface`/`JwtService` `extractUserId`, `extractRole`, `extractUserStatus`, `isTokenValid`, `getRemainingExpirationSeconds`, `extractClaim`; `MonitoringService#recordLoginDuration` / `#recordRegistrationDuration`; `UserRepository#existsByEmailAndStatus`, `#existsByEmailAndStatusNot`, `#findByEmailAndStatus`; `RefreshTokenRepository#findAllByUserAndIsRevokedFalse`; `AuthCacheService#getUserByEmail`, `#getRefreshToken`, `#evictCachedToken`, `#evictCachedUser` (P3 now calls only `updateCachedUser`); `WhitelistUrlConstant.*_ENDPOINTS_RATELIMIT` (per P4); `AuthRequestDto`; `ValidationGroups`. `commons-lang3` is still used by `CustomOAuth2UserService` (declare it explicitly). `ERole.MANAGER` is unused by P3. | Spec P6 dead-code bullet. |

## Spec coverage (self-review)

| Spec bullet (P3) | Task |
|---|---|
| Token type claim; filter rejects non-access; refresh accepts only refresh | 2 |
| refreshToken checks user status | 3 |
| CustomOAuth2UserService: `Map.of` NPE, `RuntimeException` wrapping, `email_verified`, PII log | 9 |
| OAuth2 success handler: status, repositories/duplication/concrete JwtService, redirect with fragment, sticky-sessions doc | 9 (doc in `SecurityConfig` Javadoc; README/CLAUDE.md via P6 hand-off) |
| OAuth2 failure handler echoes message | 9 |
| Registration with DELETED e-mail → never hit `uk_users_email` | 3 |
| Account enumeration (login before password; resend/activate/reset 404) | 3 |
| Login lockout DoS (failures only, e-mail + IP) | 8 |
| Passwords > 72 bytes; remove `@StrongPassword` from login | 6 |
| Dev-mode opt-in via property + `Environment.acceptsProfiles` | 5 |
| `/api/v1/auth/**` public; single whitelist; logout expired/garbled → 401 | 2 (filter, service), 10 (rules) |
| Swagger `/api-docs/**` | 10 |
| Actuator prometheus → ADMIN (health details: P4) | 10 |
| Transactions off Redis/BCrypt/JWT | 7 |
| JWT key: Base64 + ≥256-bit at startup, decode once | 1 |
| JWT filter DB hit: keep, check status, document | 2 |
| Metrics: rename success + failed-login, OTP-failure, refresh, logout, OAuth2, rate-limit | 4 (+8 `locked`/`login`, +9 OAuth2; P4 hand-off for the per-IP filter) |
| Logging: PII out of P3 logs and throttle identifiers | 2 (logout logs id), 6 (hashed throttle id), 8 (hashed lockout key), 9 (OAuth2 logs id only) |
| DTO validation: Unicode names, persist phone/address, avatar URL, e-mail normalisation | 6 (+9 for OAuth2 normalisation) |
| RBAC: enable/use method security; MANAGER unused | 10 (MANAGER removal: P6) |

Not implemented, by design: the spec's *optional* "short TTL cache of `UserResponseDto` from P1" for the JWT filter — the filter needs the `User` entity (authorities, status), and P1 deliberately stopped caching entities.
