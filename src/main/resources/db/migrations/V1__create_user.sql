-- Users table — must match com.spring.app.modules.auth.entities.User
-- (+ BaseEntity UUID id, + BaseAuditingEntity audit columns)
-- UUID is stored as BINARY(16): Hibernate's default mapping for java.util.UUID on MySQL.
CREATE TABLE users (
    id            BINARY(16)   NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password      VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    phone         VARCHAR(255),
    address       VARCHAR(255),
    date_of_birth DATE,
    description   VARCHAR(512),
    avatar_url    VARCHAR(255),
    role          VARCHAR(255) NOT NULL,
    status        VARCHAR(255) NOT NULL,
    created_by    VARCHAR(255),
    created_at    DATETIME(6)  NOT NULL,
    updated_by    VARCHAR(255),
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);
