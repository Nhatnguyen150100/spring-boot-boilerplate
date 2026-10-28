# P4 web-errors-ratelimit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every error leave the API as a correct-status `BaseResponse` without leaking internals, make the rate-limit filter run the downstream chain exactly once and apply the API bucket, stop client-IP spoofing through proxy headers, and clean up Swagger / CORS / health / filter-registration configuration.

**Architecture:** `GlobalExceptionHandler` is rebuilt on Spring MVC's `ResponseEntityExceptionHandler` (framework exceptions get their real 4xx status) and only swaps the body for the existing `BaseResponse` shape. `RateLimitFilter` guards only the rate-limit check, never the chain; bucket path patterns move into the P4-owned `ERateLimitEndpoint`. Tomcat's `RemoteIpValve` internal-proxies regex is derived from the same `application.trusted-proxies` list the filter already uses, so there is one source of truth for "which peer may set X-Forwarded-For".

**Tech Stack:** Java 21, Spring Boot 3.5.0 (Spring Framework 6.2.7, Tomcat 10.1.41, Spring Security 6.5), springdoc 2.8.8, JJWT 0.12.6, JUnit 5.12, Mockito 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-28-review-remediation-design.md` (section "### P4 web-errors-ratelimit"; Decisions, Global constraints, ownership table and allowed cross-edits are binding).

## Global Constraints

- Java 21, Spring Boot 3.5.x, Maven. `mvn test` must pass with nothing running (no MySQL, no Redis, no SMTP, no Docker).
- Config rules from CLAUDE.md are binding: framework keys under `spring.*`, app keys under `application.*` bound by `configs/properties/*` with `@Validated`; the six leaf properties classes keep `ignoreUnknownFields = false`; every validation-constrained property gets a **literal** override in `src/test/resources/application-test.yml`; every new env var is added to `.env.example`; profile files hold deltas only and never set `spring.profiles.active`.
- Flyway owns the schema (`src/main/resources/db/...`); any schema change is a new versioned migration, never an edit of an applied one.
- TDD: each fix lands with a test that fails before and passes after, unless the change is pure config/docs (then state how it was verified).
- Match surrounding code style: 2-space indent, Lombok, constructor injection, `ResponseBuilder` for HTTP responses.
- Commit per task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Decision 3 (binding): actually apply the existing `API` rate-limit bucket. Do NOT add Bucket4j, spring-session, or structured JSON logging.
- Execution order: P1 → P2 → P3 → **P4** → P5 → P6. P4 may assume P1–P3 are applied. P4 only edits files it owns (table below) plus allowed cross-edits, new test classes in its area, and appends to `application-test.yml`.

### Files P4 owns (from the spec table)

`GlobalExceptionHandler`, other `exceptions/*` not owned elsewhere (`BadRequestException`, `ConflictException`, `RateLimitExceededException`, `ResourceNotFoundException`), `RateLimitFilter`, `RateLimitManagerService`, `RateLimitConfig`, `RateLimitProperties`, `ERateLimitEndpoint`, `SwaggerConfig`, `CorsConfig`, `HealthCheckConfig`, `application.yml` (`springdoc`, `application.rate-limit`, `management`), `application-prod.yml` (`server` section). Allowed cross-edit: map P3 exception types (e.g. `InvalidTokenException`, `UserNotActiveException` → 403) inside `GlobalExceptionHandler`.

New files created by this plan (P4 area): `configs/ProxyHeadersConfig.java`, `configs/FilterRegistrationConfig.java`, and the test classes listed per task.

**NOT touched** (owned elsewhere): `WhitelistUrlConstant`, `SecurityConfig`, `JwtAuthenticatorFilter`, `UserNotActiveException`, `CustomAuthenticationEntryPoint` (P3); `RedisService`, `RedisServiceInterface` (P1); `ApplicationProperties`, `ApplicationConstants`, `.env.example`, `application-dev.yml`, `ResponseBuilder`, `BaseResponse` (read only).

### Line numbers

Line ranges are at HEAD `92eac07`. P1/P2 edit other sections of `application.yml` and `application-prod.yml` before P4 runs, so for YAML always anchor the edit on the quoted text, not the line number.

---

## File Structure

| File | Responsibility after P4 |
|---|---|
| `src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java` | Rewrite. Extends `ResponseEntityExceptionHandler`; all bodies are `BaseResponse`; generic 5xx text; WARN/INFO for 4xx. |
| `src/main/java/com/spring/app/enums/ERateLimitEndpoint.java` | Rewrite. Bucket enum also carries its path patterns; declaration order = match precedence. |
| `src/main/java/com/spring/app/filter/RateLimitFilter.java` | Rewrite. Check guarded, chain called once outside `try`; bucket from enum; right-most-untrusted X-Forwarded-For. |
| `src/main/java/com/spring/app/shared/services/RateLimitManagerService.java` | Rewrite. Masks e-mail identifiers in every log line; window constants named for what they are. |
| `src/main/java/com/spring/app/configs/properties/RateLimitProperties.java` | Remove unused `burstCapacity`. |
| `src/main/java/com/spring/app/configs/ProxyHeadersConfig.java` | New. Sets Tomcat `RemoteIpValve` internal proxies from `application.trusted-proxies`. |
| `src/main/java/com/spring/app/configs/FilterRegistrationConfig.java` | New. Disables servlet auto-registration of the two `@Component` filters. |
| `src/main/java/com/spring/app/configs/CorsConfig.java` | Rewrite. Single `CorsConfigurationSource`, no `WebMvcConfigurer`, `allowCredentials(false)`. |
| `src/main/java/com/spring/app/configs/SwaggerConfig.java` | Server URL `/` (relative). |
| `src/main/java/com/spring/app/configs/HealthCheckConfig.java` | Deleted (Boot's `db` / `redis` indicators are used). |
| `src/main/resources/application.yml` | `application.rate-limit` (drop `burst-capacity`), `management` (show-details + roles). |
| `src/main/resources/application-prod.yml` | `server` section comment for `forward-headers-strategy: native`. |
| `src/test/resources/application-test.yml` | Append literal `springdoc.api-docs.enabled: true`. |

Tests (all new): `exceptions/GlobalExceptionHandlerTest`, `filter/RateLimitFilterTest`, `shared/services/RateLimitManagerServiceTest`, `configs/properties/RateLimitPropertiesBindingTest`, `configs/ProxyHeadersConfigTest`, `configs/CorsConfigTest`, `configs/WebInfrastructureIntegrationTest` (all under `src/test/java/com/spring/app/`).

---

### Task 1: Rebuild `GlobalExceptionHandler` on `ResponseEntityExceptionHandler`

Covers spec: unmapped `HttpMessageNotReadableException`, `HttpRequestMethodNotSupportedException`, `NoResourceFoundException`, `MaxUploadSizeExceededException` (→413), `MissingServletRequestPartException`, `MethodArgumentTypeMismatchException`, `HttpMediaTypeNotSupportedException`, `HandlerMethodValidationException`, `DataIntegrityViolationException` → no more 500 + SQL leak; `InternalAuthenticationServiceException` / `TransactionSystemException` no longer concatenate messages; `UserNotActiveException` → 403; 4xx logged at WARN/INFO not ERROR; fixed generic message for 5xx; `ResponseBuilder`/`BaseResponse` shape kept. Also maps JJWT's `JwtException` base type → 401 (covers "logout with an expired/garbled token → 500").

**Files:**
- Modify (full rewrite): `src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java:1-263`
- Test (create): `src/test/java/com/spring/app/exceptions/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `ResponseBuilder.error(HttpStatus, String)` → `ResponseEntity<BaseResponse<T>>`; `BaseResponse.error(HttpStatusCode, String)` → `BaseResponse<T>` (fields `statusCode`, `message`, `data` (omitted when null), `timestamp`). Exception classes `ResourceNotFoundException`, `BadRequestException`, `ConflictException`, `RateLimitExceededException`, `UserNotActiveException` (all `RuntimeException` with a `(String)` constructor; `UserNotActiveException` is P3-owned — referenced only).
- Produces: `public class GlobalExceptionHandler extends ResponseEntityExceptionHandler` with a no-arg constructor (Task 2's `RateLimitFilter` keeps resolving `RateLimitExceededException` → 429 through it via `HandlerExceptionResolver`). Package-private constants `GENERIC_SERVER_ERROR = "Internal server error"`, `INVALID_CREDENTIALS = "Invalid email or password"`, `AUTHENTICATION_FAILED = "Authentication failed"`, `ACCOUNT_NOT_ACTIVE = "Account is not active"`, `INVALID_TOKEN = "Invalid or expired token"`, `ACCESS_DENIED = "You do not have permission to access this resource"`, `DATA_CONFLICT = "The request conflicts with existing data"`. Private helper `respond(HttpStatus, String, HttpServletRequest, Exception)` used again in Task 10.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/exceptions/GlobalExceptionHandlerTest.java`:

```java
package com.spring.app.exceptions;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Standalone MockMvc: only the test controller below and the real
 * {@link GlobalExceptionHandler}; no Spring context, no security filters.
 */
class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  // ---------------------------------------------------------------- exceptions thrown by application code

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(delimiter = '|', textBlock = """
      not-found                | 404 | User not found
      bad-request              | 400 | Bad input
      conflict                 | 409 | Already exists
      rate-limit               | 429 | Too many requests from this client
      user-not-active          | 403 | Account is not active
      internal-auth-not-active | 403 | Account is not active
      internal-auth            | 401 | Authentication failed
      bad-credentials          | 401 | Invalid email or password
      username-not-found       | 401 | Invalid email or password
      disabled                 | 401 | Authentication failed
      access-denied            | 403 | You do not have permission to access this resource
      jwt-expired              | 401 | Invalid or expired token
      jwt-malformed            | 401 | Invalid or expired token
      data-integrity           | 409 | The request conflicts with existing data
      tx-validation            | 400 | name is required
      tx-other                 | 500 | Internal server error
      constraint               | 400 | name is required
      max-upload               | 413 | Maximum upload size exceeded
      generic                  | 500 | Internal server error
      """)
  void mapsThrownExceptionToStatusAndBaseResponse(String kind, int status, String message) throws Exception {
    mockMvc.perform(get("/test/throw/{kind}", kind))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.statusCode").value(status))
        .andExpect(jsonPath("$.message").value(message))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @ParameterizedTest
  @ValueSource(strings = { "internal-auth", "data-integrity", "tx-other", "generic", "user-not-active" })
  void neverEchoesInternalDetails(String kind) throws Exception {
    mockMvc.perform(get("/test/throw/{kind}", kind))
        .andExpect(content().string(not(containsString("secret"))))
        .andExpect(content().string(not(containsString("SQL"))))
        .andExpect(content().string(not(containsString("a@b.c"))));
  }

  @Test
  void noResourceFoundIs404() throws Exception {
    mockMvc.perform(get("/test/throw/no-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.statusCode").value(404))
        .andExpect(jsonPath("$.message").exists());
  }

  // ---------------------------------------------------------------- exceptions raised by Spring MVC itself

  @Test
  void malformedJsonIs400() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("Failed to read request"));
  }

  @Test
  void invalidBodyListsFieldErrors() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("name: name is required"));
  }

  @Test
  void unsupportedMediaTypeIs415() throws Exception {
    mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("name"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.statusCode").value(415));
  }

  @Test
  void wrongMethodIs405WithAllowHeader() throws Exception {
    mockMvc.perform(delete("/test/ok"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("GET")))
        .andExpect(jsonPath("$.statusCode").value(405));
  }

  @Test
  void pathVariableTypeMismatchIs400() throws Exception {
    mockMvc.perform(get("/test/items/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400));
  }

  @Test
  void parameterConstraintViolationIs400() throws Exception {
    mockMvc.perform(get("/test/min").param("value", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value("value: value must be at least 5"));
  }

  @Test
  void missingMultipartPartIs400() throws Exception {
    mockMvc.perform(multipart("/test/upload"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.statusCode").value(400))
        .andExpect(jsonPath("$.message").value(containsString("file")));
  }

  // ---------------------------------------------------------------- fixtures

  public static class Payload {
    @NotBlank(message = "name is required")
    public String name;

    public Payload() {
    }

    Payload(String name) {
      this.name = name;
    }
  }

  @RestController
  @RequestMapping("/test")
  static class ThrowingController {

    @GetMapping("/ok")
    public String ok() {
      return "ok";
    }

    @PostMapping(value = "/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public String body(@Valid @RequestBody Payload payload) {
      return payload.name;
    }

    @GetMapping("/items/{id}")
    public String item(@PathVariable("id") Long id) {
      return "item " + id;
    }

    @GetMapping("/min")
    public String min(@RequestParam("value") @Min(value = 5, message = "value must be at least 5") int value) {
      return "ok";
    }

    @PostMapping("/upload")
    public String upload(@RequestPart("file") MultipartFile file) {
      return file.getOriginalFilename();
    }

    @GetMapping("/throw/{kind}")
    public String throwIt(@PathVariable("kind") String kind) throws Exception {
      throw exceptionFor(kind);
    }
  }

  static Exception exceptionFor(String kind) {
    return switch (kind) {
      case "not-found" -> new ResourceNotFoundException("User not found");
      case "bad-request" -> new BadRequestException("Bad input");
      case "conflict" -> new ConflictException("Already exists");
      case "rate-limit" -> new RateLimitExceededException("Too many requests from this client");
      case "user-not-active" -> new UserNotActiveException("User with email a@b.c is not active");
      case "internal-auth-not-active" ->
        new InternalAuthenticationServiceException("wrapped", new UserNotActiveException("inactive"));
      case "internal-auth" -> new InternalAuthenticationServiceException("secret jdbc:mysql://db:3306 refused");
      case "bad-credentials" -> new BadCredentialsException("Bad credentials");
      case "username-not-found" -> new UsernameNotFoundException("User not found with email a@b.c");
      case "disabled" -> new DisabledException("User is disabled");
      case "access-denied" -> new AccessDeniedException("nope");
      case "jwt-expired" -> new ExpiredJwtException(null, null, "JWT expired at 2026-01-01T00:00:00Z");
      case "jwt-malformed" -> new MalformedJwtException("Malformed JWT");
      case "data-integrity" -> new DataIntegrityViolationException(
          "could not execute statement; SQL [insert into users (email) values (?)]; constraint [uk_users_email] secret");
      case "tx-validation" -> new TransactionSystemException("Could not commit JPA transaction", constraintViolation());
      case "tx-other" -> new TransactionSystemException("Could not commit JPA transaction: secret");
      case "constraint" -> constraintViolation();
      case "max-upload" -> new MaxUploadSizeExceededException(10L);
      case "no-resource" -> new NoResourceFoundException(HttpMethod.GET, "missing.txt");
      default -> new IllegalStateException("secret internal detail");
    };
  }

  static ConstraintViolationException constraintViolation() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      Set<ConstraintViolation<Payload>> violations = factory.getValidator().validate(new Payload(""));
      return new ConstraintViolationException(violations);
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL. Among others: `malformedJsonIs400` gets 500 instead of 400, `wrongMethodIs405WithAllowHeader` gets 500, `max-upload` gets 500 instead of 413, `user-not-active` gets 500 (no direct handler) and `internal-auth-not-active` gets 400 instead of 403, `data-integrity` gets 500, `neverEchoesInternalDetails[generic]` fails because the body contains "Server error: secret internal detail".

- [ ] **Step 3: Write the implementation**

Replace the whole content of `src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java` with:

```java
package com.spring.app.exceptions;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.spring.app.common.response.BaseResponse;
import com.spring.app.common.response.ResponseBuilder;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps every exception that escapes a controller to the standard
 * {@link BaseResponse} JSON shape ({@code statusCode}, {@code message},
 * {@code timestamp}).
 *
 * <ul>
 * <li>Spring MVC's own exceptions (unreadable JSON, wrong method, wrong media
 * type, missing part, type mismatch, upload too large, unknown resource, ...)
 * are handled by the {@link ResponseEntityExceptionHandler} base class with
 * their proper 4xx status; {@link #handleExceptionInternal} only swaps the
 * body for a {@link BaseResponse}. Never add an {@code @ExceptionHandler} for
 * a type the base class already lists - Spring then fails at startup with an
 * "Ambiguous @ExceptionHandler method" error.</li>
 * <li>Every 5xx response carries the fixed {@link #GENERIC_SERVER_ERROR}
 * text; the real cause is logged at ERROR with its stack trace.</li>
 * <li>4xx responses are logged at WARN (401/403/429) or INFO (others) with
 * the exception type only. The exception message may contain user input or
 * SQL, so it is logged at DEBUG.</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  static final String GENERIC_SERVER_ERROR = "Internal server error";
  static final String INVALID_CREDENTIALS = "Invalid email or password";
  static final String AUTHENTICATION_FAILED = "Authentication failed";
  static final String ACCOUNT_NOT_ACTIVE = "Account is not active";
  static final String INVALID_TOKEN = "Invalid or expired token";
  static final String ACCESS_DENIED = "You do not have permission to access this resource";
  static final String DATA_CONFLICT = "The request conflicts with existing data";
  static final String VALIDATION_FAILED = "Validation failed";

  // ------------------------------------------------------------------ application exceptions

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<BaseResponse<Void>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
    return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request, ex);
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<BaseResponse<Void>> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
    return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ex);
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<BaseResponse<Void>> handleConflict(ConflictException ex, HttpServletRequest request) {
    return respond(HttpStatus.CONFLICT, ex.getMessage(), request, ex);
  }

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<BaseResponse<Void>> handleRateLimitExceeded(RateLimitExceededException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request, ex);
  }

  /** The account exists and the caller proved who they are, but it may not be used. */
  @ExceptionHandler(UserNotActiveException.class)
  public ResponseEntity<BaseResponse<Void>> handleUserNotActive(UserNotActiveException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.FORBIDDEN, ACCOUNT_NOT_ACTIVE, request, ex);
  }

  // ------------------------------------------------------------------ security

  /** Same text for "no such user" and "wrong password", so callers cannot enumerate accounts. */
  @ExceptionHandler({ BadCredentialsException.class, UsernameNotFoundException.class })
  public ResponseEntity<BaseResponse<Void>> handleBadCredentials(AuthenticationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, request, ex);
  }

  @ExceptionHandler(InternalAuthenticationServiceException.class)
  public ResponseEntity<BaseResponse<Void>> handleInternalAuthenticationService(
      InternalAuthenticationServiceException ex, HttpServletRequest request) {
    if (ex.getCause() instanceof UserNotActiveException) {
      return respond(HttpStatus.FORBIDDEN, ACCOUNT_NOT_ACTIVE, request, ex);
    }
    return respond(HttpStatus.UNAUTHORIZED, AUTHENTICATION_FAILED, request, ex);
  }

  /** Any other Spring Security authentication failure (disabled, locked, expired, ...). */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<BaseResponse<Void>> handleAuthentication(AuthenticationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, AUTHENTICATION_FAILED, request, ex);
  }

  /** Expired, malformed or badly signed JWT reaching a controller (e.g. logout). */
  @ExceptionHandler(JwtException.class)
  public ResponseEntity<BaseResponse<Void>> handleJwt(JwtException ex, HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, INVALID_TOKEN, request, ex);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<BaseResponse<Void>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
    return respond(HttpStatus.FORBIDDEN, ACCESS_DENIED, request, ex);
  }

  // ------------------------------------------------------------------ persistence and bean validation

  /** The message carries the SQL statement and constraint name: never returned, DEBUG-logged only. */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<BaseResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.CONFLICT, DATA_CONFLICT, request, ex);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<BaseResponse<Void>> handleConstraintViolation(ConstraintViolationException ex,
      HttpServletRequest request) {
    return respond(HttpStatus.BAD_REQUEST, violationMessages(ex), request, ex);
  }

  @ExceptionHandler(TransactionSystemException.class)
  public ResponseEntity<BaseResponse<Void>> handleTransaction(TransactionSystemException ex,
      HttpServletRequest request) {
    if (ex.getRootCause() instanceof ConstraintViolationException violations) {
      return respond(HttpStatus.BAD_REQUEST, violationMessages(violations), request, ex);
    }
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request, ex);
  }

  // ------------------------------------------------------------------ fallback

  @ExceptionHandler(Exception.class)
  public ResponseEntity<BaseResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
    return respond(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request, ex);
  }

  // ------------------------------------------------------------------ Spring MVC (ResponseEntityExceptionHandler)

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message = ex.getBindingResult().getAllErrors().stream()
        .map(error -> error instanceof FieldError fieldError
            ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
            : error.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return handleExceptionInternal(ex, BaseResponse.error(status, orDefault(message)), headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message = ex.getParameterValidationResults().stream()
        .flatMap(result -> result.getResolvableErrors().stream()
            .map(error -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
        .collect(Collectors.joining(", "));
    return handleExceptionInternal(ex, BaseResponse.error(status, orDefault(message)), headers, status, request);
  }

  /**
   * Single exit for every exception the base class handles: replaces the
   * ProblemDetail body with a {@link BaseResponse}, keeps the status and
   * headers (e.g. {@code Allow} on 405) the base class chose.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
      HttpStatusCode statusCode, WebRequest request) {
    Object responseBody = body instanceof BaseResponse<?>
        ? body
        : BaseResponse.error(statusCode, clientMessage(ex, body, statusCode));
    logByStatus(statusCode, path(request), ex);
    return super.handleExceptionInternal(ex, responseBody, headers, statusCode, request);
  }

  // ------------------------------------------------------------------ helpers

  private ResponseEntity<BaseResponse<Void>> respond(HttpStatus status, String message, HttpServletRequest request,
      Exception ex) {
    logByStatus(status, request.getRequestURI(), ex);
    return ResponseBuilder.error(status, message);
  }

  private static void logByStatus(HttpStatusCode status, String path, Exception ex) {
    int code = status.value();
    if (status.is5xxServerError()) {
      log.error("{} at {}", code, path, ex);
      return;
    }
    if (code == 401 || code == 403 || code == 429) {
      log.warn("{} {} at {}", code, ex.getClass().getSimpleName(), path);
    } else {
      log.info("{} {} at {}", code, ex.getClass().getSimpleName(), path);
    }
    log.debug("{} detail: {}", code, ex.getMessage());
  }

  /** Message shown to the client for a framework exception. Never the raw exception message. */
  private static String clientMessage(Exception ex, @Nullable Object body, HttpStatusCode status) {
    if (status.is5xxServerError()) {
      return GENERIC_SERVER_ERROR;
    }
    String detail = null;
    if (body instanceof ProblemDetail problemDetail) {
      detail = problemDetail.getDetail();
    } else if (ex instanceof ErrorResponse errorResponse) {
      detail = errorResponse.getBody().getDetail();
    }
    if (StringUtils.hasText(detail)) {
      return detail;
    }
    HttpStatus known = HttpStatus.resolve(status.value());
    return known != null ? known.getReasonPhrase() : "Request failed";
  }

  private static String violationMessages(ConstraintViolationException ex) {
    Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
    if (violations == null || violations.isEmpty()) {
      return VALIDATION_FAILED;
    }
    return violations.stream()
        .map(ConstraintViolation::getMessage)
        .sorted()
        .collect(Collectors.joining(", "));
  }

  private static String orDefault(String message) {
    return StringUtils.hasText(message) ? message : VALIDATION_FAILED;
  }

  private static String path(WebRequest request) {
    if (request instanceof ServletWebRequest servletWebRequest) {
      return servletWebRequest.getRequest().getRequestURI();
    }
    return request.getDescription(false);
  }
}
```

Notes for the implementer:
- The old `@ExceptionHandler(MethodArgumentNotValidException.class)` method is gone on purpose: the base class already handles that type, and keeping both makes Spring fail at startup ("Ambiguous @ExceptionHandler method").
- The old `MessagingException` handler is removed: nothing a controller calls throws a checked `jakarta.mail.MessagingException` (P2's mail runs async), and if one ever escapes, the generic 500 handler now covers it without echoing the SMTP message. (This is the "unreachable MessagingException handler" P6 lists as dead code — done here.)

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS (all parameterized cases green, `Tests run: 32, Failures: 0, Errors: 0`).

- [ ] **Step 5: Run the context smoke test (catches ambiguous handler mappings)**

Run: `mvn -q test -Dtest=BaseSpringApplicationTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java \
        src/test/java/com/spring/app/exceptions/GlobalExceptionHandlerTest.java
git commit -m "$(cat <<'EOF'
fix(errors): map MVC/security/data exceptions, stop leaking internals

GlobalExceptionHandler now extends ResponseEntityExceptionHandler so
framework exceptions keep their 4xx status (400/405/413/415/404) instead
of falling into the generic 500 that echoed ex.getMessage() (SQL included).
5xx always returns a fixed message; 4xx are logged at WARN/INFO.
UserNotActiveException -> 403, JwtException -> 401,
DataIntegrityViolationException -> 409 with a generic message.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `RateLimitFilter` — chain runs once, API bucket applied, no X-Forwarded-For spoofing

Covers spec: "RateLimitFilter.java:60 `catch (Exception)` ... calls `doFilter` again → chain runs twice"; "API bucket never applied (`API_ENDPOINTS_RATELIMIT = {}`) → apply API bucket to `/api/**` not covered by auth/upload"; part of "Proxy IP spoofing" (the filter's own header parsing took the *left-most* X-Forwarded-For entry, which the client controls).

**Files:**
- Modify (full rewrite): `src/main/java/com/spring/app/enums/ERateLimitEndpoint.java:1-16`
- Modify (full rewrite): `src/main/java/com/spring/app/filter/RateLimitFilter.java:1-114`
- Test (create): `src/test/java/com/spring/app/filter/RateLimitFilterTest.java`

**Interfaces:**
- Consumes: `RateLimitManagerService.checkRateLimitAndThrow(ERateLimitEndpoint, String)` (throws `RateLimitExceededException`); `ApplicationProperties.getTrustedProxies()` / `setTrustedProxies(List<String>)` (Lombok `@Data`); `ApplicationConstants.AUTH_BASE_PATH` (`"/api/v1/auth"`), `ApplicationConstants.FILES_BASE_PATH` (`"/api/v1/files"`).
- Produces: `ERateLimitEndpoint` constants in order `AUTH, UPLOAD, API, GLOBAL` with `String getEndpointType()`, `List<String> getPathPatterns()`, `boolean matches(String requestPath, PathMatcher matcher)`. `RateLimitFilter` keeps its constructor `(AntPathMatcher, RateLimitManagerService, HandlerExceptionResolver, ApplicationProperties)` (Lombok `@RequiredArgsConstructor`, field order unchanged) and adds package-private `ERateLimitEndpoint resolveBucket(String requestPath)` and `String getClientIpAddress(HttpServletRequest)`. Redis key format `rate_limit:<BUCKET>:<window>:<identifier>` is unchanged (still uses the enum `name()`).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/filter/RateLimitFilterTest.java`:

```java
package com.spring.app.filter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.RateLimitManagerService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  private static final String CLIENT_IP = "198.51.100.7";

  @Mock
  private RateLimitManagerService rateLimitManagerService;
  @Mock
  private HandlerExceptionResolver handlerExceptionResolver;
  @Mock
  private FilterChain filterChain;
  @Mock
  private MonitoringService monitoringService;

  private final ApplicationProperties applicationProperties = new ApplicationProperties();
  private RateLimitFilter filter;

  @BeforeEach
  void setUp() {
    filter = new RateLimitFilter(new AntPathMatcher(), rateLimitManagerService, handlerExceptionResolver,
        applicationProperties, monitoringService);
  }

  private static MockHttpServletRequest request(String path, String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(remoteAddr);
    return request;
  }

  // ---------------------------------------------------------------- the chain runs exactly once

  @Test
  void downstreamServletExceptionPropagatesAndChainRunsOnce() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    ServletException downstream = new ServletException("downstream failure");
    doThrow(downstream).when(filterChain).doFilter(request, response);

    assertThatThrownBy(() -> filter.doFilter(request, response, filterChain)).isSameAs(downstream);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void downstreamRuntimeExceptionPropagatesAndChainRunsOnce() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    IllegalStateException downstream = new IllegalStateException("expired token further down");
    doThrow(downstream).when(filterChain).doFilter(request, response);

    assertThatThrownBy(() -> filter.doFilter(request, response, filterChain)).isSameAs(downstream);

    verify(filterChain, times(1)).doFilter(request, response);
  }

  @Test
  void rejectedRequestIsResolvedAndNeverReachesTheChain() throws Exception {
    MockHttpServletRequest request = request("/api/v1/auth/login", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    RateLimitExceededException limit = new RateLimitExceededException("limit");
    doThrow(limit).when(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, CLIENT_IP);

    filter.doFilter(request, response, filterChain);

    verify(handlerExceptionResolver).resolveException(request, response, null, limit);
    verify(monitoringService).recordRateLimitRejection("auth");
    verifyNoInteractions(filterChain);
  }

  @Test
  void brokenLimiterFailsOpenAndChainRunsOnce() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();
    doThrow(new IllegalStateException("redis down"))
        .when(rateLimitManagerService).checkRateLimitAndThrow(any(), anyString());

    filter.doFilter(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    verifyNoInteractions(handlerExceptionResolver);
  }

  // ---------------------------------------------------------------- bucket selection

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "/api/v1/auth/login, AUTH",
      "/api/v1/auth/oauth2/authorization/google, AUTH",
      "/api/v1/files/upload, UPLOAD",
      "/api/v1/users/me, API",
      "/api/v2/anything, API",
      "/actuator/health, GLOBAL",
      "/swagger-ui/index.html, GLOBAL"
  })
  void picksBucketByPath(String path, ERateLimitEndpoint expected) throws Exception {
    MockHttpServletRequest request = request(path, CLIENT_IP);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(expected, CLIENT_IP);
    verify(filterChain).doFilter(request, response);
  }

  // ---------------------------------------------------------------- client IP resolution

  @Test
  void forwardedHeadersAreIgnoredFromUntrustedPeers() throws Exception {
    MockHttpServletRequest request = request("/api/v1/users/me", CLIENT_IP);
    request.addHeader("X-Forwarded-For", "203.0.113.9");
    request.addHeader("X-Real-IP", "203.0.113.10");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.API, CLIENT_IP);
  }

  @Test
  void trustedProxyYieldsRightMostUntrustedHop() throws Exception {
    applicationProperties.setTrustedProxies(List.of("10.0.0.1", "10.0.0.2"));
    MockHttpServletRequest request = request("/api/v1/users/me", "10.0.0.1");
    // "6.6.6.6" was typed by the client itself; our proxies appended the rest.
    request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9, 10.0.0.2");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.API, "203.0.113.9");
  }

  @Test
  void trustedProxyWithoutForwardedForFallsBackToRealIp() throws Exception {
    applicationProperties.setTrustedProxies(List.of("10.0.0.1"));
    MockHttpServletRequest request = request("/api/v1/users/me", "10.0.0.1");
    request.addHeader("X-Real-IP", "203.0.113.9");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimitManagerService).checkRateLimitAndThrow(ERateLimitEndpoint.API, "203.0.113.9");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=RateLimitFilterTest`
Expected: FAIL. `downstreamServletExceptionPropagatesAndChainRunsOnce` / `downstreamRuntimeExceptionPropagatesAndChainRunsOnce` fail with "Wanted 1 time ... But was 2 times"; `picksBucketByPath` fails for `/api/v1/users/me` and `/api/v2/anything` (GLOBAL used instead of API); `trustedProxyYieldsRightMostUntrustedHop` fails (identifier `6.6.6.6`).

- [ ] **Step 3: Rewrite `ERateLimitEndpoint`**

Replace the whole content of `src/main/java/com/spring/app/enums/ERateLimitEndpoint.java` with:

```java
package com.spring.app.enums;

import java.util.List;

import org.springframework.util.PathMatcher;

import com.spring.app.constants.ApplicationConstants;

import lombok.Getter;

/**
 * Rate-limit buckets and the request paths each one covers.
 *
 * <p>
 * Declaration order is match precedence: {@code RateLimitFilter} uses the
 * first bucket whose pattern matches the request path. AUTH and UPLOAD must
 * therefore stay before API (they are also under {@code /api/**}), and GLOBAL,
 * which has no pattern, is the fallback for everything else (actuator,
 * Swagger, {@code /oauth2/**}, ...).
 * </p>
 */
@Getter
public enum ERateLimitEndpoint {
  AUTH("auth", ApplicationConstants.AUTH_BASE_PATH + "/**"),
  UPLOAD("upload", ApplicationConstants.FILES_BASE_PATH + "/**"),
  API("api", "/api/**"),
  GLOBAL("global");

  private final String endpointType;
  private final List<String> pathPatterns;

  ERateLimitEndpoint(String endpointType, String... pathPatterns) {
    this.endpointType = endpointType;
    this.pathPatterns = List.of(pathPatterns);
  }

  public boolean matches(String requestPath, PathMatcher matcher) {
    return pathPatterns.stream().anyMatch(pattern -> matcher.match(pattern, requestPath));
  }
}
```

- [ ] **Step 4: Rewrite `RateLimitFilter`**

Replace the whole content of `src/main/java/com/spring/app/filter/RateLimitFilter.java` with:

```java
package com.spring.app.filter;

import java.io.IOException;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.spring.app.configs.properties.ApplicationProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.services.MonitoringService;
import com.spring.app.shared.services.RateLimitManagerService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-client-IP fixed-window rate limiting, run inside the Spring Security
 * chain (see {@code SecurityConfig}). Servlet auto-registration is disabled in
 * {@code FilterRegistrationConfig}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

  private final AntPathMatcher pathMatcher;
  private final RateLimitManagerService rateLimitManagerService;
  private final HandlerExceptionResolver handlerExceptionResolver;
  private final ApplicationProperties applicationProperties;
  private final MonitoringService monitoringService;

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    ERateLimitEndpoint bucket = resolveBucket(request.getRequestURI());
    String clientIp = getClientIpAddress(request);

    // Only the rate-limit check is guarded. The downstream chain is called
    // exactly once, outside any try block: calling it again after a failure
    // would execute the request (e.g. a POST) a second time.
    try {
      rateLimitManagerService.checkRateLimitAndThrow(bucket, clientIp);
    } catch (RateLimitExceededException e) {
      monitoringService.recordRateLimitRejection(bucket.getEndpointType());
      handlerExceptionResolver.resolveException(request, response, null, e);
      return;
    } catch (RuntimeException e) {
      // Fail open: a broken limiter must not take the whole API down.
      log.error("Rate limit check failed for bucket {}; allowing the request", bucket, e);
    }

    filterChain.doFilter(request, response);
  }

  ERateLimitEndpoint resolveBucket(String requestPath) {
    for (ERateLimitEndpoint bucket : ERateLimitEndpoint.values()) {
      if (bucket.matches(requestPath, pathMatcher)) {
        return bucket;
      }
    }
    return ERateLimitEndpoint.GLOBAL;
  }

  /**
   * The peer address, unless the peer is one of {@code application.trusted-proxies}.
   * Then X-Forwarded-For is walked right to left: entries appended by our own
   * proxies are skipped and the first untrusted one is the client. Anything
   * further left was sent by the client itself and can be forged.
   */
  String getClientIpAddress(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (!isTrustedProxy(remoteAddr)) {
      return remoteAddr;
    }

    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (StringUtils.hasText(xForwardedFor)) {
      String[] hops = xForwardedFor.split(",");
      for (int i = hops.length - 1; i >= 0; i--) {
        String hop = hops[i].trim();
        if (hop.isEmpty() || "unknown".equalsIgnoreCase(hop)) {
          return remoteAddr;
        }
        if (!isTrustedProxy(hop)) {
          return hop;
        }
      }
      return remoteAddr;
    }

    String xRealIp = request.getHeader("X-Real-IP");
    if (StringUtils.hasText(xRealIp) && !"unknown".equalsIgnoreCase(xRealIp.trim())) {
      return xRealIp.trim();
    }
    return remoteAddr;
  }

  private boolean isTrustedProxy(String address) {
    List<String> proxies = applicationProperties.getTrustedProxies();
    if (proxies == null || proxies.isEmpty()) {
      return false;
    }
    return proxies.stream()
        .map(String::trim)
        .anyMatch(address::equals);
  }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=RateLimitFilterTest`
Expected: PASS (`Tests run: 14, Failures: 0, Errors: 0`).

- [ ] **Step 6: Confirm nothing else used the old constants and the context still loads**

Run: `grep -rn "_ENDPOINTS_RATELIMIT" src/main/java`
Expected: only the three declarations in `WhitelistUrlConstant.java` (P3-owned; now unused — hand-off to P6, do not edit here).

Run: `mvn -q test -Dtest=BaseSpringApplicationTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spring/app/enums/ERateLimitEndpoint.java \
        src/main/java/com/spring/app/filter/RateLimitFilter.java \
        src/test/java/com/spring/app/filter/RateLimitFilterTest.java
git commit -m "$(cat <<'EOF'
fix(ratelimit): run the filter chain once, apply the API bucket

The catch (Exception) around filterChain.doFilter re-ran the whole chain
after any downstream failure (POST executed twice, expired JWT continued
unauthenticated). Only the limiter check is guarded now. Bucket patterns
live on ERateLimitEndpoint; /api/** not covered by auth/files now uses the
API bucket. X-Forwarded-For is read right to left so a client cannot pick
its own identity by prepending a fake hop.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Mask e-mail identifiers in `RateLimitManagerService` logs

Covers spec: "Rate-limit WARN logs include `email:<addr>` — mask in the code P4 owns." Also renames the misleading `DEFAULT_REQUESTS_PER_*` constants (they are window lengths in seconds, not request counts).

**Files:**
- Modify (full rewrite): `src/main/java/com/spring/app/shared/services/RateLimitManagerService.java:1-160`
- Test (create): `src/test/java/com/spring/app/shared/services/RateLimitManagerServiceTest.java`

**Interfaces:**
- Consumes: `RedisServiceInterface.incrementRateLimit(String key, long windowSeconds)` → `long` (P1 makes it atomic; signature unchanged), `getRateLimitValue(String)`, `deleteRateLimitKey(String)`; `RateLimitProperties.getAuth()/getUpload()/getApi()/getGlobal()` → `BaseRateLimitConfig` with `getRequestsPerMinute()/getRequestsPerHour()/getRequestsPerDay()/isEnabled()` and setters.
- Produces (public API unchanged — P3's `AuthServiceImpl` calls it): `boolean checkRateLimit(ERateLimitEndpoint, String)`, `void checkRateLimitAndThrow(ERateLimitEndpoint, String)`, `long getRemainingRequests(ERateLimitEndpoint, String, String)`, `void resetRateLimit(String, String)`. New: `static String maskIdentifier(String)` (package-private).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/shared/services/RateLimitManagerServiceTest.java`:

```java
package com.spring.app.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.spring.app.configs.properties.RateLimitProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.interfaces.RedisServiceInterface;

@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class RateLimitManagerServiceTest {

  @Mock
  private RedisServiceInterface redisService;

  private final RateLimitProperties properties = new RateLimitProperties();
  private RateLimitManagerService service;

  @BeforeEach
  void setUp() {
    service = new RateLimitManagerService(redisService, properties);
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
  void exceededLimitThrowsAndLogsOnlyTheMaskedEmail(CapturedOutput output) {
    properties.getAuth().setRequestsPerMinute(1);
    when(redisService.incrementRateLimit("rate_limit:AUTH:minute:email:john@example.com", 60L)).thenReturn(2L);

    assertThatThrownBy(() -> service.checkRateLimitAndThrow(ERateLimitEndpoint.AUTH, "email:john@example.com"))
        .isInstanceOf(RateLimitExceededException.class);

    assertThat(output.getAll())
        .contains("email:j***@example.com")
        .doesNotContain("john@example.com");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=RateLimitManagerServiceTest`
Expected: FAIL — compilation error `cannot find symbol: method maskIdentifier(java.lang.String)`.

- [ ] **Step 3: Write the implementation**

Replace the whole content of `src/main/java/com/spring/app/shared/services/RateLimitManagerService.java` with:

```java
package com.spring.app.shared.services;

import com.spring.app.configs.properties.RateLimitProperties;
import com.spring.app.enums.ERateLimitEndpoint;
import com.spring.app.exceptions.RateLimitExceededException;
import com.spring.app.shared.interfaces.RedisServiceInterface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitManagerService {

  private final RedisServiceInterface redisService;
  private final RateLimitProperties rateLimitProperties;

  // Fixed-window lengths, in seconds, for the three quotas of every bucket.
  private static final int MINUTE_WINDOW_SECONDS = 60;
  private static final int HOUR_WINDOW_SECONDS = 3600;
  private static final int DAY_WINDOW_SECONDS = 86400;

  /**
   * Check rate limit for a specific endpoint type
   *
   * @param endpointType Type of endpoint (auth, upload, api, etc.)
   * @param identifier   Unique identifier (IP, user ID, email, etc.)
   * @return true if allowed, false if rate limit exceeded
   */
  public boolean checkRateLimit(ERateLimitEndpoint endpointType, String identifier) {
    try {
      RateLimitProperties.BaseRateLimitConfig config = getConfigForType(endpointType);
      if (config == null || !config.isEnabled()) {
        return true; // Rate limiting disabled for this type
      }

      String minuteKey = String.format("rate_limit:%s:minute:%s", endpointType, identifier);
      String hourKey = String.format("rate_limit:%s:hour:%s", endpointType, identifier);
      String dayKey = String.format("rate_limit:%s:day:%s", endpointType, identifier);

      return checkRateLimit(minuteKey, config.getRequestsPerMinute(), MINUTE_WINDOW_SECONDS) &&
          checkRateLimit(hourKey, config.getRequestsPerHour(), HOUR_WINDOW_SECONDS) &&
          checkRateLimit(dayKey, config.getRequestsPerDay(), DAY_WINDOW_SECONDS);

    } catch (Exception e) {
      log.error("Error checking rate limit for {} with identifier: {}", endpointType, maskIdentifier(identifier), e);
      return true; // Allow request on error
    }
  }

  /**
   * Check rate limit and throw exception if exceeded
   *
   * @param endpointType Type of endpoint
   * @param identifier   Unique identifier
   * @throws RateLimitExceededException if rate limit exceeded
   */
  public void checkRateLimitAndThrow(ERateLimitEndpoint endpointType, String identifier) {
    if (!checkRateLimit(endpointType, identifier)) {
      RateLimitProperties.BaseRateLimitConfig config = getConfigForType(endpointType);
      String message = String.format("Rate limit exceeded for %s. Max %d requests per minute, %d per hour, %d per day",
          endpointType, config.getRequestsPerMinute(), config.getRequestsPerHour(), config.getRequestsPerDay());
      throw new RateLimitExceededException(message);
    }
  }

  /**
   * Get remaining requests for a specific endpoint type
   *
   * @param endpointType Type of endpoint
   * @param time         Time period (minute, hour, day)
   * @param identifier   Unique identifier
   * @return Number of remaining requests for the current time
   */
  public long getRemainingRequests(ERateLimitEndpoint endpointType, String time, String identifier) {
    try {
      if (!"minute".equals(time) && !"hour".equals(time) && !"day".equals(time)) {
        log.warn("Invalid time period: {}. Defaulting to minute.", time);
        time = "minute"; // Default to minute if invalid
      }

      RateLimitProperties.BaseRateLimitConfig config = getConfigForType(endpointType);
      if (config == null)
        return -1;

      int maxRequests;
      if ("minute".equals(time)) {
        maxRequests = config.getRequestsPerMinute();
      } else if ("hour".equals(time)) {
        maxRequests = config.getRequestsPerHour();
      } else {
        maxRequests = config.getRequestsPerDay();
      }

      String timeKey = String.format("rate_limit:%s:%s:%s", endpointType, time, identifier);
      String currentCount = (String) redisService.getRateLimitValue(timeKey);

      if (currentCount == null) {
        return maxRequests;
      }

      int count = Integer.parseInt(currentCount);
      return Math.max(0, maxRequests - count);

    } catch (Exception e) {
      log.error("Error getting remaining requests for {} in {} with identifier: {}", endpointType, time,
          maskIdentifier(identifier), e);
      return -1;
    }
  }

  /**
   * Reset rate limit for a specific identifier
   *
   * @param endpointType Type of endpoint
   * @param identifier   Unique identifier
   */
  public void resetRateLimit(String endpointType, String identifier) {
    try {
      String minuteKey = String.format("rate_limit:%s:minute:%s", endpointType, identifier);
      String hourKey = String.format("rate_limit:%s:hour:%s", endpointType, identifier);
      String dayKey = String.format("rate_limit:%s:day:%s", endpointType, identifier);

      redisService.deleteRateLimitKey(minuteKey);
      redisService.deleteRateLimitKey(hourKey);
      redisService.deleteRateLimitKey(dayKey);

      log.info("Rate limit reset for {} with identifier: {}", endpointType, maskIdentifier(identifier));
    } catch (Exception e) {
      log.error("Error resetting rate limit for {} with identifier: {}", endpointType, maskIdentifier(identifier), e);
    }
  }

  /**
   * Hides the local part of an e-mail inside a rate-limit identifier or key
   * ({@code rate_limit:AUTH:minute:email:john@x.com} becomes
   * {@code rate_limit:AUTH:minute:email:j***@x.com}). Values without an
   * {@code @} (IP addresses) are returned unchanged. Use it for every log line
   * that prints an identifier or key.
   */
  static String maskIdentifier(String identifier) {
    if (identifier == null) {
      return null;
    }
    int at = identifier.indexOf('@');
    if (at < 0) {
      return identifier;
    }
    int localStart = identifier.lastIndexOf(':', at) + 1;
    String local = identifier.substring(localStart, at);
    String visible = local.isEmpty() ? "" : local.substring(0, 1);
    return identifier.substring(0, localStart) + visible + "***" + identifier.substring(at);
  }

  private boolean checkRateLimit(String key, int maxRequests, int windowSeconds) {
    try {
      // Atomic increment avoids the read-then-write race under concurrency, and the
      // window TTL is set only when the counter is first created (fixed window).
      long count = redisService.incrementRateLimit(key, windowSeconds);

      if (count > maxRequests) {
        log.warn("Rate limit exceeded for key: {} (count: {}, max: {})", maskIdentifier(key), count, maxRequests);
        return false;
      }
      return true;

    } catch (Exception e) {
      log.error("Error checking rate limit for key: {}", maskIdentifier(key), e);
      return true; // Allow request on error
    }
  }

  private RateLimitProperties.BaseRateLimitConfig getConfigForType(ERateLimitEndpoint endpointType) {
    return switch (endpointType) {
      case AUTH -> rateLimitProperties.getAuth();
      case UPLOAD -> rateLimitProperties.getUpload();
      case API -> rateLimitProperties.getApi();
      case GLOBAL -> rateLimitProperties.getGlobal();
    };
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=RateLimitManagerServiceTest`
Expected: PASS (`Tests run: 8, Failures: 0, Errors: 0`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/shared/services/RateLimitManagerService.java \
        src/test/java/com/spring/app/shared/services/RateLimitManagerServiceTest.java
git commit -m "$(cat <<'EOF'
fix(ratelimit): mask e-mail identifiers in rate-limit logs

Every log line that prints a rate-limit key or identifier now goes through
maskIdentifier (john@x.com -> j***@x.com). Window-length constants are
renamed for what they are (seconds, not request counts).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Remove the unused `burstCapacity` setting

Covers spec: "`burstCapacity` validated but unused → remove (strict binding: drop from yml/test yml too)". `application-test.yml` has no `burst-capacity` key at HEAD (verify in Step 5), so only `application.yml` changes.

**Files:**
- Modify: `src/main/java/com/spring/app/configs/properties/RateLimitProperties.java:40-42`
- Modify: `src/main/resources/application.yml:187-213` (`application.rate-limit` block)
- Test (create): `src/test/java/com/spring/app/configs/properties/RateLimitPropertiesBindingTest.java`

**Interfaces:**
- Consumes: `RateLimitProperties` (Lombok `@Data`, prefix `application.rate-limit`, `ignoreUnknownFields = false`).
- Produces: `RateLimitProperties.BaseRateLimitConfig` with only `requestsPerMinute`, `requestsPerHour`, `requestsPerDay`, `enabled`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/configs/properties/RateLimitPropertiesBindingTest.java`:

```java
package com.spring.app.configs.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds {@code application.rate-limit} the way Boot does for
 * {@code ignoreUnknownFields = false} (a {@link NoUnboundElementsBindHandler}),
 * without starting a context.
 */
class RateLimitPropertiesBindingTest {

  @Test
  void applicationYmlRateLimitSectionBindsStrictly() throws IOException {
    List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"));
    Binder binder = new Binder(ConfigurationPropertySources.from(yaml));

    RateLimitProperties properties = binder.bind("application.rate-limit",
        Bindable.ofInstance(new RateLimitProperties()), new NoUnboundElementsBindHandler()).get();

    assertThat(properties.getApi().getRequestsPerMinute()).isEqualTo(60);
    assertThat(properties.getAuth().getRequestsPerMinute()).isEqualTo(10);
    assertThat(properties.getUpload().getRequestsPerMinute()).isEqualTo(5);
    assertThat(properties.getGlobal().getRequestsPerMinute()).isEqualTo(100);
  }

  @Test
  void burstCapacityIsNoLongerAKnownKey() {
    Binder binder = new Binder(new MapConfigurationPropertySource(
        Map.of("application.rate-limit.api.burst-capacity", "5")));

    assertThatThrownBy(() -> binder.bind("application.rate-limit",
        Bindable.ofInstance(new RateLimitProperties()), new NoUnboundElementsBindHandler()))
        .isInstanceOf(BindException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=RateLimitPropertiesBindingTest`
Expected: FAIL — `burstCapacityIsNoLongerAKnownKey`: "Expecting code to raise a throwable" (the key still binds). `applicationYmlRateLimitSectionBindsStrictly` passes.

- [ ] **Step 3: Remove the field**

In `src/main/java/com/spring/app/configs/properties/RateLimitProperties.java`, delete these lines (40-42 at HEAD, including the blank line before them):

```java

    @Min(value = 1, message = "burst-capacity must be at least 1")
    private int burstCapacity = 100;
```

so that `requestsPerDay` is followed directly by the blank line and `private boolean enabled = true;`.

- [ ] **Step 4: Remove the keys from `application.yml`**

In `src/main/resources/application.yml`, replace the `rate-limit:` block (anchor on this exact text):

```yaml
  rate-limit:
    global:
      enabled: true
      requests-per-minute: 100
      requests-per-hour: 1000
      requests-per-day: 10000
      burst-capacity: 200
    auth:
      enabled: true
      requests-per-minute: 10
      requests-per-hour: 100
      requests-per-day: 1000
      burst-capacity: 20
    upload:
      enabled: true
      requests-per-minute: 5
      requests-per-hour: 50
      requests-per-day: 500
      burst-capacity: 10
    api:
      enabled: true
      requests-per-minute: 60
      requests-per-hour: 600
      requests-per-day: 6000
      burst-capacity: 100
```

with:

```yaml
  # Buckets (see ERateLimitEndpoint): auth = /api/v1/auth/**, upload =
  # /api/v1/files/**, api = every other /api/**, global = everything else.
  # Fixed windows counted per client IP in Redis.
  rate-limit:
    global:
      enabled: true
      requests-per-minute: 100
      requests-per-hour: 1000
      requests-per-day: 10000
    auth:
      enabled: true
      requests-per-minute: 10
      requests-per-hour: 100
      requests-per-day: 1000
    upload:
      enabled: true
      requests-per-minute: 5
      requests-per-hour: 50
      requests-per-day: 500
    api:
      enabled: true
      requests-per-minute: 60
      requests-per-hour: 600
      requests-per-day: 6000
```

(Keep the two existing comment lines above `rate-limit:` — "Quotas are identical in every environment today..." — and insert the new three comment lines between them and `rate-limit:`.)

- [ ] **Step 5: Verify no other file still sets the key**

Run: `grep -rn "burst" src/ .env.example docker-compose*.yml`
Expected: no output.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -q test -Dtest=RateLimitPropertiesBindingTest,BaseSpringApplicationTests`
Expected: PASS (`Tests run: 3, Failures: 0, Errors: 0`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spring/app/configs/properties/RateLimitProperties.java \
        src/main/resources/application.yml \
        src/test/java/com/spring/app/configs/properties/RateLimitPropertiesBindingTest.java
git commit -m "$(cat <<'EOF'
refactor(ratelimit): drop unused burst-capacity setting

It was validated but never read by the fixed-window limiter. Removed from
RateLimitProperties and application.yml together (strict binding would
reject a leftover key at startup).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Trust forwarded headers only from `application.trusted-proxies` (Tomcat `RemoteIpValve`)

Covers spec: "Proxy IP spoofing: `application-prod.yml:54` `forward-headers-strategy: native` → Tomcat RemoteIpValve trusts XFF from any private-range peer, undoing `TRUSTED_PROXIES`. Set `server.tomcat.remoteip.internal-proxies` explicitly from config, consistent with `application.trusted-proxies`."

Approach: keep `native` (it also gives correct `X-Forwarded-Proto` / `isSecure()` handling) and add a `WebServerFactoryCustomizer` that runs after Boot's `TomcatWebServerFactoryCustomizer` (order 0, which creates the valve) and overwrites the valve's internal-proxies regex with exactly the `TRUSTED_PROXIES` IPs. No new env var: one list drives both Tomcat and `RateLimitFilter`. With an empty list Tomcat's `setInternalProxies(null)` means no peer is trusted (verified in Tomcat 10.1.41 `RemoteIpValve.invoke`: both `internalProxies` and `trustedProxies` null → headers ignored).

**Files:**
- Create: `src/main/java/com/spring/app/configs/ProxyHeadersConfig.java`
- Modify: `src/main/resources/application-prod.yml:46-54` (`server:` section, comment only)
- Test (create): `src/test/java/com/spring/app/configs/ProxyHeadersConfigTest.java`

**Interfaces:**
- Consumes: `ApplicationProperties.getTrustedProxies()` → `List<String>` (exact IP strings, possibly empty); Tomcat `RemoteIpValve#setInternalProxies(String regex)` / `getInternalProxies()`; `TomcatServletWebServerFactory#getEngineValves()`.
- Produces: `ProxyHeadersConfig(ApplicationProperties)`; bean method `RemoteIpValveCustomizer remoteIpValveCustomizer()` (package-private); nested `static final class RemoteIpValveCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered` (`getOrder()` = `Ordered.LOWEST_PRECEDENCE`); `static String toInternalProxiesRegex(List<String>)` → `null` when empty.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/configs/ProxyHeadersConfigTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

import com.spring.app.configs.properties.ApplicationProperties;

class ProxyHeadersConfigTest {

  private static RemoteIpValve customizedValve(List<String> trustedProxies) {
    ApplicationProperties properties = new ApplicationProperties();
    properties.setTrustedProxies(trustedProxies);
    RemoteIpValve valve = new RemoteIpValve();
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
    factory.addEngineValves(valve);

    new ProxyHeadersConfig(properties).remoteIpValveCustomizer().customize(factory);
    return valve;
  }

  @Test
  void stockValveTrustsEveryPrivateAddress() {
    // The problem being fixed: out of the box any 10.x / 192.168.x peer may set X-Forwarded-For.
    String stock = new RemoteIpValve().getInternalProxies();
    assertThat(Pattern.compile(stock).matcher("10.9.8.7").matches()).isTrue();
  }

  @Test
  void onlyConfiguredTrustedProxiesAreInternal() {
    RemoteIpValve valve = customizedValve(List.of("10.0.0.1", " 172.18.0.5 "));

    Pattern internal = Pattern.compile(valve.getInternalProxies());
    assertThat(internal.matcher("10.0.0.1").matches()).isTrue();
    assertThat(internal.matcher("172.18.0.5").matches()).isTrue();
    assertThat(internal.matcher("10.0.0.2").matches()).isFalse();
    assertThat(internal.matcher("10.0.0.10").matches()).isFalse();
    assertThat(internal.matcher("192.168.1.10").matches()).isFalse();
  }

  @Test
  void noTrustedProxiesMeansForwardedHeadersAreNeverTrusted() {
    assertThat(customizedValve(List.of()).getInternalProxies()).isNull();
    assertThat(customizedValve(List.of(" ")).getInternalProxies()).isNull();
  }

  @Test
  void runsAfterBootsOwnTomcatCustomizer() {
    // Boot's TomcatWebServerFactoryCustomizer (order 0) creates the valve; this one must run later.
    assertThat(new ProxyHeadersConfig(new ApplicationProperties()).remoteIpValveCustomizer().getOrder())
        .isGreaterThan(0);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ProxyHeadersConfigTest`
Expected: FAIL — compilation error `cannot find symbol: class ProxyHeadersConfig`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/spring/app/configs/ProxyHeadersConfig.java`:

```java
package com.spring.app.configs;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.spring.app.configs.properties.ApplicationProperties;

import lombok.RequiredArgsConstructor;

/**
 * Makes Tomcat's {@link RemoteIpValve} (created by Boot when
 * {@code server.forward-headers-strategy=native}, i.e. in prod) trust
 * {@code X-Forwarded-For} / {@code X-Forwarded-Proto} only from the peers in
 * {@code application.trusted-proxies} - the same list {@code RateLimitFilter}
 * uses. Tomcat's default trusts every private-range peer, so any container on
 * a shared network could choose its own client IP.
 *
 * <p>
 * Addresses are matched exactly as Tomcat reports the peer
 * ({@code request.getRemoteAddr()}); write IPv6 entries in that full form.
 * With an empty list no peer is trusted and the forwarded headers are ignored.
 * When the strategy is not {@code native} there is no valve and this is a no-op.
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class ProxyHeadersConfig {

  private final ApplicationProperties applicationProperties;

  @Bean
  RemoteIpValveCustomizer remoteIpValveCustomizer() {
    return new RemoteIpValveCustomizer(toInternalProxiesRegex(applicationProperties.getTrustedProxies()));
  }

  /** Exact-match regex for the given IPs, or {@code null} (= trust nobody) when there are none. */
  static String toInternalProxiesRegex(List<String> trustedProxies) {
    if (trustedProxies == null) {
      return null;
    }
    String regex = trustedProxies.stream()
        .map(String::trim)
        .filter(address -> !address.isEmpty())
        .map(Pattern::quote)
        .collect(Collectors.joining("|"));
    return regex.isEmpty() ? null : regex;
  }

  static final class RemoteIpValveCustomizer
      implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private final String internalProxiesRegex;

    RemoteIpValveCustomizer(String internalProxiesRegex) {
      this.internalProxiesRegex = internalProxiesRegex;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
      factory.getEngineValves().stream()
          .filter(RemoteIpValve.class::isInstance)
          .map(RemoteIpValve.class::cast)
          .forEach(valve -> valve.setInternalProxies(internalProxiesRegex));
    }

    /** After Boot's TomcatWebServerFactoryCustomizer (order 0), which adds the valve. */
    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }
}
```

- [ ] **Step 4: Document it in `application-prod.yml`**

In `src/main/resources/application-prod.yml`, replace (anchor on text):

```yaml
    key-store-type: ${SSL_KEYSTORE_TYPE:PKCS12}
  forward-headers-strategy: native
```

with:

```yaml
    key-store-type: ${SSL_KEYSTORE_TYPE:PKCS12}
  # Tomcat's RemoteIpValve applies X-Forwarded-For / X-Forwarded-Proto, but
  # ProxyHeadersConfig limits it to the peers listed in TRUSTED_PROXIES
  # (application.trusted-proxies) - Tomcat's own default would trust every
  # private-range address. Behind a load balancer, set TRUSTED_PROXIES to its
  # IP(s); left empty, the headers are ignored and every client is seen as the
  # load balancer (one shared rate-limit bucket).
  forward-headers-strategy: native
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ProxyHeadersConfigTest,BaseSpringApplicationTests`
Expected: PASS (`Tests run: 5, Failures: 0, Errors: 0`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/configs/ProxyHeadersConfig.java \
        src/main/resources/application-prod.yml \
        src/test/java/com/spring/app/configs/ProxyHeadersConfigTest.java
git commit -m "$(cat <<'EOF'
fix(proxy): trust X-Forwarded-* only from TRUSTED_PROXIES in Tomcat

With forward-headers-strategy=native, Tomcat's RemoteIpValve trusted any
private-range peer, so any container on the network could spoof its client
IP and bypass rate limits. The valve's internal-proxies regex is now built
from application.trusted-proxies, the same list RateLimitFilter uses.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Stop double registration of the `@Component` filters

Covers spec: "`@Component` filters double-registered as servlet filters → `FilterRegistrationBean#setEnabled(false)` for each."

Both `RateLimitFilter` and `JwtAuthenticatorFilter` are `@Component` `OncePerRequestFilter`s added to the Spring Security chain by `SecurityConfig`; Boot also auto-registers every `Filter` bean on the servlet container. A disabled `FilterRegistrationBean` per filter keeps them only in the security chain. The registrations live in a new P4 config class; `JwtAuthenticatorFilter` (P3-owned) is only referenced as a type.

**Files:**
- Create: `src/main/java/com/spring/app/configs/FilterRegistrationConfig.java`
- Test (create): `src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java`

**Interfaces:**
- Consumes: beans `com.spring.app.filter.RateLimitFilter` and `com.spring.app.filter.JwtAuthenticatorFilter`.
- Produces: beans `rateLimitFilterRegistration` (`FilterRegistrationBean<RateLimitFilter>`) and `jwtAuthenticatorFilterRegistration` (`FilterRegistrationBean<JwtAuthenticatorFilter>`), both `isEnabled() == false`. `WebInfrastructureIntegrationTest` is extended in Tasks 8 and 9.

- [ ] **Step 1: Check P3 did not change how `JwtAuthenticatorFilter` is created**

Run: `grep -n "@Component\|class JwtAuthenticatorFilter" src/main/java/com/spring/app/filter/JwtAuthenticatorFilter.java`
Expected: `@Component` and `public class JwtAuthenticatorFilter extends OncePerRequestFilter`. If P3 removed `@Component` (filter built with `new` inside `SecurityConfig`), it is no longer a bean and not auto-registered: drop the `jwtAuthenticatorFilterRegistration` method below, its import, and the `JwtAuthenticatorFilter` part of the test assertion.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.filter.RateLimitFilter;

/**
 * Full context on the test profile (H2, no Redis needed). MockMvc runs without
 * servlet filters, so no request here touches Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class WebInfrastructureIntegrationTest {

  @Autowired
  private ApplicationContext context;

  @Test
  @SuppressWarnings("rawtypes")
  void appFiltersRunOnlyInsideTheSecurityChain() {
    Map<String, FilterRegistrationBean> registrations = context.getBeansOfType(FilterRegistrationBean.class);

    List<Object> disabledFilters = registrations.values().stream()
        .filter(registration -> !registration.isEnabled())
        .map(registration -> (Object) registration.getFilter())
        .toList();

    assertThat(disabledFilters).contains(
        context.getBean(RateLimitFilter.class),
        context.getBean(JwtAuthenticatorFilter.class));
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=WebInfrastructureIntegrationTest`
Expected: FAIL — "Expecting ArrayList: [] to contain: [RateLimitFilter..., JwtAuthenticatorFilter...]".

- [ ] **Step 4: Write the implementation**

Create `src/main/java/com/spring/app/configs/FilterRegistrationConfig.java`:

```java
package com.spring.app.configs;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.filter.RateLimitFilter;

import jakarta.servlet.Filter;

/**
 * The application's filters are {@code @Component}s so they can be injected
 * into {@code SecurityConfig}, which adds them to the Spring Security chain.
 * Boot would ALSO register every {@code Filter} bean directly on the servlet
 * container; these disabled registrations prevent that second copy.
 */
@Configuration
public class FilterRegistrationConfig {

  @Bean
  FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
    return disabled(filter);
  }

  @Bean
  FilterRegistrationBean<JwtAuthenticatorFilter> jwtAuthenticatorFilterRegistration(JwtAuthenticatorFilter filter) {
    return disabled(filter);
  }

  private static <T extends Filter> FilterRegistrationBean<T> disabled(T filter) {
    FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -q test -Dtest=WebInfrastructureIntegrationTest,BaseSpringApplicationTests`
Expected: PASS (`Tests run: 2, Failures: 0, Errors: 0`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/configs/FilterRegistrationConfig.java \
        src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java
git commit -m "$(cat <<'EOF'
fix(web): keep app filters out of the servlet container chain

RateLimitFilter and JwtAuthenticatorFilter are @Component beans added to
the Spring Security chain; Boot also auto-registered them as plain servlet
filters. Disabled FilterRegistrationBeans keep them security-chain only.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: One CORS source, no credentials

Covers spec: "CORS configured twice (security `CorsConfigurationSource` + a `WebMvcConfigurer`) with `allowCredentials(true)` though no cookie auth → one source, `allowCredentials(false)` unless needed." Authentication is a bearer token in the `Authorization` header; there is no cookie auth, so credentials are not needed. `SecurityConfig` (P3) keeps injecting the `CorsConfigurationSource` bean — its name and type do not change.

**Files:**
- Modify (full rewrite): `src/main/java/com/spring/app/configs/CorsConfig.java:1-89`
- Test (create): `src/test/java/com/spring/app/configs/CorsConfigTest.java`

**Interfaces:**
- Consumes: `ApplicationProperties.getCors().getAllowedOrigins()` (`List<String>`), `getMaxAgeSeconds()` (`long`), setters via Lombok `@Data`.
- Produces: bean `CorsConfigurationSource corsConfigurationSource()` (package-private method, unchanged name); no `WebMvcConfigurer` bean.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/spring/app/configs/CorsConfigTest.java`:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.spring.app.configs.properties.ApplicationProperties;

class CorsConfigTest {

  private static CorsConfig corsConfig() {
    ApplicationProperties properties = new ApplicationProperties();
    properties.getCors().setAllowedOrigins(List.of("https://app.example.com"));
    properties.getCors().setMaxAgeSeconds(600);
    return new CorsConfig(properties);
  }

  @Test
  void allowsConfiguredOriginWithoutCredentials() {
    CorsConfiguration configuration = corsConfig().corsConfigurationSource()
        .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/users/me"));

    assertThat(configuration).isNotNull();
    assertThat(configuration.getAllowedOrigins()).containsExactly("https://app.example.com");
    assertThat(configuration.getAllowCredentials()).isFalse();
    assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
    assertThat(configuration.getAllowedHeaders()).contains("Authorization", "Content-Type");
    assertThat(configuration.getMaxAge()).isEqualTo(600L);
  }

  @Test
  void corsIsDeclaredOnlyOnce() {
    assertThat(Arrays.stream(CorsConfig.class.getDeclaredMethods()).map(Method::getReturnType))
        .doesNotContain(WebMvcConfigurer.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=CorsConfigTest`
Expected: FAIL — `allowsConfiguredOriginWithoutCredentials`: "Expecting value to be false but was true"; `corsIsDeclaredOnlyOnce`: found `WebMvcConfigurer`.

- [ ] **Step 3: Write the implementation**

Replace the whole content of `src/main/java/com/spring/app/configs/CorsConfig.java` with:

```java
package com.spring.app.configs;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.spring.app.configs.properties.ApplicationProperties;

import lombok.RequiredArgsConstructor;

/**
 * The application's only CORS policy. Spring Security applies it
 * ({@code SecurityConfig} passes this source to {@code http.cors(...)}) before
 * Spring MVC sees the request, so there is deliberately no
 * {@code WebMvcConfigurer#addCorsMappings} - two sources drift apart.
 *
 * <p>
 * Credentials are not allowed: the API authenticates with a bearer token in
 * the {@code Authorization} header, never with cookies.
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

  private static final List<String> ALLOWED_METHODS = List.of(
      HttpMethod.GET.name(),
      HttpMethod.POST.name(),
      HttpMethod.PUT.name(),
      HttpMethod.DELETE.name(),
      HttpMethod.OPTIONS.name());

  private static final List<String> ALLOWED_HEADERS = List.of(
      "Authorization",
      "Content-Type",
      "Accept",
      "X-Requested-With",
      "Origin",
      "Cache-Control");

  private static final List<String> EXPOSED_HEADERS = List.of(
      "Authorization",
      "Content-Disposition",
      "X-Frame-Options");

  private final ApplicationProperties applicationProperties;

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(applicationProperties.getCors().getAllowedOrigins());
    configuration.setAllowedMethods(ALLOWED_METHODS);
    configuration.setAllowedHeaders(ALLOWED_HEADERS);
    configuration.setExposedHeaders(EXPOSED_HEADERS);
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(applicationProperties.getCors().getMaxAgeSeconds());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q test -Dtest=CorsConfigTest,BaseSpringApplicationTests`
Expected: PASS (`Tests run: 3, Failures: 0, Errors: 0`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/spring/app/configs/CorsConfig.java \
        src/test/java/com/spring/app/configs/CorsConfigTest.java
git commit -m "$(cat <<'EOF'
fix(cors): single CORS source, credentials not allowed

Drop the duplicate WebMvcConfigurer mapping; Spring Security's
CorsConfigurationSource is the only policy. allowCredentials is false
because authentication is a bearer header, not a cookie.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Swagger server URL is relative

Covers spec: "`SwaggerConfig.java:14` server `http://localhost:8080/api/v1` while controllers map `/api/v1/...` → 'Try it out' hits `/api/v1/api/v1/...`; hard-coded localhost. Use relative server `/`." `springdoc.api-docs.path=/api-docs` is kept (P3 whitelists `/api-docs/**`).

**Files:**
- Modify: `src/main/java/com/spring/app/configs/SwaggerConfig.java:13-17`
- Modify: `src/test/resources/application-test.yml` (append)
- Test (modify): `src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java`

**Interfaces:**
- Consumes: `springdoc.api-docs.path: /api-docs` from `application.yml`.
- Produces: `/api-docs` JSON with `servers == [{ "url": "/", "description": "Current host" }]`.

- [ ] **Step 1: Pin springdoc on in the test profile**

`application.yml` enables the docs with `${SWAGGER_ENABLED:true}`; an exported `SWAGGER_ENABLED=false` in a shell would break the new test. Append to the end of `src/test/resources/application-test.yml`:

```yaml

springdoc:
  api-docs:
    # Literal (see header): WebInfrastructureIntegrationTest reads /api-docs.
    enabled: true
```

- [ ] **Step 2: Write the failing test**

Replace the whole content of `src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java` with:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.filter.RateLimitFilter;

/**
 * Full context on the test profile (H2, no Redis needed). MockMvc runs without
 * servlet filters, so no request here touches Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class WebInfrastructureIntegrationTest {

  @Autowired
  private ApplicationContext context;

  @Autowired
  private MockMvc mockMvc;

  @Test
  @SuppressWarnings("rawtypes")
  void appFiltersRunOnlyInsideTheSecurityChain() {
    Map<String, FilterRegistrationBean> registrations = context.getBeansOfType(FilterRegistrationBean.class);

    List<Object> disabledFilters = registrations.values().stream()
        .filter(registration -> !registration.isEnabled())
        .map(registration -> (Object) registration.getFilter())
        .toList();

    assertThat(disabledFilters).contains(
        context.getBean(RateLimitFilter.class),
        context.getBean(JwtAuthenticatorFilter.class));
  }

  @Test
  void openApiServerIsRelative() throws Exception {
    mockMvc.perform(get("/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.servers.length()").value(1))
        .andExpect(jsonPath("$.servers[0].url").value("/"));
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=WebInfrastructureIntegrationTest`
Expected: FAIL — `openApiServerIsRelative`: "JSON path "$.servers[0].url" expected:</> but was:<http://localhost:8080/api/v1>".

- [ ] **Step 4: Write the implementation**

In `src/main/java/com/spring/app/configs/SwaggerConfig.java`, replace:

```java
    @Server(description = "Local ENV", url = "http://localhost:8080/api/v1"),
```

with:

```java
    // Relative: "Try it out" calls whichever host served the docs (any
    // environment, behind any proxy), and the controller mappings already
    // carry the /api/v1 prefix, so the server URL must not repeat it.
    @Server(description = "Current host", url = "/"),
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=WebInfrastructureIntegrationTest`
Expected: PASS (`Tests run: 2, Failures: 0, Errors: 0`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spring/app/configs/SwaggerConfig.java \
        src/test/resources/application-test.yml \
        src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java
git commit -m "$(cat <<'EOF'
fix(swagger): use a relative server URL

The server was http://localhost:8080/api/v1 while every controller mapping
already starts with /api/v1, so "Try it out" called /api/v1/api/v1/... and
only ever on localhost. "/" works on any host.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Health — Boot's indicators only, details for ADMIN only

Covers spec: "`HealthCheckConfig` duplicates Boot's `db` indicator and puts exception messages in details → remove the duplicate; rely on Boot indicators" and "Actuator config (`management` yml): explicit `show-details` policy consistent with P3's ADMIN rule."

Deleting the whole class also restores Boot's own `redis` indicator: the custom bean was named `redisHealthIndicator`, which suppressed Boot's (`@ConditionalOnMissingBean(name = {"redisHealthIndicator", "redisHealthContributor"})`). After this task `/actuator/health/db` and `/actuator/health/redis` (as README documents) are Boot's. `ERole.getListAuthorities()` grants `ROLE_ADMIN`, which `management.endpoint.health.roles: ADMIN` matches via `isUserInRole("ADMIN")`.

**Files:**
- Delete: `src/main/java/com/spring/app/configs/HealthCheckConfig.java`
- Modify: `src/main/resources/application.yml:223-231` (`management:` block)
- Test (modify): `src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java`

**Interfaces:**
- Consumes: Boot beans `HealthContributorRegistry` (`getContributor(String)`), `HealthEndpointProperties` (`getShowDetails()` → `org.springframework.boot.actuate.endpoint.Show`, `getRoles()` → `Set<String>`).
- Produces: health contributors `db`, `redis` (plus Boot defaults `diskSpace`, `ping`); no `database`; `management.endpoint.health.show-details=when_authorized`, `roles=ADMIN`.

- [ ] **Step 1: Write the failing test**

Replace the whole content of `src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java` with:

```java
package com.spring.app.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.spring.app.filter.JwtAuthenticatorFilter;
import com.spring.app.filter.RateLimitFilter;

/**
 * Full context on the test profile (H2, no Redis needed). MockMvc runs without
 * servlet filters, so no request here touches Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class WebInfrastructureIntegrationTest {

  @Autowired
  private ApplicationContext context;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private HealthContributorRegistry healthContributorRegistry;

  @Autowired
  private HealthEndpointProperties healthEndpointProperties;

  @Test
  @SuppressWarnings("rawtypes")
  void appFiltersRunOnlyInsideTheSecurityChain() {
    Map<String, FilterRegistrationBean> registrations = context.getBeansOfType(FilterRegistrationBean.class);

    List<Object> disabledFilters = registrations.values().stream()
        .filter(registration -> !registration.isEnabled())
        .map(registration -> (Object) registration.getFilter())
        .toList();

    assertThat(disabledFilters).contains(
        context.getBean(RateLimitFilter.class),
        context.getBean(JwtAuthenticatorFilter.class));
  }

  @Test
  void openApiServerIsRelative() throws Exception {
    mockMvc.perform(get("/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.servers.length()").value(1))
        .andExpect(jsonPath("$.servers[0].url").value("/"));
  }

  @Test
  void healthUsesBootIndicatorsOnly() {
    assertThat(healthContributorRegistry.getContributor("db")).isNotNull();
    assertThat(healthContributorRegistry.getContributor("redis")).isNotNull();
    assertThat(healthContributorRegistry.getContributor("database")).isNull();
  }

  @Test
  void healthDetailsAreForAdminsOnly() {
    assertThat(healthEndpointProperties.getShowDetails()).isEqualTo(Show.WHEN_AUTHORIZED);
    assertThat(healthEndpointProperties.getRoles()).containsExactly("ADMIN");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=WebInfrastructureIntegrationTest`
Expected: FAIL — `healthUsesBootIndicatorsOnly`: "Expecting actual to be null" (custom `database` contributor present); `healthDetailsAreForAdminsOnly`: roles is empty.

- [ ] **Step 3: Delete the duplicate indicators**

Run: `git rm src/main/java/com/spring/app/configs/HealthCheckConfig.java`

- [ ] **Step 4: Set the health policy in `application.yml`**

In `src/main/resources/application.yml`, replace (anchor on text):

```yaml
management:
  endpoints:
    web:
      exposure:
        # Keep this minimal. Never expose env/configprops/beans/shutdown.
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when_authorized
```

with:

```yaml
management:
  endpoints:
    web:
      exposure:
        # Keep this minimal. Never expose env/configprops/beans/shutdown.
        # /actuator/prometheus is ADMIN-only in SecurityConfig.
        include: health,info,prometheus
  endpoint:
    health:
      # Anonymous callers (load balancer, compose healthcheck) get UP/DOWN
      # only; component details need an authenticated ADMIN - the same rule
      # SecurityConfig applies to /actuator/prometheus. The indicators are
      # Boot's own (db, redis, diskSpace, ping); there is no custom one.
      show-details: when_authorized
      roles: ADMIN
```

(The `metrics:` and `prometheus:` sub-blocks below stay unchanged. `application-dev.yml` keeps its `show-details: always` override for local work — not P4's file.)

- [ ] **Step 5: Confirm nothing referenced the deleted beans**

Run: `grep -rn "HealthCheckConfig\|databaseHealthIndicator\|redisHealthIndicator" src/`
Expected: no output.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -q test -Dtest=WebInfrastructureIntegrationTest,BaseSpringApplicationTests`
Expected: PASS (`Tests run: 5, Failures: 0, Errors: 0`).

- [ ] **Step 7: Commit**

```bash
# HealthCheckConfig.java was already staged for deletion by `git rm` in Step 3.
git add src/main/resources/application.yml \
        src/test/java/com/spring/app/configs/WebInfrastructureIntegrationTest.java
git commit -m "$(cat <<'EOF'
fix(health): rely on Boot's db/redis indicators, details for ADMIN only

HealthCheckConfig duplicated Boot's db indicator, shadowed Boot's redis
indicator, and put raw exception messages in the details. Removed.
Health details now require an authenticated ADMIN (show-details
when_authorized + roles ADMIN), matching the prometheus rule.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Reconcile with P3's exception types, audit, full suite

Covers spec allowed cross-edit: "P4 maps exception types introduced/used by P3 (e.g. `InvalidTokenException`, `UserNotActiveException` → 403) inside `GlobalExceptionHandler`." `UserNotActiveException` is already mapped (Task 1). Token/auth failures are mapped by stable base types (`org.springframework.security.core.AuthenticationException` → 401, `io.jsonwebtoken.JwtException` → 401). This task adds a type-specific handler **only** if P3 introduced a token exception that extends neither.

**Files:**
- Modify (conditional): `src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java` (security section)
- Modify (conditional): `src/test/java/com/spring/app/exceptions/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: Task 1's private `respond(HttpStatus, String, HttpServletRequest, Exception)` and constant `INVALID_TOKEN`.
- Produces: nothing new for other tasks.

- [ ] **Step 1: Find custom exception types added by P1–P3**

Run: `grep -rn "class [A-Za-z]*Exception extends" src/main/java`
Expected at least: `BadRequestException`, `ConflictException`, `RateLimitExceededException`, `ResourceNotFoundException`, `UserNotActiveException` (all handled in Task 1). For every other class listed, look at its superclass:
- extends `AuthenticationException` (or a subclass such as `BadCredentialsException`) → already 401, nothing to do;
- extends `JwtException` → already 401, nothing to do;
- extends `RuntimeException` / `Exception` directly and represents an invalid/expired/wrong-type token → do Step 2;
- anything else that a controller can throw and that is a client error → report it to the plan owner instead of guessing a status (it would otherwise be a 500 with the generic message, which is safe but wrong).

- [ ] **Step 2 (only if Step 1 found a token exception extending neither base type, e.g. `InvalidTokenException`): add an explicit 401 mapping**

In the test, add a row to the `@CsvSource` of `mapsThrownExceptionToStatusAndBaseResponse`:

```
      invalid-token            | 401 | Invalid or expired token
```

and a case to `exceptionFor` (use the class's `(String)` constructor; add the import for the package grep printed):

```java
      case "invalid-token" -> new InvalidTokenException("secret token detail");
```

and add `"invalid-token"` to the `@ValueSource` of `neverEchoesInternalDetails`.

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — `invalid-token` returns 500 / "Internal server error".

Then in `GlobalExceptionHandler`, directly after `handleJwt(...)`, add (and the import printed by the grep):

```java
  /** P3's token exception (not a JwtException/AuthenticationException subtype). */
  @ExceptionHandler(InvalidTokenException.class)
  public ResponseEntity<BaseResponse<Void>> handleInvalidToken(InvalidTokenException ex, HttpServletRequest request) {
    return respond(HttpStatus.UNAUTHORIZED, INVALID_TOKEN, request, ex);
  }
```

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS.

If Step 1 found no such class, skip this step and record "no P3-specific mapping needed" in the commit message of Step 5 (or skip the commit if nothing changed).

- [ ] **Step 3: Audit for leftovers**

Run: `grep -rn "Server error\|ex.getMessage())" src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java`
Expected: only the four application handlers (`ResourceNotFoundException`, `BadRequestException`, `ConflictException`, `RateLimitExceededException`) pass `ex.getMessage()`; no "Server error" text.

Run: `grep -rn "addCorsMappings\|allowCredentials(true)\|setAllowCredentials(true)" src/main/java`
Expected: no output.

Run: `grep -rn "filterChain.doFilter" src/main/java/com/spring/app/filter/RateLimitFilter.java`
Expected: exactly one line.

- [ ] **Step 4: Run the whole suite with nothing running**

Make sure no local MySQL/Redis is required (stop them if running, or just run as is — the test profile uses H2 and `fail-fast: false`).

Run: `mvn -q test`
Expected: BUILD SUCCESS, 0 failures, 0 errors.

- [ ] **Step 5: Commit (only if Step 2 changed files)**

```bash
git add src/main/java/com/spring/app/exceptions/GlobalExceptionHandler.java \
        src/test/java/com/spring/app/exceptions/GlobalExceptionHandlerTest.java
git commit -m "$(cat <<'EOF'
fix(errors): map P3's token exception to 401

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Hand-offs

| To | What | Why |
|---|---|---|
| **P3** (owns `JwtAuthenticatorFilter`, `SecurityConfig`, `WhitelistUrlConstant`, token exceptions) | If P3 introduces a token exception (e.g. `InvalidTokenException`), make it extend `org.springframework.security.core.AuthenticationException` or `io.jsonwebtoken.JwtException`; P4 then maps it to 401 by base type with no extra code (Task 10 adds a type-specific handler only as a fallback). | Keeps the error mapping generic. |
| **P3** | Keep `JwtAuthenticatorFilter` a Spring bean (`@Component`): P4's `FilterRegistrationConfig` disables its servlet auto-registration by injecting it. If P3 stops making it a bean, Task 6 Step 1 tells the executor to drop that registration. | Avoid a missing-bean startup failure. |
| **P3** | Health details are `when_authorized` + `ADMIN`, but `JwtAuthenticatorFilter` skips authentication on `/actuator/health/**` (public GET), so even an ADMIN gets only UP/DOWN unless the filter authenticates when a Bearer token is present on public routes. Optional; current result is safe. | Spec P3 "health details never shown ... acceptable". |
| **P6** (dead code, runs last) | `WhitelistUrlConstant.AUTH_ENDPOINTS_RATELIMIT`, `UPLOAD_ENDPOINTS_RATELIMIT`, `API_ENDPOINTS_RATELIMIT` are unused after Task 2 (patterns moved to `ERateLimitEndpoint`) — delete. `RateLimitManagerService.getRemainingRequests` / `resetRateLimit` are still unused — delete per spec. Check whether `RateLimitConfig`'s `rateLimitRedisTemplate` bean is still injected anywhere after P1's `RedisService` change (`grep -rn rateLimitRedisTemplate src/main/java`); if not, delete `RateLimitConfig`. The "unreachable `MessagingException` handler" is already removed by P4 Task 1. `ApplicationConstants.AUTH_BASE_PATH` / `FILES_BASE_PATH` are now used by `ERateLimitEndpoint` — keep them. | Dead-code sweep. |
| **P6** (docs) | Document: rate limiting is a Redis INCR fixed window per client IP with buckets auth (`/api/v1/auth/**`), upload (`/api/v1/files/**`), api (every other `/api/**`), global (rest); `burst-capacity` no longer exists; `TRUSTED_PROXIES` now also drives Tomcat's `RemoteIpValve` in prod (exact IPs as Tomcat reports them; must be set behind a load balancer, otherwise all clients share the LB's bucket); X-Forwarded-For is read right-most-untrusted; CORS has `allowCredentials=false`; health indicators are Boot's `db`/`redis`/`diskSpace`/`ping`, details for ADMIN only; error responses keep the `BaseResponse` shape, 5xx text is always "Internal server error". CLAUDE.md's "every constrained property is overridden in application-test.yml" claim: `application.rate-limit.*` values are literals in `application.yml` (no `${ENV}` placeholder), so no env var can reach them — reword the claim or add literal overrides; P4 did not add them. | Docs match reality (Decision 3). |
| **P6** (compose) | If the prod stack runs behind a reverse proxy container, set `TRUSTED_PROXIES` for the app service in `docker-compose.prod.yml` (owned by P6). | Otherwise forwarded headers are ignored after Task 5. |

`.env.example`: P4 adds no new environment variable (`TRUSTED_PROXIES`, `SWAGGER_ENABLED` already exist), so no `.env.example` change is needed.

## Spec coverage (self-review)

| Spec bullet (P4) | Task |
|---|---|
| `GlobalExceptionHandler` unmapped MVC exceptions, SQL leak, message concatenation, `UserNotActiveException` → 403, 4xx at ERROR, rewrite on `ResponseEntityExceptionHandler`, keep `ResponseBuilder` shape, MockMvc tests per mapping | 1 (+10 for P3 types) |
| `RateLimitFilter` chain runs twice | 2 |
| API bucket never applied | 2 |
| `burstCapacity` unused → remove (yml/test yml) | 4 |
| Proxy IP spoofing (`forward-headers-strategy: native`) | 5 (Tomcat) + 2 (filter's own X-Forwarded-For parsing) |
| Swagger server URL | 8 |
| CORS configured twice / `allowCredentials(true)` | 7 |
| `HealthCheckConfig` duplicate, exception messages in details | 9 |
| `@Component` filters double-registered | 6 |
| Actuator `show-details` policy consistent with ADMIN rule | 9 |
| Mask `email:<addr>` in rate-limit WARN logs | 3 |
