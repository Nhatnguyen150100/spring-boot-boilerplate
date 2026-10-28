# Pending .env.example changes

`.env.example` is write-protected for agents. Apply these by hand.

## P1 Task 2 (Redis: let Spring Boot build the connection)

Insert after the `REDIS_SSL_ENABLED=false` line:

```
# Command / connect timeouts (Spring duration syntax).
REDIS_TIMEOUT=2s
REDIS_CONNECT_TIMEOUT=2s
# Refuse to start when Redis is unreachable. Leave true outside tests.
REDIS_FAIL_FAST=true
```

Notes: `REDIS_TIMEOUT` and `REDIS_CONNECT_TIMEOUT` default to `2s` in `application.yml`.
`REDIS_FAIL_FAST` is already read by `application.redis.fail-fast` in `application.yml`
(default true); it is only being documented here.

## P1 Task 6 (typed, declared-only Redis cache)

Delete these two lines from the `# --- Cache TTLs ...` block (the settings no
longer exist; `application.cache.default-ttl` and `application.cache.tokens-ttl`
were removed and `CacheProperties` binds strictly, so they would be dead):

```
CACHE_DEFAULT_TTL=30m
CACHE_TOKENS_TTL=5m
```

The block should then read:

```
# --- Cache TTLs (Spring duration syntax: 30m, 90s, 2h) ----------------------
CACHE_USERS_TTL=10m
```

## P2 Task 2 (bounded platform-thread e-mail executor)

Insert immediately after the `ASYNC_TASK_QUEUE_CAPACITY` line, in the same style
as the neighbouring `ASYNC_TASK_*` lines (if those are commented out, prefix these
variable lines with `# ` too). If the file has no `ASYNC_TASK_*` lines, append them
at the end under a `# --- Async e-mail pool (application.async.email) ---` header:

```
# Platform-thread pool for outgoing e-mail. max-size caps concurrent SMTP sessions.
ASYNC_EMAIL_CORE_SIZE=5
ASYNC_EMAIL_MAX_SIZE=5
ASYNC_EMAIL_QUEUE_CAPACITY=100
```

Notes: defaults in `application.yml` are 5 / 5 / 100 (read by `application.async.email.*`).

## P2 Task 3 (SMTP connect/read/write timeouts)

Insert immediately after the last `MAIL_*` line (e.g. `MAIL_SSL_ENABLED`), in the same
style (commented or not) as the neighbouring `MAIL_*` lines that have a safe default.
If there are no `MAIL_*` lines, append them at the end under a
`# --- Mail (application.mail) ---` header:

```
# SMTP socket timeouts (Spring duration syntax, 1ms..5m).
MAIL_CONNECTION_TIMEOUT=10s
MAIL_READ_TIMEOUT=10s
MAIL_WRITE_TIMEOUT=10s
```

Notes: defaults in `application.yml` are 10s each (read by
`application.mail.connection-timeout|read-timeout|write-timeout`).

## P2 Task 4 (remove send semaphore / `max-concurrent-sends`)

No change expected: `MAIL_MAX_CONCURRENT_SENDS` was never documented in `.env.example`.
If a line for it exists anyway, delete it (`application.mail.max-concurrent-sends` no
longer exists and `MailProperties` binds strictly). Concurrency is now capped by
`ASYNC_EMAIL_MAX_SIZE`.

## P3 Task 5 (e-mail verification skip is an explicit dev-only opt-in)

Append (e.g. next to the other auth/app settings, or at the end under a
`# --- Auth (application.auth) ---` header) the default line, followed by a
commented-out `=true` example that a developer uncomments (in place of the
default line) to opt in:

```
# Dev only: new accounts start ACTIVE without the e-mail OTP. Honoured ONLY when
# the dev profile is also active; ignored (with a WARN) in every other profile.
# Leave false/unset outside local development.
AUTH_SKIP_EMAIL_VERIFICATION=false
# AUTH_SKIP_EMAIL_VERIFICATION=true
```

Notes: default in `application.yml` is `false` (read by
`application.auth.skip-email-verification`). Developers who relied on dev
auto-activation must now set `AUTH_SKIP_EMAIL_VERIFICATION=true` in `.env`.

## P3 Task 8 (login lockout counts failures only, per e-mail + IP)

Append next to `AUTH_SKIP_EMAIL_VERIFICATION` (under the
`# --- Auth (application.auth) ---` header), commented out like other
optional tunables:

```
# Failed logins allowed per (e-mail, client IP) before further attempts are
# refused (429) until the window expires. Successful logins are never counted.
# AUTH_LOGIN_MAX_FAILURES=5
# AUTH_LOGIN_FAILURE_WINDOW=15m
```

Notes: defaults in `application.yml` are `5` and `15m` (read by
`application.auth.login-max-failures` / `login-failure-window`; the window uses
Spring duration syntax and must be at least 1s).

## P3 Task 9 (OAuth2 redirects to the frontend)

Append next to the other `AUTH_*` lines (under the
`# --- Auth (application.auth) ---` header), commented out like other
optional tunables:

```
# SPA route, appended to FRONTEND_URL, that receives the OAuth2 result in the
# URL fragment (#access_token=...&refresh_token=...&token_type=Bearer or
# #error=<code>). Must start with '/'.
# AUTH_OAUTH2_REDIRECT_PATH=/oauth2/callback
```

Notes: default in `application.yml` is `/oauth2/callback` (read by
`application.auth.oauth2-redirect-path`). `FRONTEND_URL` (already documented)
is now also the OAuth2 redirect target, so it must point at the SPA in every
environment that uses Google sign-in.

## P3 final review I3 (per-OTP guess cap)

Append next to the other `AUTH_*` lines (under the
`# --- Auth (application.auth) ---` header), commented out like other
optional tunables:

```
# Wrong guesses allowed per OTP (activation / password reset); the last one
# discards the OTP and the user has to request a new one. Must be >= 1.
# AUTH_OTP_MAX_ATTEMPTS=5
```

Notes: default in `application.yml` is `5` (read by
`application.auth.otp-max-attempts`). OTP verification
(`/activate`, `/reset-password`) no longer consumes the per-e-mail AUTH
rate-limit bucket; only the OTP-sending endpoints do.

## P5 Task 3 (upload allowlists and file permissions)

Insert directly below the `UPLOAD_DIR` line. If there is no `UPLOAD_DIR` line,
append `UPLOAD_DIR=uploads` followed by these lines at the end of the file, under a
`# --- File uploads ---` comment:

```
# Uploads are accepted only if BOTH the Content-Type and the extension are listed (comma-separated)
UPLOAD_ALLOWED_CONTENT_TYPES=image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain
UPLOAD_ALLOWED_EXTENSIONS=png,jpg,jpeg,gif,webp,pdf,txt
# POSIX mode of stored files (ls -l form)
UPLOAD_FILE_PERMISSIONS=rw-r--r--
```

Notes: defaults in `application.yml` are the values above (read by
`application.file.storage.allowed-content-types|allowed-extensions|file-permissions`).

## Complete catalogue (generated 2026-10-28)

Every `${VAR}` / `${VAR:default}` / `${VAR:?msg}` / `${VAR:-default}` name referenced by
`src/main/resources/application*.yml` (`application.yml`, `application-dev.yml`,
`application-prod.yml`) and `docker-compose*.yml` (`docker-compose.yml`, `.dev.yml`,
`.prod.yml`, `.debug.yml`; only `docker-compose.yml` and `.prod.yml` reference variables), with YAML comments ignored. 69 distinct variables.

Purpose: `.env.example` could not be read or edited by the automation (permission deny),
so reconcile it by hand against this table, then run
`scripts/check-env-example.sh` (exit 0 and no output means in sync; it prints the
`Used but missing` / `Listed ... but used nowhere` diff otherwise). The script takes an
optional file argument, default `.env.example`. Commented-out entries (`# VAR=`) count
as listed, so optional tunables can stay commented.

Reading the table: the middle column gives the default found in each referencing file.
`(required)` means the reference has no default (yml or compose `${VAR}`) or uses compose
`${VAR:?msg}`: the app (prod profile) fails startup, or Compose fails / warns, when it is unset. `(empty)` is an explicit empty default.
Where the same variable has different defaults in different files, all are listed; the
profile file that is active wins for the app. Compose-only names: `MYSQL_ROOT_PASSWORD`
(no yml reference).

### Variables with no default, required in prod

Required by `application-prod.yml` (app fails startup under `spring.profiles.active=prod`)
and/or by the compose files (`docker compose` refuses to start):

`CORS_ALLOWED_ORIGINS`, `DB_HOST`, `DB_NAME`, `DB_PASSWORD`, `DB_PORT`, `DB_USERNAME`, `FRONTEND_URL`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, `JWT_SECRET_KEY`, `MAIL_FROM`, `MAIL_HOST`, `MAIL_PASSWORD`, `MAIL_USERNAME`, `MYSQL_ROOT_PASSWORD`, `REDIS_HOST`, `REDIS_PASSWORD`, `REDIS_PORT`

Of these, only `MYSQL_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD` and `REDIS_PASSWORD`
are hard-enforced by Compose (`${VAR:?msg}` in `docker-compose.yml`); `docker-compose.prod.yml`
uses plain `${DB_NAME}` / `${DB_USERNAME}` / `${DB_PASSWORD}` / ... (Compose substitutes an
empty string and only warns); the rest are enforced by `application-prod.yml` at app startup.
`docker-compose.dev.yml` and `docker-compose.debug.yml` reference no variables. Each of them needs a line in
`.env.example` (an empty or placeholder value; never a real secret). Variables not in this
list have a working default and may be listed commented-out.

Dev-only fallbacks (do not copy into prod): `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`
default to `unset-google-*` placeholders in `application.yml`; `SSL_*` are referenced only by
`application-prod.yml` (`SSL_ENABLED` defaults to `false`).

| Variable | Default (per referencing file) | Prod-required |
|---|---|---|
| `APP_LOG_LEVEL` | `DEBUG` (application-dev.yml); `INFO` (application.yml) | no |
| `APP_NAME` | `springapp` (application.yml) | no |
| `ASYNC_EMAIL_CORE_SIZE` | `5` (application.yml) | no |
| `ASYNC_EMAIL_MAX_SIZE` | `5` (application.yml) | no |
| `ASYNC_EMAIL_QUEUE_CAPACITY` | `100` (application.yml) | no |
| `ASYNC_TASK_CORE_SIZE` | `5` (application.yml) | no |
| `ASYNC_TASK_MAX_SIZE` | `10` (application.yml) | no |
| `ASYNC_TASK_QUEUE_CAPACITY` | `25` (application.yml) | no |
| `AUTH_LOGIN_FAILURE_WINDOW` | `15m` (application.yml) | no |
| `AUTH_LOGIN_MAX_FAILURES` | `5` (application.yml) | no |
| `AUTH_OAUTH2_REDIRECT_PATH` | `/oauth2/callback` (application.yml) | no |
| `AUTH_OTP_MAX_ATTEMPTS` | `5` (application.yml) | no |
| `AUTH_SKIP_EMAIL_VERIFICATION` | `false` (application.yml) | no |
| `CACHE_USERS_TTL` | `10m` (application.yml) | no |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` (application.yml); (required) (application-prod.yml) | **yes** |
| `DB_HOST` | `localhost` (application.yml); (required) (application-prod.yml) | **yes** |
| `DB_NAME` | `db_test_spring` (application.yml, docker-compose.yml); (required) (application-prod.yml, docker-compose.prod.yml) | **yes** |
| `DB_PASSWORD` | (empty) (application.yml); (required) (application-prod.yml, docker-compose.prod.yml, docker-compose.yml) | **yes** |
| `DB_POOL_MAX_SIZE` | `20` (application.yml) | no |
| `DB_POOL_MIN_IDLE` | `10` (application-prod.yml); `5` (application.yml) | no |
| `DB_PORT` | `3306` (application.yml, docker-compose.prod.yml, docker-compose.yml); (required) (application-prod.yml) | **yes** |
| `DB_USERNAME` | (required) (application-prod.yml, docker-compose.prod.yml, docker-compose.yml); `root` (application.yml) | **yes** |
| `DB_USE_SSL` | `false` (application.yml) | no |
| `FLYWAY_ENABLED` | `true` (application.yml) | no |
| `FRONTEND_URL` | `http://localhost:3000` (application.yml); (required) (application-prod.yml) | **yes** |
| `GOOGLE_CLIENT_ID` | (required) (application-prod.yml); `unset-google-client-id` (application.yml) | **yes** |
| `GOOGLE_CLIENT_SECRET` | (required) (application-prod.yml); `unset-google-client-secret` (application.yml) | **yes** |
| `GOOGLE_REDIRECT_URI` | `http://localhost:8080/api/v1/auth/oauth2/code/google` (application.yml); (required) (application-prod.yml) | **yes** |
| `JPA_DDL_AUTO` | `validate` (application.yml) | no |
| `JPA_FORMAT_SQL` | `false` (application.yml); `true` (application-dev.yml) | no |
| `JPA_SHOW_SQL` | `false` (application.yml); `true` (application-dev.yml) | no |
| `JWT_EXPIRATION` | `86400000` (application.yml) | no |
| `JWT_REFRESH_EXPIRATION` | `604800000` (application.yml) | no |
| `JWT_SECRET_KEY` | (required) (application-prod.yml); `ZGV2LW9ubHktc2VjcmV0LWRvLW5vdC11c2UtaW4tcHJvZHVjdGlvbi1taW4tMjU2LWJpdA==` (application.yml) | **yes** |
| `LOG_PATH` | `logs` (application.yml); `/var/log/springapp` (application-prod.yml) | no |
| `MAIL_APP_NAME` | `SPRING_APP` (application.yml) | no |
| `MAIL_CONNECTION_TIMEOUT` | `10s` (application.yml) | no |
| `MAIL_FROM` | (empty) (application.yml); (required) (application-prod.yml) | **yes** |
| `MAIL_HOST` | (required) (application-prod.yml); `smtp.gmail.com` (application.yml) | **yes** |
| `MAIL_PASSWORD` | (empty) (application.yml); (required) (application-prod.yml) | **yes** |
| `MAIL_PORT` | `587` (application.yml) | no |
| `MAIL_READ_TIMEOUT` | `10s` (application.yml) | no |
| `MAIL_SSL_ENABLED` | `false` (application.yml) | no |
| `MAIL_USERNAME` | (empty) (application.yml); (required) (application-prod.yml) | **yes** |
| `MAIL_WRITE_TIMEOUT` | `10s` (application.yml) | no |
| `MAX_FILE_SIZE` | `10MB` (application.yml) | no |
| `MAX_REQUEST_SIZE` | `10MB` (application.yml) | no |
| `MYSQL_ROOT_PASSWORD` | (required) (docker-compose.prod.yml, docker-compose.yml) | **yes** |
| `OAUTH2_ALLOWED_EMAIL_DOMAINS` | (empty) (application.yml) | no |
| `REDIS_CONNECT_TIMEOUT` | `2s` (application.yml) | no |
| `REDIS_FAIL_FAST` | `true` (application.yml) | no |
| `REDIS_HOST` | `localhost` (application.yml); (required) (application-prod.yml) | **yes** |
| `REDIS_PASSWORD` | (empty) (application.yml); (required) (application-prod.yml, docker-compose.prod.yml, docker-compose.yml) | **yes** |
| `REDIS_PORT` | `6379` (application.yml, docker-compose.prod.yml, docker-compose.yml); (required) (application-prod.yml) | **yes** |
| `REDIS_SSL_ENABLED` | `false` (application.yml); `true` (application-prod.yml) | no |
| `REDIS_TIMEOUT` | `2s` (application.yml) | no |
| `ROOT_LOG_LEVEL` | `INFO` (application.yml) | no |
| `SERVER_PORT` | `8080` (application.yml, docker-compose.prod.yml, docker-compose.yml) | no |
| `SPRING_PROFILES_ACTIVE` | `dev` (application.yml) | no |
| `SSL_ENABLED` | `false` (application-prod.yml) | no |
| `SSL_KEYSTORE` | (empty) (application-prod.yml) | no |
| `SSL_KEYSTORE_PASSWORD` | (empty) (application-prod.yml) | no |
| `SSL_KEYSTORE_TYPE` | `PKCS12` (application-prod.yml) | no |
| `SWAGGER_ENABLED` | `false` (application-prod.yml); `true` (application.yml) | no |
| `TRUSTED_PROXIES` | (empty) (application.yml) | no |
| `UPLOAD_ALLOWED_CONTENT_TYPES` | `image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain` (application.yml) | no |
| `UPLOAD_ALLOWED_EXTENSIONS` | `png,jpg,jpeg,gif,webp,pdf,txt` (application.yml) | no |
| `UPLOAD_DIR` | `/app/uploads` (application-prod.yml); `uploads` (application.yml) | no |
| `UPLOAD_FILE_PERMISSIONS` | `rw-r--r--` (application.yml) | no |
