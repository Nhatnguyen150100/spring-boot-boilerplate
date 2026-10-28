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
