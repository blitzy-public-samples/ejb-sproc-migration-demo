# =============================================================================
# orders-service — Multi-stage container image (Spring Boot 3.x, Java 17)
# =============================================================================
# Bounded context: orders, shopping cart, shipping, and customer discounts.
# Runtime: an executable Spring Boot fat JAR listening on port 8082 under the
# servlet context-path /orders.
#
# -----------------------------------------------------------------------------
# CRITICAL — BUILD CONTEXT MUST BE THE AGGREGATOR DIRECTORY (kitchensink/)
# -----------------------------------------------------------------------------
# orders-service/pom.xml inherits from the aggregator parent POM at ../pom.xml.
# A build whose context is orders-service/ cannot read that parent (it lives
# ABOVE the context root), so Maven would fail to resolve <parent>. This
# Dockerfile is therefore authored to be built with the context set to
# kitchensink/ and selected via -f:
#
#     # run from kitchensink/
#     docker build -f orders-service/Dockerfile -t orders-service:latest .
#
# Every COPY path below is relative to kitchensink/. The repository-root
# .github/workflows/ci.yml invokes the build exactly this way.
#
# Cross-domain boundary rule (AAP §0.7.2): this image bundles ONLY the
# orders-service module. It never copies the sibling marketplace-service or
# users-service code; all cross-domain communication happens at runtime over
# HTTP through the @Component client gateways (MarketplaceClient, UsersClient).
# =============================================================================


# ---- Build stage ------------------------------------------------------------
# Maven 3.9 on the Eclipse Temurin JDK 17 — the exact image pinned in AAP
# §0.5.1. Java 17 is the Spring Boot 3.x baseline.
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace

# Copy the aggregator parent POM first so the module can resolve its <parent>
# (groupId org.jboss.as.quickstarts, artifactId kitchensink, version 8.0.0.GA,
# relativePath ../pom.xml). The aggregator's own parent is
# spring-boot-starter-parent, which Maven downloads from Maven Central.
COPY pom.xml ./pom.xml

# Copy ONLY this module (its POM + sources). Using the module-scoped path keeps
# the sibling service directories out of the build and honors the boundary rule
# (orders-service declares no inter-module compile dependency).
COPY orders-service ./orders-service

# Build just this module by pointing Maven at the module POM. This deliberately
# bypasses the aggregator reactor (which lists marketplace-service / users-service
# in <modules> and would otherwise require those sibling directories to be
# present). Maven reads the parent at /workspace/pom.xml for dependency
# management and pulls the Spring Boot grandparent + starters from Maven Central.
#
# -DskipTests keeps the image build hermetic: the integration tests (*IT) need a
# PostgreSQL/Testcontainers runtime that is not available during `docker build`;
# they run separately in CI via `mvn verify`.
RUN mvn -B -f orders-service/pom.xml clean package -DskipTests


# ---- Runtime stage ----------------------------------------------------------
# Slim JRE-only Alpine image — the exact runtime image pinned in AAP §0.5.1.
# The heavyweight Maven/JDK build image is discarded; only this thin layer ships.
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Best practice: run as an unprivileged user rather than root. BusyBox
# addgroup/adduser ship with Alpine. Port 8082 is non-privileged (>1024), so the
# spring user can bind it without elevated capabilities.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring

# Copy the repackaged executable fat JAR from the build stage. The artifact is
# orders-service-<version>.jar (version 8.0.0.GA inherited from the aggregator).
# The glob tolerates a version bump; it matches the Spring Boot fat JAR but NOT
# the orders-service-*.jar.original thin JAR, so exactly the runnable artifact
# is copied to a stable path.
COPY --from=build /workspace/orders-service/target/orders-service-*.jar /app/app.jar

# Informational: the embedded Tomcat listens on 8082 (server.port).
EXPOSE 8082

# Liveness/readiness probe against the actuator health endpoint. The actuator
# starter exposes /actuator/health, served under the /orders context-path at
# /orders/actuator/health. wget is provided by Alpine BusyBox.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=10 \
    CMD wget -qO- http://localhost:8082/orders/actuator/health || exit 1

# Launch the service. Datasource credentials and the marketplace/users *.base-url
# values are NOT baked into the image; they come from application.properties and
# are overridden via environment variables in container/CI networking.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
