# syntax=docker/dockerfile:1

# =============================================================================
# marketplace-service — Multi-stage container image (Spring Boot 3.x, Java 17)
# Listens on port 8081 under the servlet context-path /marketplace.
#
# -----------------------------------------------------------------------------
# BUILD CONTEXT MUST BE THE AGGREGATOR DIRECTORY (kitchensink/)
# -----------------------------------------------------------------------------
# marketplace-service/pom.xml inherits from the aggregator parent POM at ../pom.xml, which
# lives ABOVE this module. The image must therefore be built with the context
# set to kitchensink/ and selected via -f:
#
#     # run from kitchensink/
#     docker build -f marketplace-service/Dockerfile -t marketplace-service:latest .
#
# Every COPY path below is relative to kitchensink/. The repository-root
# .github/workflows/ci.yml invokes the build exactly this way.
#
# Cross-domain boundary rule (AAP §0.7.2): this image bundles ONLY the marketplace-service
# module. It never copies sibling service code; all cross-domain communication
# happens at runtime over HTTP through the @Component client gateways.
# =============================================================================


# ---- Build stage ------------------------------------------------------------
# Maven 3.9 on the Eclipse Temurin JDK 17 — the exact image pinned in AAP §0.5.1.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Copy the aggregator parent POM and this module's POM first so the (expensive)
# dependency-resolution layer is cached and only re-runs when a POM changes.
# Invoking Maven with -f marketplace-service/pom.xml makes this module the reactor root, so
# the aggregator's <modules> list is ignored and sibling services are never
# required or built here (boundary rule).
COPY pom.xml ./pom.xml
COPY marketplace-service/pom.xml ./marketplace-service/pom.xml
RUN mvn -B -f marketplace-service/pom.xml -DskipTests dependency:go-offline

# Copy the module sources last (the most frequently changing layer) and build
# the repackaged executable Spring Boot fat jar. Tests are skipped here on
# purpose: the *IT integration tests need Testcontainers and run separately in
# CI, not inside the image build.
COPY marketplace-service/src ./marketplace-service/src
RUN mvn -B -f marketplace-service/pom.xml -DskipTests clean package


# ---- Runtime stage ----------------------------------------------------------
# Slim JRE-only Alpine image — the exact runtime image pinned in AAP §0.5.1. The
# heavyweight Maven/JDK build image is discarded; only this thin layer ships.
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Run as an unprivileged system user instead of root (defense-in-depth).
RUN addgroup -S spring && adduser -S spring -G spring

# Copy ONLY the executable fat jar from the build stage to a stable path,
# assigning ownership to the runtime user at copy time. The version-agnostic
# wildcard matches the repackaged marketplace-service-<version>.jar but NOT the
# *.jar.original thin jar emitted by the Spring Boot plugin.
COPY --chown=spring:spring --from=build /workspace/marketplace-service/target/marketplace-service-*.jar /app/app.jar

USER spring:spring

# Informational: the embedded Tomcat listens on 8081 (server.port).
EXPOSE 8081

# Liveness/readiness probe against the actuator health endpoint, served under
# the /marketplace context-path. wget is provided by the Alpine BusyBox userland.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8081/marketplace/actuator/health || exit 1

# Launch the self-executable Spring Boot jar. Exec form ensures the JVM runs as
# PID 1 and receives container stop signals directly for a clean shutdown.
# Datasource credentials and *.base-url values are NOT baked into the image;
# they come from application.properties and are overridden via environment
# variables in container/CI networking.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
