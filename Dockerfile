# ---- Build stage ----
FROM gradle:8.12-jdk21 AS build

WORKDIR /app

# Copy dependency definitions first for caching
COPY build.gradle.kts settings.gradle.kts gradle/libs.versions.toml ./
COPY gradle/ ./gradle/
COPY gradlew ./
RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src/ ./src/

# Build the application
RUN ./gradlew build --no-daemon -x test

# ---- Run stage ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Expose the application port
EXPOSE 8080

# Run the Ktor application
ENTRYPOINT ["java", "-jar", "app.jar"]
