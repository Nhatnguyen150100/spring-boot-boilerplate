# Review Remediation — Plan Index

Spec: `docs/superpowers/specs/2026-10-28-review-remediation-design.md`

Execute strictly in this order; each plan assumes all earlier ones are applied.

| # | Plan | Tasks |
|---|---|---|
| P1 | `2026-10-28-remediation-P1-persistence-cache-redis.md` | 7 |
| P2 | `2026-10-28-remediation-P2-email-otp.md` | 7 |
| P3 | `2026-10-28-remediation-P3-auth-security.md` | 11 |
| P4 | `2026-10-28-remediation-P4-web-errors-ratelimit.md` | 10 |
| P5 | `2026-10-28-remediation-P5-uploads.md` | 8 |
| P6 | `2026-10-28-remediation-P6-build-ops-docs-cleanup.md` | 8 |

P7 (service-layer refactor) is deferred; write its plan after P6 lands.

## Rules for executors

- Plans were authored in parallel against HEAD `92eac07`. Where a plan cites
  line numbers in a file an earlier plan already changed (notably
  `src/test/resources/application-test.yml`, which P1 shortens by 2 lines),
  locate the edit by **content**, not by line number.
- If a file's current content does not match what a step expects, stop and
  reconcile against the earlier plan that touched it — do not force the edit.
- `.env.example` is protected by the user's permission settings. Never work
  around a denial (no `git show`, `cat`, copying, etc.). Follow the
  "`.env.example` handling" decision recorded below.
- Each plan's Hand-offs section lists work owned by a later plan; check the
  hand-offs addressed to the plan you are executing before starting it.

## Amendments made during cross-plan review

1. **P4 Task 2** — `RateLimitFilter` now takes `MonitoringService` (added by
   P3 Task 4) and calls `recordRateLimitRejection(bucket.getEndpointType())`
   when a request is rejected; `RateLimitFilterTest` constructs and verifies it.
   (P3 hand-off.)
2. **P6 Task 6** — base `docker-compose.yml` Redis: password is mandatory
   (`${REDIS_PASSWORD:?...}`) because an empty `--requirepass` stops
   redis-server; healthcheck authenticates and requires `PONG` (a bare
   `redis-cli ping` exits 0 on NOAUTH).

Hand-offs verified as already covered:
- P2 → P3: OTP sent only after the user row is saved (P3 Task 3).
- P6 → P3: refresh tokens carry a unique `jti` (P3 Task 2), so two tokens
  minted in the same second differ; P6's 1.1 s wait in `AuthRoundTripIT` is
  harmless and may be removed.
- P1 → P3: P3 never calls `evictCachedToken`; OAuth2 user keeps the `"id"`
  attribute (auditing).
- P3 → P6: `frontend-url` deletion in P6 Task 3 Step 13 is grep-gated and will
  be skipped because P3 uses it; P6 docs list `auth.login.failed`.

## `.env.example` handling

Decision pending from the project owner (see conversation). Until resolved,
executors skip `.env.example` edits and append each intended change to
`docs/superpowers/plans/2026-10-28-remediation-env-example-changes.md`
(variable name, default, comment, which plan/task).
