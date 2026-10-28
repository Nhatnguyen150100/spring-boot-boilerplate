# ============================
# Stage 1: Build the application
# ============================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy pom.xml and download dependencies first (cache layer)
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Copy source code and build (tests run in CI, not in the image build)
COPY src ./src
RUN mvn -B clean package -DskipTests

# ============================
# Stage 2: Run the application
# ============================
# JRE only: no compiler or JDK tools in the runtime image.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Unprivileged runtime user with a fixed UID/GID (10001) so volume ownership is
# predictable. The writable directories are created here, owned by that user;
# named volumes mounted on them inherit the ownership on first use. The upload
# temp area (.tmp) lives inside /app/uploads, so it stays on the same volume as
# the user folders (required for the atomic move).
RUN addgroup -S -g 10001 app \
  && adduser -S -D -H -u 10001 -G app app \
  && mkdir -p /app/uploads /app/logs /var/log/springapp \
  && chown -R app:app /app /var/log/springapp

COPY --from=builder --chown=app:app /build/target/springapp-0.0.1-SNAPSHOT.jar app.jar

USER app:app

# Expose port (matches SERVER_PORT's default in application.yml)
EXPOSE 8080

# Fail-closed default: application.yml falls back to the "dev" profile when
# SPRING_PROFILES_ACTIVE is unset, which boots with the committed dev JWT
# secret and open Swagger UI. The prod profile requires DB_HOST,
# JWT_SECRET_KEY and friends with no fallback, so a misconfigured
# `docker run` crashes immediately instead of silently serving with dev secrets.
ENV SPRING_PROFILES_ACTIVE=prod

# Container-aware heap: 75% of the container memory limit (the JVM reads the
# cgroup limit), and exit on OOM so the orchestrator restarts a clean process.
# JAVA_TOOL_OPTIONS (e.g. the debug overlay's JDWP agent) is added on top.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
