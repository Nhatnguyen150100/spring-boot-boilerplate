# Release notes: review remediation (branch `fix/review-remediation`)

This release fixes the findings of the October 2026 code review (plans P1–P6).
Several fixes change behaviour that clients, operators or developers rely on.
Read the checklist before deploying.

## Deployment checklist

1. **Expect every user to log in again** (see [Sessions and tokens](#sessions-and-tokens)).
2. **Set `TRUSTED_PROXIES`** to the exact IP(s) of your load balancer / reverse proxy
   (`172.28.0.1` for `docker-compose.prod.yml` behind a proxy on the host; see
   [`TRUSTED_PROXIES`](#trusted_proxies)).
3. **Check `FRONTEND_URL` and `CORS_ALLOWED_ORIGINS`**: both are now validated at startup.
4. **Add the new environment variables** (or accept their defaults) and remove the
   obsolete ones: see [Environment variables](#environment-variables).
5. **Uploads**: move or delete the legacy flat files, and copy data out of the old
   bind mounts into the new named volumes (see [File uploads](#file-uploads) and
   [Docker and Compose](#docker-and-compose)).
6. **Give the Prometheus scraper an ADMIN bearer token**: `/actuator/prometheus` is ADMIN-only.
7. **Raise the shutdown grace period** to about 70s (see [Shutdown](#shutdown)).
8. **Multi-instance**: enable sticky sessions for the Google sign-in flow.
9. **Frontends**: update for the OAuth2 fragment redirect, the CORS credentials
   change and the status-code changes below.

## Sessions and tokens

- **All existing tokens stop working.** Access and refresh JWTs now carry a
  `token_type` claim, and tokens issued before the deploy have none, so they
  are rejected. Refresh tokens are now stored as SHA-256 hashes, so the plain
  values already in `refresh_tokens` never match again. Users log in once
  after the deploy; nothing else is needed. Old rows are purged by the
  nightly cleanup job once they expire.
- **OTPs issued before the deploy are invalid**: the Redis key format changed
  (`otp:<sha256(lower(trim(email)))>`). Users press "resend".
- An OTP is now discarded after **5 wrong guesses** (`AUTH_OTP_MAX_ATTEMPTS`);
  the user has to request a new one. Sending a new OTP (register, resend-otp,
  forgot-password) resets the wrong-guess count for it.
- **Logout signs out everywhere**: it blacklists the presented access token,
  revokes all of the user's refresh tokens and ends the access tokens of the
  user's other devices (they are refused from the next request on).
- **Password reset ends every live session**: refresh tokens are revoked and
  all access tokens issued before the reset are refused.
- Refresh rotates the token: the presented refresh token is revoked and a new
  pair is returned. Reusing an old refresh token gets 401.
- The JWT filter now loads the user on every request: an account that becomes
  INACTIVE or DELETED loses access on its next request.
- **The `email` and `status` JWT claims were removed** (the e-mail is the
  `sub` claim; the status is read from the database). An SPA that decoded
  them from the token must call `GET /api/v1/users/me` instead.
- `JWT_SECRET_KEY` must be Base64 and decode to at least 256 bits; startup
  fails otherwise.
- Consider shortening `JWT_EXPIRATION` (default 24h) to about 15 minutes now
  that refresh rotation and revocation work. Caveat: `/refresh-token` is in
  the AUTH rate-limit bucket (10 per minute, 100 per hour per client IP).
  With 15-minute tokens every active user refreshes 4 times an hour, so about
  25 users behind one NAT or corporate proxy exhaust the hourly quota. Raise
  `application.rate-limit.auth.requests-per-hour` (and `requests-per-minute`)
  accordingly, or keep a longer access-token lifetime.
- **PENDING accounts are never expired** (owner decision): a registration that
  is never activated keeps its e-mail address reserved, so someone can
  register an address they do not own and block its owner until activation.
  No cleanup job exists; add one if this matters for you.

## API behaviour changes

### Status codes and error bodies

| Situation | Before | Now |
|---|---|---|
| Login with correct password on a non-ACTIVE account | 400 | **403** "Account is not active" |
| Unique-constraint / data-integrity violation | 500 | **409** |
| Authentication backend failure (e.g. database down during login) | 401 | **500** |
| Framework errors (malformed JSON, wrong method, missing parameter, unsupported media type, upload too large, ...) | 500 | their real **4xx** (400/404/405/413/415) |
| Expired, malformed or refresh token sent as bearer on a protected route | exception thrown by the JWT filter | **401** (the request stays anonymous) |
| Download or delete of a missing file | 400 | **404** |
| Download or delete of another user's file | allowed (one shared folder) | **404** |
| Server-side upload/delete I/O failure | 400 | **500** |

- Every 5xx body now carries the fixed message `Internal server error`;
  exception text (SQL, stack traces) is never returned.
- Validation error messages now include object-level (class-level) errors.
- Unknown e-mail, deleted account and wrong password return the same login
  error. Resend-OTP, forgot-password, activation and reset answer the same
  way whether or not the account exists.

### Rate limiting

- The **API bucket is now applied**: every `/api/**` request outside
  `/api/v1/auth/**` and `/api/v1/files/**` is limited to 60 / 600 / 6000 per
  minute / hour / day per client IP. Those requests used to count against
  GLOBAL (100 per minute). Each request now counts in exactly one bucket.
- Every 429 carries `Retry-After: 60`, including login lockout and the
  per-e-mail OTP throttle, and CORS exposes the header so browser clients can
  read it.
- Failed logins are throttled per e-mail + client IP (5 per 15 minutes by
  default; `AUTH_LOGIN_MAX_FAILURES`, `AUTH_LOGIN_FAILURE_WINDOW`).
  Successful logins are no longer counted, so a stranger can no longer lock
  a user out.
- The OTP-sending endpoints (register, resend-otp, forgot-password) are limited
  per e-mail with the AUTH quotas. OTP verification (activate, reset-password)
  no longer consumes that per-e-mail quota.
- IPv6 clients are keyed by their /64 prefix.
- The upload limit is per **client IP**, not per user (the old docs were wrong).

### CORS

- **Credentials are no longer allowed** (`Access-Control-Allow-Credentials` is
  not sent). An SPA that calls the API with `credentials: 'include'` /
  `withCredentials: true` fails its preflight; drop that option. The API
  authenticates with the `Authorization` header only.
- `CORS_ALLOWED_ORIGINS` must list exact origins; `*` fails startup.

### Google sign-in (OAuth2)

- The callback **no longer returns JSON**. The browser is redirected to
  `FRONTEND_URL` + `AUTH_OAUTH2_REDIRECT_PATH` (default `/oauth2/callback`)
  with the result in the URL fragment:
  `#access_token=...&refresh_token=...&token_type=Bearer`, or
  `#error=<code>` (e.g. `email_not_verified`, `email_domain_not_allowed`,
  `account_not_active`, `authentication_failed`). The SPA must read and then
  clear the fragment.
- `FRONTEND_URL` must be an absolute `http(s)://` URL without a query or
  fragment; startup fails otherwise.
- Google must report the e-mail as verified; existing non-ACTIVE accounts are refused.
- The authorization request lives in the in-memory HTTP session: with more
  than one instance, configure **sticky sessions** on the load balancer.

## File uploads

- Files are now stored per user: `<upload-dir>/<userId>/<filename>`, with
  in-flight uploads in `<upload-dir>/.tmp/`. **Legacy flat files directly in
  `<upload-dir>/` have no owner and are no longer served.** Before deploying,
  move each one into the owner's `<userId>/` folder or delete it.
- Uploads are accepted only if both the declared Content-Type and the file
  extension are allowlisted. Defaults: `image/png, image/jpeg, image/gif,
  image/webp, application/pdf, text/plain` and `png, jpg, jpeg, gif, webp, pdf, txt`
  (`UPLOAD_ALLOWED_CONTENT_TYPES`, `UPLOAD_ALLOWED_EXTENSIONS`). Types accepted
  before may now be rejected. There is no content sniffing.
- The "identical content already exists" response is gone: an upload is
  always stored under the requested name; a second upload with the same name
  replaces the first (concurrent same-name uploads: last write wins).
- File names containing control or format characters, `"`, `%`, `;`, `\`,
  U+2028/U+2029, or longer than 255 UTF-8 bytes are rejected with 400
  ("Invalid file name" / "File name is too long").
- `Content-Disposition` on download now uses RFC 6266 `filename*` (UTF-8) for
  non-ASCII names; ASCII names keep a plain `filename="..."`.
- Stored files get the mode `UPLOAD_FILE_PERMISSIONS` (default `rw-r--r--`).
  User folders are created with the process umask (usually 0755), so other OS
  users can list file names.
- In Docker, `.tmp/` must stay on the same volume as the user folders (it does
  with the provided Compose files).

## Docker and Compose

- `docker compose up` (base file) now runs the **prod** profile and has no
  credential defaults: `MYSQL_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD` and
  `REDIS_PASSWORD` must be set (the MySQL image refuses `DB_USERNAME=root`).
  MySQL and Redis are published on 127.0.0.1 only. For local development use
  the dev overlay; the four secrets still have to come from the shell or `.env`:

  ```bash
  MYSQL_ROOT_PASSWORD=dev-root DB_USERNAME=springapp DB_PASSWORD=dev-pass REDIS_PASSWORD=dev-redis \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build
  ```

  `docker-compose.debug.yml` (JDWP on 127.0.0.1:5005) is only for use with the dev overlay.
- The image is now `eclipse-temurin:21-jre-alpine`, runs as **non-root UID/GID
  10001**, and has no `curl` (healthchecks use `wget`). The heap is 75% of the
  container memory limit: set one (`docker-compose.prod.yml` sets 2G).
- **Uploads and logs moved from bind mounts to named volumes**
  (`springapp_uploads_data` / `springapp_logs_data`; prod:
  `springapp_uploads_prod_data` / `springapp_logs_prod_data`). Data in the old
  `./uploads` and `./logs` directories is **not** migrated. Copy it once,
  with the stack stopped (prod names shown; drop `_prod` for the base stack):

  ```bash
  docker run --rm -v "$PWD/uploads:/from:ro" -v springapp_uploads_prod_data:/to alpine \
    sh -c 'cp -a /from/. /to/ && chown -R 10001:10001 /to'
  docker run --rm -v "$PWD/logs:/from:ro" -v springapp_logs_prod_data:/to alpine \
    sh -c 'cp -a /from/. /to/ && chown -R 10001:10001 /to'
  ```

  Then move legacy flat upload files into `<userId>/` folders (see above).
  The volume names are fixed, so two checkouts on the same host share them.
- `docker-compose.prod.yml` pins its network to `172.28.0.0/16` (gateway
  `172.28.0.1`), so a reverse proxy on the host has a known address to trust
  (see [`TRUSTED_PROXIES`](#trusted_proxies)). If that subnet clashes with an
  existing network on the host, change it in the file and in `TRUSTED_PROXIES`.
- **Prod Redis no longer evicts keys**: `maxmemory-policy` changed from
  `allkeys-lru` to `noeviction`. The same Redis holds the access-token
  blacklist, the "tokens not before" markers and the login/OTP failure
  counters; evicting them would revive revoked tokens and reset lockouts.
  Cache entries all have TTLs. At the 512MB limit Redis now rejects writes
  (cache writes are logged and skipped; logins and OTP sends fail) instead of
  dropping keys, so monitor its memory.
- Both Compose files set `stop_grace_period: 70s` on the app (see
  [Shutdown](#shutdown)).
- `docker-compose.prod.yml` sets `REDIS_SSL_ENABLED=false` for its bundled
  plain-TCP Redis. Outside Compose, the prod profile defaults Redis TLS to on
  (`REDIS_SSL_ENABLED=true`); set it to `false` for a plain-TCP Redis.

## Environment variables

The full list of additions and removals, and a complete catalogue of every
variable, is in
[docs/superpowers/plans/2026-10-28-remediation-env-example-changes.md](superpowers/plans/2026-10-28-remediation-env-example-changes.md).
Apply it to `.env.example` and your `.env`, then run
`scripts/check-env-example.sh` (no output means in sync).

New (all have defaults unless noted):

- Proxies: `TRUSTED_PROXIES` (default empty; set it behind a load balancer, see below)
- Redis: `REDIS_TIMEOUT`, `REDIS_CONNECT_TIMEOUT` (2s each), `REDIS_FAIL_FAST` (now documented)
- E-mail pool and SMTP: `ASYNC_EMAIL_CORE_SIZE`, `ASYNC_EMAIL_MAX_SIZE`,
  `ASYNC_EMAIL_QUEUE_CAPACITY` (5 / 5 / 100), `MAIL_CONNECTION_TIMEOUT`,
  `MAIL_READ_TIMEOUT`, `MAIL_WRITE_TIMEOUT` (10s each)
- Auth: `AUTH_SKIP_EMAIL_VERIFICATION` (false), `AUTH_LOGIN_MAX_FAILURES` (5),
  `AUTH_LOGIN_FAILURE_WINDOW` (15m), `AUTH_OTP_MAX_ATTEMPTS` (5),
  `AUTH_OAUTH2_REDIRECT_PATH` (`/oauth2/callback`)
- Uploads: `UPLOAD_ALLOWED_CONTENT_TYPES`, `UPLOAD_ALLOWED_EXTENSIONS`, `UPLOAD_FILE_PERMISSIONS`

Removed: `CACHE_DEFAULT_TTL`, `CACHE_TOKENS_TTL` (only `CACHE_USERS_TTL`, default
10m, remains) and `MAIL_MAX_CONCURRENT_SENDS` (concurrency is
`ASYNC_EMAIL_MAX_SIZE`). Strict binding now also covers `application.auth.*`:
an unknown key under `application.auth`, `.cache`, `.mail`, `.async`,
`.rate-limit`, `.security.jwt` or `.file.storage` in any config source fails
startup, so delete stale keys such as `application.cache.default-ttl` from
external config files.

### `TRUSTED_PROXIES`

List the exact IP(s) of the proxies in front of the app (comma-separated; no
CIDR ranges; IPv6 in the full form Tomcat reports). Only requests from these
peers may set the client IP (`X-Forwarded-For` / `X-Real-IP`) and, in the prod
profile, the scheme (`X-Forwarded-Proto`). Left empty (the default), those
headers are ignored: every client shares the proxy's IP for rate limiting and
login throttling, and HTTPS requests look like HTTP to the app. The prod
profile logs a WARN at startup in that case.

With `docker-compose.prod.yml` and a reverse proxy on the host (publishing to
the app's `127.0.0.1:8080` binding), Tomcat does not see the proxy's address:
Docker forwards the connection, so the peer is the bridge gateway. The
network is pinned for this reason; set `TRUSTED_PROXIES=172.28.0.1` and make
the proxy send `X-Forwarded-For` and `X-Forwarded-Proto`.

## Development

- The dev profile **no longer auto-activates new accounts**. Set
  `AUTH_SKIP_EMAIL_VERIFICATION=true` to skip the OTP step locally. It is
  honoured only while the `dev` profile is active and ignored (with a WARN)
  elsewhere. The Compose dev overlay sets it.
- `mvn test` is hermetic (H2; no MySQL, Redis, SMTP or Docker) and no longer
  writes `uploads-test/` or `logs/` into the working tree.
- Integration tests against real MySQL and Redis: `mvn -Pintegration verify`
  (Docker required).
- Spring Boot is now 3.5.16. Unused `jsoup` and web-services dependencies
  were removed.

## Shutdown

Shutdown is graceful: Spring Boot gives in-flight requests up to 30s, then the
e-mail pool gets up to 30s to send queued mails while Redis is still up (and,
if an SMTP server hangs, up to 30s more when the pool is destroyed). Allow at
least **70s** (90s for the worst case):

- Kubernetes: `terminationGracePeriodSeconds: 70` or more.
- Docker Compose: the default stop timeout is 10s; both Compose files now set
  `stop_grace_period: 70s` on the app service (with your own Compose file, set
  it or use `docker compose stop -t 70`).

Mails still queued after the drain timeout are lost; users can request a new OTP.

## Monitoring

- `/actuator/prometheus` and every actuator endpoint except `health` and
  `info` now require an **ADMIN** bearer token. Health component details are
  shown only to ADMIN (always in the dev profile).
- Metric changes: `auth.login.attempts` was replaced by `auth.login.success`
  and `auth.login.failed{reason}`. New: `auth.otp.failed{purpose}`,
  `auth.token.refresh{result}`, `auth.logout`, `auth.oauth2.login{result}`,
  `auth.ratelimit.rejected{bucket}`. Unchanged: `auth.registration.attempts`
  (now counts successful registrations), `auth.login.duration`,
  `auth.registration.duration`.
- Logs: plain text in prod (no ANSI colours), rolling files capped at 3GB
  (`application.log`) and 1GB (`error.log`).

## Data

- Migration `V3__demote_manager_role.sql` turns every `MANAGER` user into
  `USER` (the role was removed; it was never checked anywhere).
- The user-profile cache moved to keys prefixed `v2:` with a 10-minute TTL.
  Old cache entries are ignored and expire on their own. It is cache-aside: a
  profile read racing an update can put the old profile back after the
  eviction, and it then lives until `CACHE_USERS_TTL` expires.
- Collation comes from the MySQL server default; the Compose files start
  MySQL with `utf8mb4_unicode_ci`. E-mails are now stored lower-cased and
  trimmed, so lookups rely on a case-insensitive collation only for legacy
  mixed-case rows. On a server with a case-sensitive default collation,
  lower-case those rows (`UPDATE users SET email = LOWER(TRIM(email))`, after
  checking for duplicates).
- Expired and revoked refresh tokens are deleted nightly at 02:00 by exactly
  one instance (Redis lock `lock:token-cleanup`).
