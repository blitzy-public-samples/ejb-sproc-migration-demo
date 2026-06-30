# syntax=docker/dockerfile:1
# =============================================================================
# marketplace-service - multi-stage container image
# -----------------------------------------------------------------------------
# Builds and runs the marketplace-service Spring Boot 3.x module (product
# catalog, pricing, and vendor selection). One of three independently
# deployable services that replace the legacy `kitchensink` JBoss EAP WAR.
#
#   Runtime identity : server.port=8081, server.servlet.context-path=/marketplace
#   Health probe     : GET /marketplace/actuator/health  ->  {"status":"UP"}
#
# Design notes:
#   * The module's Maven parent is `spring-boot-starter-parent`, resolved from
#     Maven Central via an empty <relativePath/> - NOT the sibling kitchensink
#     aggregator POM. The module therefore builds STANDALONE: only its own
#     pom.xml + src/ need to be present in the build context (no parent or
#     aggregator POM is required inside the image).
#   * `spring-boot-maven-plugin` repackages the module into a single executable
#     "fat" JAR (target/marketplace-service-<version>.jar). A version-agnostic
#     wildcard copy (target/*.jar) keeps this Dockerfile independent of the
#     artifact version.
#   * Integration tests (*IT) are intentionally skipped here (-DskipTests):
#     they rely on Testcontainers, which needs a Docker daemon that is not
#     available inside an image build. Test execution lives in CI instead
#     (mvn -B clean verify on a Docker-enabled runner).
# =============================================================================

# ---- Stage 1: build ---------------------------------------------------------
# Full JDK 17 + Maven toolchain, used only to compile and repackage the JAR.
# This stage is discarded from the final image; none of its tooling ships.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the POM first and resolve dependencies in a separate, cacheable layer.
# As long as pom.xml is unchanged, Docker reuses this layer and skips the
# (slow) dependency download on subsequent source-only rebuilds.
COPY pom.xml .
RUN mvn -B -e dependency:go-offline

# Copy the sources and produce the executable fat JAR. Tests are skipped here
# (see header) - they run in CI where a Docker daemon is available.
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Stage 2: runtime -------------------------------------------------------
# Minimal JRE-only Alpine image (no Maven, no JDK, no build tooling) for a
# small, lean production runtime footprint.
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as an unprivileged user instead of root (defence-in-depth best practice).
# Created before the copy so the runtime process never executes as root.
RUN addgroup -S spring && adduser -S spring -G spring

# Copy only the repackaged executable JAR from the build stage; all build
# tooling and intermediate layers are left behind. The `*.jar` glob matches
# the single Spring Boot fat JAR - the plugin renames the original thin jar to
# `*.jar.original`, which does not match `*.jar`.
COPY --from=build /build/target/*.jar app.jar

# Drop privileges for the running container.
USER spring

# Documents the port the service listens on (matches server.port=8081).
# Publish it at runtime with `docker run -p 8081:8081 ...`.
EXPOSE 8081

# Exec (JSON-array) form so the JVM runs as PID 1 and receives stop signals
# (SIGTERM/SIGINT) directly, enabling graceful shutdown.
ENTRYPOINT ["java","-jar","app.jar"]
