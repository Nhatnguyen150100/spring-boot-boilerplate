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

# Expose port
EXPOSE 8000

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
