# syntax=docker/dockerfile:1

# =========================================================================
# marketplace-service container image (Spring Boot 3.x, port 8081, /marketplace)
#
# BUILD CONTEXT MUST BE THE kitchensink/ DIRECTORY (the aggregator root), e.g.:
#   docker build -f marketplace-service/Dockerfile -t marketplace-service:latest .
# All COPY paths below are relative to kitchensink/.
# =========================================================================

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Copy the aggregator parent POM (referenced as <parent> via ../pom.xml) and the
# module POM first, so dependency resolution is cached as a separate layer.
COPY pom.xml ./pom.xml
COPY marketplace-service/pom.xml ./marketplace-service/pom.xml
RUN mvn -B -f marketplace-service/pom.xml -DskipTests dependency:go-offline

# Copy module sources and build the repackaged executable jar.
COPY marketplace-service/src ./marketplace-service/src
RUN mvn -B -f marketplace-service/pom.xml -DskipTests clean package

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy the Spring Boot fat jar (version-agnostic wildcard; resolves to marketplace-service-8.0.0.GA.jar).
COPY --from=build /workspace/marketplace-service/target/marketplace-service-*.jar /app/app.jar

EXPOSE 8081

# Optional but recommended: liveness via actuator (note the /marketplace context-path).
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8081/marketplace/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
