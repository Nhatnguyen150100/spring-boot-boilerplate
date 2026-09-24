# ============================
# Stage 1: Build the application
# ============================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

# Copy pom.xml and download dependencies first (cache layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build application (skip tests to speed up if needed)
RUN mvn clean package -DskipTests

# ============================
# Stage 2: Run the application
# ============================
FROM eclipse-temurin:21-jdk-alpine

# Set work directory
WORKDIR /app

# Copy only the built jar from builder stage
COPY --from=builder target/springapp-0.0.1-SNAPSHOT.jar app.jar

# Expose port (matches SERVER_PORT's default in application.yml)
EXPOSE 8080

# Fail-closed default: application.yml falls back to the "dev" profile when
# SPRING_PROFILES_ACTIVE is unset, which boots with the committed dev JWT
# secret and open Swagger UI. Both docker-compose.yml and
# docker-compose.prod.yml already set this env var explicitly, so they are
# unaffected; this only changes what happens if the image is ever run
# directly (`docker run`) without it -- the prod profile requires DB_HOST,
# JWT_SECRET_KEY and friends with no fallback, so a misconfigured run now
# crashes immediately instead of silently serving with dev secrets.
ENV SPRING_PROFILES_ACTIVE=prod

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
