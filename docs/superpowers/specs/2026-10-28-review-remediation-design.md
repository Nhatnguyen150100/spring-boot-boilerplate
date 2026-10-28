# Review Remediation — Design / Spec

Date: 2026-10-28. Source: two whole-project reviews of HEAD `92eac07`
(a correctness `/code-review` and an architecture/production-readiness review).
This spec is the single source of truth for the remediation plans in
`docs/superpowers/plans/2026-10-28-remediation-*.md`.

## Decisions (made by the project owner — do not re-litigate)

1. **Uploads → per-user folders.** Files live under `<upload-dir>/<userId>/`.
   Ownership is enforced by path; no schema change. Checksum dedup is scoped to
   the owner's folder and NEVER substitutes another name: an upload is always
   stored under the requested name (dedup becomes "identical content already
   stored as X" logging/metadata only, or is removed if it no longer earns its
   keep — plan author decides, documented).
2. **Email executor → bounded platform-thread pool.** Restore
   `ThreadPoolTaskExecutor` for `emailExecutor` with core/max/queue settings back
   in `AsyncProperties` (`application.async.email.*`, `ASYNC_EMAIL_*`), add SMTP
   connect/read/write timeouts. HTTP requests stay on virtual threads
   (`spring.threads.virtual.enabled=true`). Stay on Java 21.
3. **Docs → match reality + wire API bucket.** Correct CLAUDE.md/README to the
   real implementation. Actually apply the existing `API` rate-limit bucket, and
   restrict `/actuator/prometheus` to ADMIN (or a separate management port).
   Do NOT add Bucket4j, spring-session, or structured JSON logging.

## Global constraints

- Java 21, Spring Boot 3.5.x, Maven. `mvn test` must pass with nothing running
  (no MySQL, no Redis, no SMTP, no Docker).
- Config rules from CLAUDE.md are binding: framework keys under `spring.*`, app
  keys under `application.*` bound by `configs/properties/*` with `@Validated`;
  the six leaf properties classes keep `ignoreUnknownFields = false`; every
  validation-constrained property gets a **literal** override in
  `src/test/resources/application-test.yml`; every new env var is added to
  `.env.example`; profile files hold deltas only and never set
  `spring.profiles.active`.
- Flyway owns the schema (`src/main/resources/db/...`); any schema change is a
  new versioned migration, never an edit of an applied one.
- TDD: each fix lands with a test that fails before and passes after, unless the
  change is pure config/docs (then state how it was verified).
- Match surrounding code style: 2-space indent, Lombok, constructor injection,
  `ResponseBuilder` for HTTP responses.
- Commit per task. Commit messages end with
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

## Plans, execution order, and file ownership

Plans execute strictly in order P1 → P6. A plan may assume all earlier plans
are applied. To keep parallel authoring safe, each file has ONE owning plan; a
later plan touches a file owned by an earlier plan only where listed under
"Allowed cross-edits".

| Plan | Scope | Owns |
|---|---|---|
| P1 persistence-cache-redis | cache/serialization, auditing, Redis client/ops | `CacheConfig`, `RedisConfig`, `ApplicationAuditAware`, `RedisService`, `RedisServiceInterface`, `AuthCacheService`, `UserService`, `UserServiceInterface`, `UserController`, `CacheProperties`, `common/entities/*`, `application.yml` (`spring.data.redis`, cache sections), `application-prod.yml` (redis section) |
| P2 email-otp | email executor, SMTP, OTP template & generation | `AsyncConfig`, `AsyncProperties`, `MailConfig`, `MailProperties`, `MailService`, `MailServiceInterface`, `OtpEmailService`, `templates/HtmlTemplateOTPBuilder`, `resources/templates/*`, `utils/OtpFunctionUtil`, `application.yml` (`spring.threads`, `application.async`, `application.mail`), `.env.example` (mail/async) |
| P3 auth-security | JWT, login/register/refresh/logout, OAuth2, security config, auth metrics, auth/user DTO validation | `modules/auth/**` (except entities' audit base), `JwtService`, `JwtServiceInterface`, `JwtFunctionUtil`, `JwtProperties`, `ApplicationConfig`, `SecurityConfig`, `WhitelistUrlConstant`, `JwtAuthenticatorFilter`, `CustomOAuth2UserService`, `handlers/*`, `MonitoringService`, `MetricsConfig`, `UserNotActiveException`, `CustomAuthenticationEntryPoint`, `common/validation/**`, `modules/user/dto/**` |
| P4 web-errors-ratelimit | exception mapping, rate-limit filter & buckets, Swagger, CORS, proxy headers, health | `GlobalExceptionHandler`, other `exceptions/*` not listed above, `RateLimitFilter`, `RateLimitManagerService`, `RateLimitConfig`, `RateLimitProperties`, `ERateLimitEndpoint`, `SwaggerConfig`, `CorsConfig`, `HealthCheckConfig`, `application.yml` (`springdoc`, `application.rate-limit`, `management`), `application-prod.yml` (`server` section) |
| P5 uploads | per-user storage, dedup semantics, file perms, temp files, download headers | `modules/upload/**`, `FileStorageProperties`, `application.yml` (`application.file-storage` / upload), tests under `modules/upload` |
| P6 build-ops-docs-cleanup | Docker, compose, logback, scheduled jobs, pom, dead code, opt-in Testcontainers IT, docs | `Dockerfile`, `docker-compose*.yml`, `logback-spring.xml`, `TokenCleanupService`, `pom.xml`, `BaseSpringApplication`, `ApplicationConstants`, `enums/*`, `CLAUDE.md`, `README.md`, `.gitignore`, `.gitattributes`, `BaseSpringApplicationTests` |

Allowed cross-edits:
- P1 may set `REDIS_SSL_ENABLED` for the redis service env in
  `docker-compose.prod.yml` (otherwise fixing SSL breaks the prod stack).
- P3 adds `WhitelistUrlConstant` entries for Swagger (`/api-docs/**`) because it
  owns that file; P4 owns `springdoc` config and `SwaggerConfig`.
- P4 maps exception types introduced/used by P3 (e.g. `InvalidTokenException`,
  `UserNotActiveException` → 403) inside `GlobalExceptionHandler`.
- P6 removes dead code anywhere (it runs last) and updates `application*.yml`
  / `.env.example` to delete keys made obsolete.
- Any plan may append to `application-test.yml` for properties it adds.
- Any plan may add test classes in its own area.

Deferred: **P7 service-layer refactor** (services return DTOs instead of
`ResponseEntity`; consolidate auth code from `shared/`, `handlers/`, `filter/`,
`utils/` into `modules/auth`; break the `shared ↔ modules.auth` cycle; stop the
user module depending on auth internals). It touches every file above, so its
plan is written only after P1–P6 have landed.

## Findings by plan

### P1 persistence-cache-redis
- **CacheConfig.java:49** `GenericJackson2JsonRedisSerializer` lacks
  `JavaTimeModule`; cached `User`/`RefreshToken` (Instant fields) fail to
  serialize → resend-otp, activate, logout, refresh-token, forgot-password,
  reset-password return 500 whenever Redis is up. `UserService` caches
  `ResponseEntity` under `userProfile` (no creator → GET /users/me fails on
  cache read). Even with JavaTimeModule, reading `User` back fails (unknown
  getter-only props `username`, `enabled`; serializer fails on unknown props),
  and `@JsonIgnore password` returns null which `activeAccount` then saves.
  **Fix direction:** stop caching JPA entities altogether (AuthCacheService
  keeps its method signatures but reads through the repository); cache only
  immutable DTOs (e.g. `UserResponseDto`) with a correctly configured
  ObjectMapper (JavaTimeModule, no default typing surprises). Add a
  serialization round-trip unit test.
- **Stale token cache** (AuthServiceImpl.java:190 bulk revoke never evicts
  `TOKENS_CACHE`; saving the stale cached entity un-revokes it). Resolved by not
  caching `RefreshToken` entities; verify no remaining `@Cacheable` on entities.
- **ApplicationAuditAware.java:35** casts principal to
  `java.nio.file.attribute.UserPrincipal` → ClassCastException on every JPA
  insert/update with a JWT-authenticated user (PUT /users/me/update → 500).
  Fix: resolve the app `User` (or its id) from the principal, handle anonymous /
  OAuth2 principals. Test with `@DataJpaTest` + security context.
- **RedisConfig.java:38** custom `LettuceConnectionFactory` built from
  `RedisStandaloneConfiguration` only — ignores `spring.data.redis.ssl.enabled`
  and timeouts. Use `LettuceClientConfiguration` (useSsl, commandTimeout) or
  drop the custom factory and rely on Boot autoconfig. Prod compose Redis has
  no TLS → set `REDIS_SSL_ENABLED=false` there (allowed cross-edit) or keep
  prod default but make compose explicit.
- **RedisService** `rateLimitRedisTemplate` declared as
  `RedisTemplate<String,Object>` so Spring injects the JSON template instead of
  the String one; qualify / type it `StringRedisTemplate`.
- **RedisService.java:107-112** INCR then EXPIRE as two commands: if EXPIRE is
  lost the key never expires → permanent block. Make it atomic (Lua script or
  `SET key 0 EX w NX` + `INCR`).
- **UserService**: uses `jakarta.transaction.Transactional` (switch to Spring's,
  `readOnly=true` on reads); `updateUserProfile` doesn't evict the user cache.
- Email keys: cache keys case-sensitive while MySQL lookups are not — normalize
  key (lower-case) in the cache layer it owns.

### P2 email-otp
- **Critical: HtmlTemplateOTPBuilder.java:10** reads
  `Paths.get("src/main/resources/templates/otp-email.html")` — missing in the
  jar/Docker image → IOException swallowed by `OtpEmailService` → OTP never
  delivered, accounts stay PENDING, password reset broken. Load via classpath
  (`ClassPathResource`), cache the template, HTML-escape inserted values. Delete
  unused `templates/OTP.html`. Test renders from classpath.
- **Virtual-thread pinning**: Java 21 + jakarta.mail `SMTPTransport`
  `synchronized` socket I/O pins carriers; emailExecutor now on virtual threads
  and shares carriers with Tomcat → a few slow SMTP sends freeze request
  handling. Per decision 2: bounded `ThreadPoolTaskExecutor` with
  `application.async.email.{core-size,max-size,queue-capacity}` (validated,
  literal overrides in test yml, `ASYNC_EMAIL_*` in `.env.example`), rejection
  policy that logs+drops rather than blocking the caller, task-termination wait
  on shutdown. Fix the misleading yml comment ("no synchronized blocks").
- **No SMTP timeouts** (MailConfig sets none → infinite wait). Add
  `mail.smtp.connectiontimeout`, `mail.smtp.timeout`, `mail.smtp.writetimeout`
  bound from `MailProperties` (validated, test overrides, `.env.example`).
- **No backpressure**: `sendPermits.acquire()` has no timeout; with a bounded
  pool the semaphore may be redundant — remove it or use `tryAcquire(timeout)`.
  `MAIL_MAX_CONCURRENT_SENDS` missing from `.env.example` (add or remove the
  setting with the semaphore).
- **OtpFunctionUtil** uses `Math.random` → use `SecureRandom`. OTP stored under
  the raw email as Redis key → namespaced key (e.g. `otp:<lower(email)>`).
- `MailService.sendContentEmail` unused (only jsoup user) — leave removal to P6
  unless P2 rewrites MailService anyway.

### P3 auth-security
- **JwtFunctionUtil.java:73** access & refresh tokens same key/claims, no
  `token_type` claim → refresh JWT accepted as Bearer access token for 7 days;
  logout doesn't blacklist it. Add `typ`/`token_type` claim, reject non-access
  tokens in `JwtAuthenticatorFilter`, accept only refresh type at
  `/auth/refresh-token`.
- **refreshToken** doesn't check user status (DELETED/INACTIVE keep refreshing).
- **CustomOAuth2UserService.java:107** `Map.of` NPE on null avatar; all
  exceptions wrapped as `RuntimeException` instead of
  `OAuth2AuthenticationException` → 500 instead of failure handler. Also
  links by e-mail without checking Google `email_verified` (pre-account hijack);
  logs PII at INFO (line 92).
- **OAuth2AuthenticationSuccessHandler.java:74** issues tokens without checking
  status (DELETED/INACTIVE get tokens; PENDING bypasses OTP). Handler talks to
  repositories directly, duplicates `createAndStoreRefreshToken`, injects
  concrete `JwtService`. Writes tokens as JSON into the browser callback page —
  redirect to `application.frontend-url` instead (tokens in URL fragment) so a
  SPA can receive them. OAuth2 state lives in in-memory HttpSession — document
  "sticky sessions required" (no spring-session per decision 3).
- **OAuth2AuthenticationFailureHandler.java:44** echoes exception message.
- **AuthServiceImpl.java:259** uniqueness check skips DELETED users but
  `uk_users_email` covers all rows → re-registration 500 (and OTP already
  sent). Decide: reject with the same generic "cannot register" / conflict, or
  reactivate the row; never hit the constraint.
- **Account enumeration**: login on PENDING/INACTIVE returns "User with email X
  is not active" before password check (`ApplicationConfig.java:45-46` throws
  plain RuntimeException in `loadUserByUsername`; post-auth check at 89-95 is
  unreachable). resend-otp/activate/reset-password return 404 "User not found
  with email". Make responses uniform; check status only after password
  matches.
- **Login lockout DoS**: `AuthServiceImpl.java:77-81,158` throttles by
  `email:`+email with AUTH bucket and counts successes → ~1000 requests lock a
  victim out for a day. Count failures only, key on email+IP.
- **Passwords > 72 bytes** → BCrypt throws → 500. Add max length
  (`@Size(max=72)` bytes-aware validator) on register/reset. Remove
  `@StrongPassword` from `LoginRequestDto` (policy at login locks out users if
  it tightens).
- **Dev-mode check**: `@Value("${spring.profiles.active}")` +
  `activeProfile.equals("dev")` skips e-mail verification; use
  `Environment.acceptsProfiles(Profiles.of("dev"))` and make the skip an
  explicit opt-in property (e.g. `application.auth.skip-email-verification`,
  default false) so a misconfigured deployment never silently skips it.
- **`/api/v1/auth/**` all public** and JWT filter skips them
  (`WhitelistUrlConstant.java:12`, `JwtAuthenticatorFilter.java:54-73`). List
  public auth endpoints explicitly; single source of truth for the whitelist
  shared by `SecurityConfig` and the filter. Logout with an expired/garbled
  token → 500 via JJWT exception — handle as 401.
- **Swagger whitelist**: `springdoc.api-docs.path=/api-docs` but whitelist has
  only `/v3/api-docs/**` → add `/api-docs/**` (P4 keeps the springdoc path).
- **Actuator**: `/actuator/prometheus` requires any user JWT → restrict to
  ADMIN (`hasRole('ADMIN')`); health details never shown because filter skips
  auth on public routes — acceptable to set `show-details` explicitly (P4 owns
  `management` yml).
- **Transactions**: `@Transactional` on `login`/`register` holds a Hikari
  connection through Redis INCRs, BCrypt, JWT signing → with virtual threads
  pool of 20 is the concurrency cap. Move throttling/authentication outside the
  transaction.
- **JwtService.getSignInKey** re-decodes per call; `JwtProperties` only
  `@NotBlank` → validate Base64 and ≥256-bit at startup, decode once.
- **JWT filter** hits DB every request via `loadUserByUsername` — acceptable,
  but status must be checked; keep, document. (Optional: short TTL cache of
  `UserResponseDto` from P1.)
- **Metrics**: `auth.login.attempts` incremented only on success
  (`MonitoringService.java:24`, `AuthServiceImpl.java:166`). Rename to
  success, add failed-login, OTP-failure, refresh, logout, OAuth2, rate-limit
  rejection counters.
- **Logging**: `AuthCacheService.java:49` logs raw refresh token at DEBUG — P1
  owns the file; P1 removes the log. P3 removes PII from its own logs and
  rate-limit WARN lines it owns.
- **DTO validation**: full-name regex `^[a-zA-Z\s]+$` (RegisterRequestDto,
  UpdateUserDto) rejects "Nguyễn", "O'Brien", "Jean-Luc" → Unicode letters,
  space, apostrophe, hyphen, dot. `register` validates `phone`/`address` then
  drops them (AuthServiceImpl.java:99) — persist them. Validate `avatarUrl` as
  URL. Normalize e-mail (trim + lower-case) on every auth entry point.
- **No Redis sessions / RBAC**: no `@PreAuthorize` anywhere — enable method
  security and use it for the ADMIN actuator rule / any admin endpoint; MANAGER
  role unused (P6 decides removal).

### P4 web-errors-ratelimit
- **GlobalExceptionHandler.java:210-215** (also 149, 194, 231): unmapped
  `HttpMessageNotReadableException`, `HttpRequestMethodNotSupportedException`,
  `NoResourceFoundException`, `MaxUploadSizeExceededException` (→413),
  `MissingServletRequestPartException`, `MethodArgumentTypeMismatchException`,
  `HttpMediaTypeNotSupportedException`, `HandlerMethodValidationException`,
  `DataIntegrityViolationException` → 500 with `"Server error: " +
  ex.getMessage()` leaking SQL. `InternalAuthenticationServiceException` and
  `TransactionSystemException` also concatenate messages. `UserNotActiveException`
  maps to 400 (should be 403). All 4xx logged at ERROR. Rewrite on
  `ResponseEntityExceptionHandler` (keep `ResponseBuilder` response shape),
  fixed generic message for 5xx, WARN/INFO for 4xx. MockMvc tests per mapping.
- **RateLimitFilter.java:60** `catch (Exception)` around
  `filterChain.doFilter` calls `doFilter` again → chain runs twice (expired JWT
  continues unauthenticated; POST can execute twice). Only wrap the
  rate-limit check, never the downstream chain.
- **API bucket never applied** (`API_ENDPOINTS_RATELIMIT = {}`) → apply API
  bucket to `/api/**` not covered by auth/upload. `burstCapacity` validated but
  unused → remove (strict binding: drop from yml/test yml too).
- **Proxy IP spoofing**: `application-prod.yml:54`
  `forward-headers-strategy: native` → Tomcat RemoteIpValve trusts XFF from any
  private-range peer, undoing `TRUSTED_PROXIES`. Set
  `server.tomcat.remoteip.internal-proxies` explicitly from config (or use
  `framework`), consistent with `application.trusted-proxies`.
- **Swagger**: `SwaggerConfig.java:14` server `http://localhost:8080/api/v1`
  while controllers map `/api/v1/...` → "Try it out" hits
  `/api/v1/api/v1/...`; hard-coded localhost. Use relative server `/` (or none).
- **CORS configured twice** (security `CorsConfigurationSource` + a
  `WebMvcConfigurer`) with `allowCredentials(true)` though no cookie auth →
  one source, `allowCredentials(false)` unless needed.
- **HealthCheckConfig** duplicates Boot's `db` indicator and puts exception
  messages in details → remove the duplicate; rely on Boot indicators.
- **`@Component` filters double-registered** as servlet filters →
  `FilterRegistrationBean#setEnabled(false)` for each.
- **Actuator config** (`management` yml): explicit `show-details` policy
  consistent with P3's ADMIN rule.
- Rate-limit WARN logs include `email:<addr>` — mask in the code P4 owns.

### P5 uploads
- **Ownership**: no owner; `Files.move(..., REPLACE_EXISTING)` lets user B
  overwrite user A's `report.pdf`; any user can DELETE any file. Per decision 1:
  `<upload-dir>/<userId>/`, user id from the SecurityContext; all
  list/download/delete scoped to the caller's folder; path-safety checks
  (`resolveSafePath`) applied inside the user folder.
- **Dedup** (`UploadServiceImpl.java:103-108`, `FileChecksumRegistry`):
  re-uploading `a.txt` with content equal to `b.txt` returns 200 with `b.txt`
  and leaves `a.txt` stale; returns other users' names (info leak); deleting
  the holder deletes the "duplicate's" content. Per decision 1: always store
  under the requested name; dedup scoped per user and never redirects.
- **Permissions**: `Files.createTempFile` → 0600 survives the atomic move →
  nginx/backup agents break. Set explicit permissions (e.g. 0644 / configurable)
  on POSIX.
- **Orphaned `.upload-*.tmp`** never cleaned, downloadable via
  `GET /download/{name}` → use a dedicated temp subdirectory outside the
  servable namespace and sweep it on startup.
- User-uploaded dot-files are skipped by the index → resolved by temp subdir.
- **Startup index** re-hashes every file on each boot and holds unbounded maps;
  with per-user folders keep it lazy/per-user or drop the registry if dedup
  becomes logging-only (document the choice).
- **Download**: `Content-Disposition` built by concatenation → use
  `ContentDisposition.attachment().filename(name, UTF_8)`. `catch (Exception)`
  turns not-found into 400 + ERROR stack trace → 404 via
  `ResourceNotFoundException`, no stack trace.
- **No content-type / extension allowlist** → configurable allowlist in
  `FileStorageProperties` (validated, test override, `.env.example`).
- `UploadServiceInterface.getUploadPath` leaks the absolute server path → remove
  endpoint/method.
- Upload rate limit is per IP, CLAUDE.md says per user → P6 fixes docs.
- `contextLoads` creates `uploads-test/` in repo root → test upload dir under
  `target/` (P5 owns the upload-dir test property).

### P6 build-ops-docs-cleanup
- **Dockerfile**: full JDK image, runs as root, no `-XX:MaxRAMPercentage` → JRE
  base, non-root user, container-aware JVM flags.
- **Compose healthchecks use `curl`** which `eclipse-temurin:21-*-alpine`
  likely lacks → verify (`docker run --rm <image> which curl wget`) and use a
  tool present in the image (busybox `wget`) or a Java-free check.
- **docker-compose.yml defaults to dev profile** → dev skips e-mail
  verification (P3 makes that opt-in); default compose profile to `prod`-like or
  make it explicit; keep a dev override file.
- **logback-spring.xml**: ANSI colours in file/prod output, `scan="true"` in
  prod, no `totalSizeCap` → fix.
- **TokenCleanupService** runs on every instance, no lock → Redis-based lock
  (SET NX EX) around the job.
- **pom.xml**: remove unused `spring-boot-starter-web-services` and jsoup
  (after confirming no use remains); declare `commons-lang3` explicitly if still
  used; drop the `lettuce-core` 6.7.1 pin (Boot manages it); upgrade to the
  latest Spring Boot 3.5.x patch; configure Mockito as a surefire `-javaagent`;
  remove explicit `H2Dialect` in test yml; remove
  `spring.cloud.compatibility-verifier`.
- **Dead code**: most of `ApplicationConstants`, `ERole.MANAGER` (if unused
  after P3), `RateLimitManagerService.getRemainingRequests/resetRateLimit` (if
  still unused after P4), `JwtService.extractUserId/Role/Status`, unused
  repository finders, `MailService.sendContentEmail`, `AuthRequestDto`,
  `ValidationGroups`, unreachable `MessagingException` handler,
  duplicate `@EnableCaching`, unused `frontend-url` (only if P3 didn't wire
  it). Verify each with grep before deleting.
- **Opt-in integration tests**: Testcontainers MySQL + Redis behind a Maven
  profile (`-Pintegration`, failsafe) so `mvn test` stays hermetic. Cover:
  Flyway migrate + `ddl-auto=validate`, register→activate→login→refresh→logout
  round trip with real Redis cache, JPA auditing on save.
- **Tests writing outside `target/`**: `./logs` from tests → test logging to
  console only; add `uploads-test/`, `logs/` to `.gitignore` if missing.
- **`.env.example` completeness**: verify every `${VAR}` in `application*.yml`
  and compose files appears in `.env.example` and vice versa (script it).
- **Docs**: rewrite CLAUDE.md/README claims that are false: Bucket4j (it's a
  Redis INCR fixed window), API bucket (now applied by P4), upload limit per
  IP, "Session storage in Redis" (stateless; OAuth2 needs sticky sessions), RBAC
  (describe what P3 actually enforces), "custom business metrics" (list P3's
  counters), "Structured logging" (plain text), JSoup/Template Method email
  (classpath template + placeholder replace), "batch processing", README's
  Resilience4j / CSRF claims. Document virtual threads (Tomcat on virtual
  threads, Hikari pool = real concurrency cap, email on bounded platform pool).
  Fix the `application-test.yml` "every constrained property" claim or make it
  true (`application.rate-limit.*`).
