# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

### Essential Commands
- **Build**: `mvn clean install` - Clean and build the entire project
- **Run**: `mvn spring-boot:run` - Start the application on port 8080 (needs MySQL and Redis running; Redis is fail-fast)
- **Test**: `mvn test` - Unit and slice tests; hermetic (H2, no MySQL/Redis/SMTP/Docker needed)
- **Integration tests**: `mvn -Pintegration verify` - `*IT` classes in `src/test/java/com/spring/app/integration` via failsafe against Testcontainers MySQL 8.0 + Redis 7 (Docker required; Ryuk removes the containers). Never run by `mvn test`.
- **Package**: `mvn clean package` - Create deployable JAR file
- **Env catalogue check**: `scripts/check-env-example.sh [env-file]` - prints nothing and exits 0 when `.env.example` lists exactly the `${VAR}` names used by `src/main/resources/application*.yml` and `docker-compose*.yml`; otherwise lists the missing / unused names and exits 1 (an env file with no `NAME=` lines lists every used name); run it after adding or removing any `${VAR}`

### Docker
- `docker compose up --build` - MySQL + Redis + app with the **prod** profile. Needs a `.env` with the prod variables; `MYSQL_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_PASSWORD` are mandatory (`${VAR:?}`). MySQL/Redis are published on 127.0.0.1 only.
- Dev profile (the four secrets must come from the shell or `.env`; Compose checks `${VAR:?}` per file before overlays merge, so the overlay cannot supply them):
  `MYSQL_ROOT_PASSWORD=dev-root DB_USERNAME=springapp DB_PASSWORD=dev-pass REDIS_PASSWORD=dev-redis docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build`.
  The dev overlay also sets `AUTH_SKIP_EMAIL_VERIFICATION=true`.
- Add `-f docker-compose.debug.yml` (only together with the dev overlay) - JDWP on `127.0.0.1:5005`
- `docker compose -f docker-compose.prod.yml up -d` - production stack (`.env` mandatory, every port bound to 127.0.0.1, app memory limit 2G). Its network is pinned to `172.28.0.0/16` (gateway `172.28.0.1`): a reverse proxy on the host reaches the app through the gateway, so set `TRUSTED_PROXIES=172.28.0.1`. Its Redis runs `maxmemory-policy noeviction` (security keys must never be evicted; cache entries expire by TTL).
- Both the base and the prod file give the app `stop_grace_period: 70s` (graceful shutdown + e-mail drain need about 60s; Docker's default is 10s).
- Image: `eclipse-temurin:21-jre-alpine`, runs as non-root UID/GID 10001, `SPRING_PROFILES_ACTIVE=prod` by default, `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` (give the container a memory limit). Uploads and logs live in named volumes (`springapp_uploads_data` / `springapp_logs_data`; `springapp_uploads_prod_data` / `springapp_logs_prod_data` in prod). The names are fixed, so two checkouts on one host share them. Healthchecks use BusyBox `wget` (the image has no `curl`).

### Development Environment
- **Java Version**: 21 (required)
- **Maven**: Used for dependency management and build
- **Profile**: `spring.profiles.active` defaults to `dev` (`SPRING_PROFILES_ACTIVE`) when running from source; the Docker image and the base compose file use `prod`

### Database Setup
1. Create MySQL database: `db_test_spring`
2. Copy `.env.example` to `.env` and set `DB_*` there (never in the YAML files)
3. Flyway owns the schema (`FLYWAY_ENABLED=true` by default); migrations live in
   `src/main/resources/db/migrations` (`V1`..`V3`). Never edit an applied migration;
   add a new version.
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
  keeps the catalogue in sync with the `${VAR}` names used in `application*.yml`
  and `docker-compose*.yml` (commented-out `# VAR=` entries count as listed).
- Tests: `src/test/resources/application-test.yml` (H2, Flyway off, Redis
  fail-fast off) so `mvn test` needs nothing running. Every validation-constrained
  property whose value in `application.yml` comes from a `${ENV_VAR}`
  placeholder (JWT secret/expirations, `frontend-url`, CORS origins,
  `application.auth.*`, mail, upload settings, cache TTL, async pool sizes) is
  overridden here with a literal, never `${ENV_VAR}`. This blocks two separate
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
Adding a setting means adding a field there, plus a literal override in
`application-test.yml` if it is constrained and placeholder-backed, plus a
`.env.example` entry for any new `${VAR}`.
Strict binding (`ignoreUnknownFields = false`) is enabled on the seven leaf
classes — `JwtProperties`, `CacheProperties`, `MailProperties`, `AsyncProperties`,
`RateLimitProperties`, `FileStorageProperties`, `AuthProperties` — i.e. every
class in `configs/properties/` except `ApplicationProperties`, so a typo'd key
under one of their prefixes fails startup instead of binding to nothing.
`ApplicationProperties` itself (the bare `application.*` prefix, covering
`cors`, `redis`, `frontend-url`, `trusted-proxies`,
`oauth2-allowed-email-domains`) is left on Spring Boot's default relaxed
binding, because it is the shared parent prefix for all the others; turning
on strict binding there would make every child class's keys look "unknown"
to it and break startup entirely. A typo under those specific keys is still
silently ignored (their values are still validated: `frontend-url` must be an
absolute http(s) URL without query or fragment, `cors.allowed-origins`
non-empty and without `*`).

## Architecture Overview

### Core Architecture Pattern
The application follows a **layered architecture**:

**Controller → Service → Repository → Database**

Services currently return `ResponseEntity` (built with `ResponseBuilder`).
Moving them to DTO returns, and consolidating the auth code spread over
`shared/`, `handlers/`, `filter/` and `utils/` into `modules/auth`, is a
deferred refactor (P7 in `docs/superpowers/specs/2026-10-28-review-remediation-design.md`).

### Module Structure
Feature modules under `src/main/java/com/spring/app/modules/`:
- **auth**: registration, OTP activation, login, refresh, logout, password reset
- **user**: the caller's own profile (`/api/v1/users/me`)
- **upload**: per-user file storage (`/api/v1/files`)

### Threads, Transactions and Concurrency
- HTTP requests and `@Scheduled` jobs run on **virtual threads**
  (`spring.threads.virtual.enabled=true`). Tomcat's thread pool no longer
  caps concurrency; the **HikariCP pool** (`DB_POOL_MAX_SIZE`, default 20) is
  the real limit on concurrent DB work. `AuthServiceImpl` is deliberately not
  `@Transactional`: BCrypt, JWT signing and Redis calls run outside any
  transaction, and multi-write steps wrap only the writes in a `TransactionTemplate`.
- **E-mail** runs on the `emailExecutor`: a **bounded platform-thread**
  `ThreadPoolTaskExecutor` (`application.async.email.*`, env `ASYNC_EMAIL_*`,
  default 5/5/100). It does not use virtual threads, because jakarta.mail's
  SMTP transport does socket I/O inside synchronized methods and would pin
  carrier threads shared with Tomcat. When pool and queue are full, a new
  e-mail is logged and dropped (the OTP is not stored; the user requests a new
  one) rather than blocking the caller. SMTP connect/read/write timeouts are
  `application.mail.*-timeout` (`MAIL_*_TIMEOUT`, default 10s).
- On shutdown, `EmailExecutorDrain` (a `SmartLifecycle`) stops the e-mail pool
  after the web server and before Redis, waiting up to 30s for queued sends.
  Together with Boot's 30s graceful shutdown this can take about a minute
  (longer if an SMTP server hangs), so orchestrator grace periods must allow it.
- `taskExecutor` (`application.async.task.*`) has no caller today; it is kept
  as the default executor for any bare `@Async` method.
- `TokenCleanupService` deletes expired/revoked refresh tokens daily at 02:00;
  a Redis lock (`lock:token-cleanup`, `SET NX EX` 10 min) makes exactly one
  instance run it.

### Security Architecture
- **JWT** (jjwt, HS256). Access and refresh tokens carry a `token_type`
  claim: only access tokens are accepted as `Authorization: Bearer`, and only
  refresh tokens at `/api/v1/auth/refresh-token`. Refresh tokens are stored as
  SHA-256 hashes in `refresh_tokens`; a refresh rotates the token (conditional
  revoke, so of two concurrent refreshes only one wins). Logout signs out
  everywhere: it blacklists the presented access token in Redis until it
  expires, revokes all of the user's refresh tokens and ends the user's other
  access tokens via a per-user "not before" marker in Redis
  (`TokenRevocationService`). A password reset revokes refresh tokens and sets
  the same marker. Tokens carry `id`, `role`, `token_type`, `jti` and the
  e-mail as subject (no `email` / `status` claims).
- `JwtAuthenticatorFilter` never rejects a request itself: it authenticates
  when a usable bearer access token is present, loading the user from the
  DB on every request (non-ACTIVE or deleted users stay anonymous). The
  blacklist and revocation checks fail closed when Redis is down; a database
  failure during the user lookup also leaves the request anonymous (WARN,
  exception type only).
- **Public endpoints** are listed explicitly in `WhitelistUrlConstant`, read
  only by `SecurityConfig` (the JWT filter keeps no copy). Everything else
  needs a valid access token.
- **Roles**: `USER` (default) and `ADMIN` (`V3` migration demoted the removed
  `MANAGER` role), with method security enabled (`@EnableMethodSecurity`).
  `ADMIN` is required for every actuator endpoint except `health` and `info`
  (i.e. `/actuator/prometheus`). Every other protected endpoint only requires
  an authenticated, ACTIVE user.
- **E-mail verification**: new accounts are PENDING until activated with a
  6-digit OTP (SecureRandom, 3-minute TTL, stored in Redis under
  `otp:<sha256(email)>`). An OTP is discarded after 5 wrong guesses
  (`AUTH_OTP_MAX_ATTEMPTS`). `OtpEmailService` resets both wrong-guess
  counters (activation, password_reset) for the e-mail right after a new OTP
  is stored (one OTP per e-mail, so the new one replaces any old one); a
  failed store or dropped send leaves them as they are. Skipping
  verification is an explicit opt-in
  (`application.auth.skip-email-verification`, default `false`) and is
  honoured only while the `dev` profile is also active.
- **PENDING accounts are never expired** (owner decision): an unactivated
  registration keeps its e-mail address reserved until someone activates it,
  so a stranger can squat an address they do not own. No cleanup job exists.
- **OAuth2 (Google, OIDC)**: requires `email_verified`, optionally restricted
  by `application.oauth2-allowed-email-domains`; existing non-ACTIVE accounts
  are refused. On success or failure it redirects to
  `application.frontend-url` + `application.auth.oauth2-redirect-path`
  (default `/oauth2/callback`) with the result in the URL fragment
  (`#access_token=...&refresh_token=...&token_type=Bearer` or `#error=<code>`).
  The authorization request lives in the in-memory servlet `HttpSession`
  (there is no spring-session), so **multi-instance deployments need sticky
  sessions** for the Google login flow.
- **Rate limiting**: Redis fixed-window counters (atomic increment, window TTL
  set on the first hit), keyed by client IP (IPv6 by its /64). Each request
  counts against exactly one bucket, chosen by path (minute / hour / day):
  - AUTH `/api/v1/auth/**` (including the OAuth2 endpoints): 10 / 100 / 1000
  - UPLOAD `/api/v1/files/**`: 5 / 50 / 500
  - API every other `/api/**`: 60 / 600 / 6000
  - GLOBAL everything else (actuator, Swagger): 100 / 1000 / 10000

  Every 429 (bucket limit, login lockout, per-e-mail OTP throttle) carries
  `Retry-After: 60`, which CORS exposes to browser clients. A limiter error
  fails open. The OTP-sending endpoints (register, resend-otp, forgot-password) are
  additionally limited per e-mail with the AUTH quotas, and failed logins are
  throttled per e-mail + client IP (`AUTH_LOGIN_MAX_FAILURES` within
  `AUTH_LOGIN_FAILURE_WINDOW`, default 5 per 15m; successes are not counted).
- **Client IP / proxies**: `X-Forwarded-For` / `X-Real-IP` are honoured only
  from the exact IPs in `application.trusted-proxies` (`TRUSTED_PROXIES`, no
  CIDR), the single list used by `ClientKeys` (rate limiting, login throttling)
  and by Tomcat's `RemoteIpValve` (prod, `forward-headers-strategy=native`,
  configured in `ProxyHeadersConfig`). When it is empty, forwarded headers
  including `X-Forwarded-Proto` are ignored. With the prod Compose stack and a
  reverse proxy on the host, Tomcat's peer is the bridge gateway
  (`172.28.0.1`), so that is the address to list.
- **CORS**: one `CorsConfigurationSource` (`CorsConfig`, `application.cors.*`),
  credentials not allowed.
- **CSRF**: disabled. The API is stateless and authenticates with bearer
  tokens, not cookies. Spring Security's default `/logout` filter is disabled
  too (logout is `DELETE /api/v1/auth/logout`).

### Caching and Redis
- Spring Cache on Redis caches **immutable DTOs only**, never JPA entities.
  There is one cache: `userProfile` (`UserResponseDto` by user id), TTL
  `application.cache.users-ttl` (`CACHE_USERS_TTL`, default 10m), keys
  prefixed `v2:` (bump the prefix when the DTO shape changes). It is evicted
  on profile changes. Cache errors are logged and fall back to the database
  (`LoggingCacheErrorHandler`). It is plain cache-aside: a read that loaded
  the old profile just before a concurrent update can put it back after the
  eviction, and that stale entry lives until `CACHE_USERS_TTL` expires.
- Redis also holds OTPs and OTP guess counters, login-failure counters, the
  access-token blacklist, the per-user "tokens not before" markers,
  rate-limit counters and the token-cleanup lock. There are **no HTTP
  sessions in Redis** (see the OAuth2 note above).
- The Redis connection is Spring Boot's own Lettuce auto-configuration
  (`spring.data.redis.*`: `REDIS_TIMEOUT`, `REDIS_CONNECT_TIMEOUT`,
  `REDIS_SSL_ENABLED`; TLS defaults to on in the prod profile).

### Monitoring and Observability
- Actuator exposes `health`, `info`, `prometheus` only. `/actuator/health`
  and `/actuator/info` are public; health details are shown only to ADMIN
  (always in the dev profile). `/actuator/prometheus` requires ADMIN.
- Custom metrics (`MonitoringService`; Prometheus appends `_total` to counters):
  - `auth.login.success`
  - `auth.login.failed` (tag `reason`: `bad_credentials`, `not_active`, `locked`)
  - `auth.registration.attempts` (successful registrations)
  - `auth.otp.failed` (tag `purpose`: `activation`, `password_reset`)
  - `auth.token.refresh` (tag `result`: `success`, `failure`)
  - `auth.logout`
  - `auth.oauth2.login` (tag `result`)
  - `auth.ratelimit.rejected` (tag `bucket`)
  - timers `auth.login.duration`, `auth.registration.duration`
- **Logging** is plain text via `logback-spring.xml`: console (colours outside
  `prod`, which Boot disables when stdout is not a terminal; never ANSI under
  `prod`), plus rolling files `${LOG_PATH}/application.log` and `error.log`
  (daily and at 100MB, 30 days, 3GB / 1GB caps) in every profile except
  `test`, which logs to the console only. `LOG_PATH` defaults to `logs`
  (`/var/log/springapp` in prod).

### Data Layer
- JPA/Hibernate on MySQL 8; Flyway migrations; `ddl-auto=validate`; `open-in-view` off.
- Collation comes from the MySQL server default (the Compose files start MySQL
  with `utf8mb4_unicode_ci`). New e-mails are stored normalised (trimmed,
  lower-case), so lookups only rely on the case-insensitive collation for
  legacy mixed-case rows.
- HikariCP (max 20 by default). Hibernate JDBC batching is configured
  (`batch_size` 50, ordered inserts/updates); there are no bulk-processing jobs.
- Auditing: `BaseAuditingEntity` fills `created_at/updated_at` and
  `created_by/updated_by` via `ApplicationAuditAware`: the authenticated
  user's id, or `SYSTEM` when there is none.

### File Upload System
- Files are stored per user under `<upload-dir>/<userId>/` (`UPLOAD_DIR`,
  default `uploads`). Upload, download and delete only see the caller's own
  folder (another user's file is a 404), and an upload is always stored under
  the requested name (no dedup; same name overwrites).
- In-flight uploads go to `<upload-dir>/.tmp/` (same volume, atomic move);
  stale temp files are swept at startup. Stored files get explicit POSIX
  permissions (`UPLOAD_FILE_PERMISSIONS`, default `rw-r--r--`).
- An upload is accepted only if both its declared Content-Type and its file
  extension are allowlisted (`UPLOAD_ALLOWED_CONTENT_TYPES`,
  `UPLOAD_ALLOWED_EXTENSIONS`; default png, jpg/jpeg, gif, webp, pdf, txt).
  There is no content sniffing. Max size 10MB (`MAX_FILE_SIZE`).
- The upload rate limit is the UPLOAD bucket, **per client IP** (not per user).

### Error Handling
- `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` and
  answers in the `ResponseBuilder` / `BaseResponse` shape
  (`statusCode`, `message`, `data`, `timestamp`).
- 4xx are logged at WARN/INFO. 5xx return the fixed message "Internal server
  error"; exception text (SQL, stack traces) never reaches the client.

### E-mail
- The OTP mail is built from the classpath template
  `templates/otp-email.html` (`HtmlTemplateOTPBuilder`), with placeholders
  replaced by HTML-escaped values.

### Key Design Patterns
- **Repository Pattern**: Data access abstraction
- **Service Layer Pattern**: Business logic encapsulation
- **DTO Pattern**: Data transfer with MapStruct mapping
- **Builder Pattern**: Response construction via `ResponseBuilder`

## Dependencies and Technology Stack

### Core Spring Dependencies
- Spring Boot 3.5.16 (`spring-boot-starter-parent` in `pom.xml`), Java 21
- Spring Security with OAuth2 client
- Spring Data JPA with MySQL
- Spring Cache + Spring Data Redis (Lettuce, Boot-managed version)
- Spring Boot Actuator + Micrometer Prometheus registry

### External Libraries
- **JWT**: io.jsonwebtoken (jjwt) 0.12.6
- **Mapping**: MapStruct 1.6.3
- **Database Migration**: Flyway (MySQL support)
- **Documentation**: SpringDoc OpenAPI 2.8.8 (`/swagger-ui.html`, `/api-docs`; off in prod unless `SWAGGER_ENABLED=true`)
- **Email**: Spring Mail (jakarta.mail)
- **Testing**: JUnit 5, Mockito (loaded as `-javaagent`), H2 for `mvn test`, Testcontainers for `-Pintegration`

### Development Tools
- **Lombok**: Reduces boilerplate code
- **Spring DevTools**: Hot reload support
- **Maven**: Build and dependency management

## Security Best Practices
- BCrypt password hashing. Passwords are limited to 72 UTF-8 bytes (BCrypt's
  input limit) on register, login and reset; `@StrongPassword` (8+ chars,
  upper, lower, digit, special) on register and reset.
- `JWT_SECRET_KEY` must be Base64 and decode to ≥ 256 bits; validated at
  startup. The prod profile has no fallback.
- Unknown e-mail, deleted account and wrong password get the same login
  error; resend-OTP, forgot-password, activation and reset answer the same
  way whether or not the account exists.
- Input validation with Bean Validation and custom validators
  (`@StrongPassword`, `@ValidEmail`, `@ValidFullName`, `@MaxUtf8Bytes`).
