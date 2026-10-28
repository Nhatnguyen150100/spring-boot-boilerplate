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
