-- Refresh tokens table — must match com.spring.app.modules.auth.entities.RefreshToken
-- (+ BaseEntity UUID id, + BaseAuditingEntity audit columns).
-- Without this migration `spring.jpa.hibernate.ddl-auto=validate` fails at
-- startup: the entity exists but the table it maps to does not.
-- UUID is stored as BINARY(16), matching V1 and Hibernate's default on MySQL.
CREATE TABLE refresh_tokens (
    id          BINARY(16)   NOT NULL,
    token       VARCHAR(512),
    is_revoked  BIT(1)       NOT NULL DEFAULT b'0',
    expiry_date DATETIME(6),
    user_id     BINARY(16),
    created_by  VARCHAR(255),
    created_at  DATETIME(6)  NOT NULL,
    updated_by  VARCHAR(255),
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- findByToken is on the hot path of every token refresh. Prefix index: a full
-- index on VARCHAR(512) utf8mb4 is close to InnoDB's key-length limit.
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens (token(255));

-- revokeAllByUser / findAllByUserAndIsRevokedFalse filter on both columns.
CREATE INDEX idx_refresh_tokens_user_revoked ON refresh_tokens (user_id, is_revoked);
