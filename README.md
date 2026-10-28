# SpringApp - Spring Boot Boilerplate

A Spring Boot 3.5 / Java 21 REST API boilerplate with JWT + Google OAuth2
authentication, e-mail OTP verification, Redis-backed caching and rate
limiting, per-user file uploads, Flyway-managed MySQL, and Prometheus metrics.

[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.16-6DB33F?logo=spring-boot)](https://spring.io/projects/spring-boot)
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
- [Support](#support)

## Features

- **Authentication**: JWT access + refresh tokens (typed, refresh tokens stored
  hashed and rotated on every refresh; logout signs out everywhere: it
  blacklists the access token, revokes all refresh tokens and ends the
  account's other access tokens).
- **E-mail verification**: 6-digit OTP sent by e-mail; accounts stay PENDING until activated.
- **Password reset** by e-mail OTP; a reset ends every live session of the account.
- **Google sign-in** (OAuth2/OIDC), verified e-mails only, optional domain allow-list;
  tokens are handed to the frontend in the URL fragment.
- **Roles**: `USER` and `ADMIN` (method security enabled; Prometheus scrape is ADMIN-only).
- **Rate limiting**: Redis fixed-window counters per client IP, four buckets
  (auth, upload, API, global), plus failed-login throttling per e-mail + IP.
- **Caching**: Spring Cache on Redis for immutable DTOs (the user profile).
- **File uploads**: per-user folders, content-type + extension allow-list, 10MB limit.
- **Persistence**: MySQL 8, Flyway migrations, Hibernate in `validate` mode, JPA auditing.
- **Observability**: Actuator health, Prometheus metrics, custom auth counters and timers.

## Technology stack

| Area | Technology |
|---|---|
| Framework | Spring Boot 3.5.16, Java 21, Maven |
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
- MySQL 8.0+ and Redis 7+ (or use Docker, below). Redis is required: the app
  refuses to start without it.

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

Outside the prod profile every variable has a working local default, so
`.env` can stay almost empty for a first run. Typical values:

```properties
DB_USERNAME=your_username
DB_PASSWORD=your_password

# Base64, >= 256-bit. Generate with: openssl rand -base64 48
JWT_SECRET_KEY=

MAIL_USERNAME=your-email@gmail.com
MAIL_PASSWORD=your-app-password

# Local only, dev profile only: new accounts start ACTIVE without the OTP e-mail
# AUTH_SKIP_EMAIL_VERIFICATION=true
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
# Swagger UI (off in prod unless SWAGGER_ENABLED=true): http://localhost:8080/swagger-ui.html
```

## Running with Docker

| Command | What runs |
|---|---|
| `docker compose up --build` | MySQL + Redis + app with the **prod** profile (needs a complete `.env`) |
| `MYSQL_ROOT_PASSWORD=dev-root DB_USERNAME=springapp DB_PASSWORD=dev-pass REDIS_PASSWORD=dev-redis docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build` | Same stack, **dev** profile, e-mail verification skipped |
| add `-f docker-compose.debug.yml` to the dev command | plus JDWP debugger on `127.0.0.1:5005` |
| `docker compose -f docker-compose.prod.yml up -d` | Production stack; `.env` mandatory, every port bound to 127.0.0.1 |

The base file has no credential defaults: `MYSQL_ROOT_PASSWORD`,
`DB_USERNAME` (not `root`), `DB_PASSWORD` and `REDIS_PASSWORD` must come from
the shell or from `.env` (the dev overlay cannot supply them). MySQL and Redis
are published on 127.0.0.1 only.

`docker-compose.prod.yml` notes:

- **Reverse proxy on the host**: the app is published on `127.0.0.1:8080`,
  so the proxy's requests reach Tomcat through the Docker bridge gateway.
  The network is pinned to `172.28.0.0/16` with gateway `172.28.0.1`; set
  `TRUSTED_PROXIES=172.28.0.1` in `.env` and have the proxy send
  `X-Forwarded-For` and `X-Forwarded-Proto`. Without it every client shares
  one address for rate limiting and login lockout.
- **Redis** runs with `maxmemory 512mb` and `maxmemory-policy noeviction`. It
  holds security state (token blacklist, "tokens not before" markers, login
  and OTP failure counters) that must never be evicted; cache entries expire
  by TTL. At the limit Redis refuses writes instead of dropping keys, so
  watch its memory.
- Both Compose files give the app `stop_grace_period: 70s` (see the runtime
  model below).

The image is `eclipse-temurin:21-jre-alpine`, runs as non-root UID 10001, and
sizes the heap to 75% of the container memory limit, so give the container a
memory limit (`docker-compose.prod.yml` sets 2G). Uploads and logs are stored
in named volumes (`springapp_uploads_data` / `springapp_logs_data`;
`springapp_uploads_prod_data` / `springapp_logs_prod_data` in prod). The names
are fixed, so two checkouts on the same host share them.

Upgrading a deployment that used the old `./uploads` / `./logs` bind mounts:
see [docs/RELEASE-NOTES-remediation.md](docs/RELEASE-NOTES-remediation.md).

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

Errors use the same envelope. 5xx responses always carry the message
`Internal server error`.

### Authentication: `/api/v1/auth`

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Register; sends an OTP e-mail | public |
| `POST` | `/api/v1/auth/resend-otp` | Resend the activation OTP | public |
| `POST` | `/api/v1/auth/activate` | Activate the account with the OTP | public |
| `POST` | `/api/v1/auth/login` | Returns access + refresh token | public |
| `POST` | `/api/v1/auth/refresh-token` | Exchange a refresh token for a new pair | refresh token in body |
| `DELETE` | `/api/v1/auth/logout` | Sign out everywhere: blacklist the access token, revoke all refresh tokens, end the other access tokens | Bearer access token |
| `POST` | `/api/v1/auth/forgot-password` | Send a password-reset OTP | public |
| `POST` | `/api/v1/auth/reset-password` | Reset the password with the OTP | public |
| `GET` | `/api/v1/auth/oauth2/authorization/google` | Start Google sign-in (browser redirect) | public |

After Google sign-in the browser is redirected to
`FRONTEND_URL` + `AUTH_OAUTH2_REDIRECT_PATH` (default `/oauth2/callback`)
with `#access_token=...&refresh_token=...&token_type=Bearer` or
`#error=<code>` in the URL fragment. `FRONTEND_URL` must be an absolute
`http(s)://` URL without a query or fragment (checked at startup).

### User: `/api/v1/users`

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `GET` | `/api/v1/users/me` | Current user's profile | Bearer |
| `PUT` | `/api/v1/users/me/update` | Update the current user's profile | Bearer |

### Files: `/api/v1/files` (scoped to the caller's own folder)

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| `POST` | `/api/v1/files` | Upload (multipart field `file`) | Bearer |
| `GET` | `/api/v1/files/download/{filename}` | Download | Bearer |
| `DELETE` | `/api/v1/files/delete/{filename}` | Delete | Bearer |

A file of another user, or a missing file, is a 404.

## Security

- **Tokens**: HS256 JWTs with a `token_type` claim. Only access tokens are
  accepted as `Authorization: Bearer`; only refresh tokens at `/refresh-token`.
  Defaults: access 24h (`JWT_EXPIRATION`), refresh 7 days
  (`JWT_REFRESH_EXPIRATION`). Refresh tokens are stored as SHA-256 hashes.
  Every authenticated request reloads the user, so a deactivated account
  loses access immediately.
- **Accounts**: only ACTIVE users can log in or use a token. Unknown e-mail
  and wrong password get the same login error; OTP, resend and reset
  responses do not reveal whether an e-mail is registered. An OTP expires
  after 3 minutes or 5 wrong guesses; once a new OTP is stored, it starts
  with a fresh guess count. Unactivated (PENDING) accounts are never expired, so they keep
  their e-mail address reserved.
- **Passwords**: BCrypt. `@StrongPassword` policy on register/reset (8+
  chars, upper, lower, digit, special), max 72 bytes.
- **Roles**: `USER` (default), `ADMIN`. Every actuator endpoint except
  `health` and `info` (i.e. `/actuator/prometheus`) requires `ADMIN`.
- **CSRF** is disabled: the API is stateless and uses bearer tokens, not
  cookies. **CORS** origins come from `CORS_ALLOWED_ORIGINS` (exact origins,
  `*` is rejected); credentials are not allowed.
- **Headers**: Spring Security defaults (`X-Content-Type-Options`,
  `X-Frame-Options: DENY`, cache control; HSTS on HTTPS requests).
- **Rate limits** (per client IP, per minute / hour / day; 429 with
  `Retry-After: 60` when exceeded, as for every other 429; CORS exposes the
  header). Each request counts in exactly one bucket:

  | Bucket | Paths | Limits |
  |---|---|---|
  | AUTH | `/api/v1/auth/**` | 10 / 100 / 1000 |
  | UPLOAD | `/api/v1/files/**` | 5 / 50 / 500 |
  | API | other `/api/**` | 60 / 600 / 6000 |
  | GLOBAL | everything else | 100 / 1000 / 10000 |

  Endpoints that send an OTP e-mail are also limited per e-mail address, and
  failed logins are throttled per e-mail + IP (5 per 15 minutes by default).
- **Behind a reverse proxy / load balancer**: set `TRUSTED_PROXIES` to its
  exact IP(s). Otherwise `X-Forwarded-For` and `X-Forwarded-Proto` are
  ignored, every client shares the proxy's IP for rate limiting and login
  throttling, and HTTPS requests look like HTTP. With the prod Compose stack
  behind a host proxy, that IP is the bridge gateway `172.28.0.1`.
- **Google sign-in** keeps its OAuth2 state in the in-memory HTTP session.
  With more than one instance, enable sticky sessions on the load balancer.

## Runtime model

- HTTP requests run on **virtual threads**. The database pool
  (`DB_POOL_MAX_SIZE`, default 20) is the effective concurrency limit for DB work.
- E-mail is sent on a **bounded platform-thread pool** (`ASYNC_EMAIL_*`,
  default 5 threads, queue 100) with SMTP timeouts (`MAIL_*_TIMEOUT`, 10s).
  When it is saturated, new mails are logged and dropped instead of blocking
  requests; the user requests a new OTP.
- Shutdown is graceful: in-flight requests get up to 30s, then queued e-mails
  up to 30s more. Allow about 70s of grace in your orchestrator (the Compose
  files set `stop_grace_period: 70s`).
- A nightly job (02:00) deletes expired/revoked refresh tokens. A Redis lock
  ensures only one instance runs it.

## Monitoring and logging

```bash
GET /actuator/health        # public; component details for ADMIN (everyone in dev)
GET /actuator/info          # public
GET /actuator/prometheus    # ADMIN bearer token
```

Custom metrics (Prometheus appends `_total` to counters):

- `auth.login.success`
- `auth.login.failed` (tag `reason`)
- `auth.registration.attempts`
- `auth.otp.failed` (tag `purpose`)
- `auth.token.refresh` (tag `result`)
- `auth.logout`
- `auth.oauth2.login` (tag `result`)
- `auth.ratelimit.rejected` (tag `bucket`)
- `auth.login.duration` (timer)
- `auth.registration.duration` (timer)

Logs are plain text: console, plus rolling `application.log` / `error.log`
under `LOG_PATH` (daily and at 100MB, 30 days, size-capped). Tests log to the
console only.

## Testing

```bash
mvn test                      # hermetic: H2, no MySQL/Redis/SMTP/Docker
mvn -Pintegration verify      # + Testcontainers MySQL 8.0 and Redis 7 (Docker required)
```

The integration suite checks Flyway + Hibernate `validate` on real MySQL, the
register → activate → login → refresh → logout round trip, the Redis
rate-limit window and the profile cache on real Redis, and JPA auditing.

## Configuration

| File | Committed? | Contains |
|---|---|---|
| `src/main/resources/application.yml` | yes | Every setting, as structure + `${ENV_VAR:default}` |
| `src/main/resources/application-dev.yml` | yes | Only what dev changes (SQL logging, DevTools, debug levels, health details) |
| `src/main/resources/application-prod.yml` | yes | Only what prod changes; most placeholders have no fallback |
| `.env.example` | yes | Catalogue of every variable |
| `.env` | **no** | Your real values, loaded via `spring.config.import` |

- `spring.*` holds framework properties. `application.*` holds everything the
  app owns, bound and validated at startup by the classes in
  `configs/properties/`. Unknown keys under their prefixes (`application.auth`,
  `.cache`, `.mail`, `.async`, `.rate-limit`, `.security.jwt`,
  `.file.storage`) fail startup.
- `scripts/check-env-example.sh` verifies that `.env.example` lists exactly
  the variables the YAML and Compose files use.
- Upgrading from an earlier version: read
  [docs/RELEASE-NOTES-remediation.md](docs/RELEASE-NOTES-remediation.md).

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
├── handlers/        # OAuth2 success/failure redirect, access denied
├── modules/
│   ├── auth/        # controller, dto, entities, mapper, repositories, services
│   ├── user/
│   └── upload/
├── shared/          # cross-module services (JWT, Redis, mail, monitoring, ...)
├── templates/       # OTP e-mail builder
└── utils/           # client keys, JWT and OTP helpers
```

## Support

- **Email**: nhatnguyen150100@gmail.com
- **Issues**: [GitHub Issues](https://github.com/Nhatnguyen150100/spring-boot-boilerplate/issues)
