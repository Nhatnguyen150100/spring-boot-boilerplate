# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

### Essential Commands
- **Build**: `mvn clean install` - Clean and build the entire project
- **Run**: `mvn spring-boot:run` - Start the application on port 8080
- **Test**: `mvn test` - Run all unit tests
- **Package**: `mvn clean package` - Create deployable JAR file

### Development Environment
- **Java Version**: 21 (required)
- **Maven**: Used for dependency management and build
- **Profile**: Development profile is active by default (`spring.profiles.active=dev`)

### Database Setup
1. Create MySQL database: `db_test_spring`
2. Copy `.env.example` to `.env` and set `DB_*` there (never in the YAML files)
3. Flyway owns the schema (`FLYWAY_ENABLED=true` by default)
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
  the real values, loaded via `spring.config.import`)
- Tests: `src/test/resources/application-test.yml` (H2, Flyway off, Redis
  fail-fast off) so `mvn test` needs nothing running. Every property bound to
  a `@jakarta.validation` constraint (JWT expirations, cache TTLs, async pool
  sizes, mail, upload-dir...) is overridden here with a literal, never
  `${ENV_VAR}`. This matters for two separate leaks, not just one: a
  developer's `.env` **file** cannot reach these keys, and neither can a
  plain OS environment variable of the same name (e.g. a stray `export
  ASYNC_TASK_CORE_SIZE=0` in a dev shell or CI runner) — Spring's placeholder
  resolution checks OS env vars regardless of whether `.env` exists, so only
  a literal override actually blocks it. Properties without a validation
  constraint are NOT overridden here and can still pick up an ambient env
  var; that's fine since a bad value there can't fail the build.

Namespace rule: `spring.*` is for framework properties only; everything this
application owns lives under `application.*`, bound by the `@ConfigurationProperties`
classes in `configs/properties/` and validated at startup via `@Validated`.
Adding a setting means adding a field there. Strict binding
(`ignoreUnknownFields = false`) is enabled on the six leaf classes —
`JwtProperties`, `CacheProperties`, `MailProperties`, `AsyncProperties`,
`RateLimitProperties`, `FileStorageProperties` — so a typo'd key under one of
their prefixes fails startup instead of binding to nothing. `ApplicationProperties`
itself (the bare `application.*` prefix, covering `cors`, `redis`,
`frontend-url`, `trusted-proxies`, `oauth2-allowed-email-domains`) is left on
Spring Boot's default relaxed binding, because it is the shared parent prefix
for all the others; turning on strict binding there would make every child
class's keys look "unknown" to it and break startup entirely. A typo under
those specific keys is still silently ignored.

## Architecture Overview

### Core Architecture Pattern
The application follows a **layered architecture** with clear separation of concerns:

**Controller → Service → Repository → Database**

### Key Architectural Components

#### Module Structure
The application is organized into **feature-based modules** under `src/main/java/com/spring/app/modules/`:
- **auth**: Authentication and user registration
- **user**: User management operations  
- **upload**: File upload functionality

#### Security Architecture
- **JWT-based authentication** with refresh tokens
- **Role-based access control (RBAC)** with USER/ADMIN roles
- **OAuth2 integration** for Google authentication
- **Rate limiting** with Redis backend using Bucket4j
- **CORS configuration** for cross-origin requests

#### Caching Strategy
- **Redis caching** with 30-minute default TTL
- **Method-level caching** using Spring Cache abstraction
- **Session storage** in Redis for scalability

#### Configuration Management
Configuration is centralized using Spring Boot's configuration properties pattern:
- Custom properties classes in `configs/properties/`
- Type-safe configuration binding with `@ConfigurationProperties`
- Environment-specific property files

#### Response Handling
Standardized API responses using `ResponseBuilder` pattern:
- Consistent response format across all endpoints
- Global exception handling via `GlobalExceptionHandler`
- Standard HTTP status codes and error messages

#### Monitoring and Observability
- **Spring Boot Actuator** enabled with endpoints: health, metrics, prometheus
- **Micrometer metrics** with Prometheus integration
- **Custom business metrics** for authentication events
- **Structured logging** with logback configuration

#### Data Layer
- **JPA/Hibernate** for ORM with MySQL
- **Connection pooling** with HikariCP (max 20 connections)
- **Batch processing** optimizations for bulk operations
- **Audit trail** with base auditing entity

### Key Design Patterns
- **Repository Pattern**: Data access abstraction
- **Service Layer Pattern**: Business logic encapsulation  
- **DTO Pattern**: Data transfer with MapStruct mapping
- **Builder Pattern**: Response construction via ResponseBuilder
- **Template Method**: Email template system

## Dependencies and Technology Stack

### Core Spring Dependencies
- Spring Boot 3.5.0 (Java 21)
- Spring Security with OAuth2
- Spring Data JPA with MySQL
- Spring Cache with Redis
- Spring Boot Actuator for monitoring

### External Libraries
- **JWT**: io.jsonwebtoken (jjwt) 0.12.6
- **Mapping**: MapStruct 1.6.3  
- **Redis**: Lettuce driver 6.7.1
- **Rate Limiting**: Bucket4j 8.7.0
- **Database Migration**: Flyway (MySQL support)
- **Monitoring**: Micrometer with Prometheus
- **Documentation**: SpringDoc OpenAPI 2.8.8
- **Email**: Spring Mail with JSoup for HTML templates

### Development Tools
- **Lombok**: Reduces boilerplate code
- **Spring DevTools**: Hot reload support
- **Maven**: Build and dependency management

## Important Implementation Notes

### Authentication Flow
1. User registration with email verification (OTP system)
2. JWT token generation with configurable expiration
3. Refresh token mechanism for seamless authentication
4. Role-based access control for protected endpoints

### Rate Limiting Configuration
The application implements comprehensive rate limiting:
- **Global**: 100 req/min, 1000 req/hour, 10000 req/day
- **Auth endpoints**: 10 req/min, 100 req/hour, 1000 req/day  
- **Upload endpoints**: 5 req/min, 50 req/hour, 500 req/day
- **API endpoints**: 60 req/min, 600 req/hour, 6000 req/day

### File Upload System
- Upload directory: `uploads/` (configurable)
- Max file size: 10MB
- Rate limited per user
- Integration with authentication system

### Error Handling Strategy
- Global exception handler catches all exceptions
- Custom exceptions for domain-specific errors
- Standardized error response format
- Proper HTTP status code mapping

### Performance Optimizations
- Redis caching for frequently accessed data
- Connection pooling with optimized settings
- Batch processing for database operations
- Async processing for email operations
- JPA performance tuning (batch inserts, query optimization)

### Security Best Practices
- Password encryption with BCrypt
- JWT secret key configuration (must be set in production)
- CORS configuration for API access
- Rate limiting to prevent abuse
- Input validation with custom validators