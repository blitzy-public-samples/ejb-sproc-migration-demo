# syntax=docker/dockerfile:1
# =============================================================================
# orders-service - multi-stage container image
# -----------------------------------------------------------------------------
# Builds and runs the orders-service Spring Boot 3.x module (shopping cart,
# order orchestration, discounting, and shipping). One of three independently
# deployable services that replace the legacy `kitchensink` JBoss EAP WAR.
#
#   Runtime identity : server.port=8082, server.servlet.context-path=/orders
#   Health probe     : GET /orders/actuator/health  ->  {"status":"UP"}
#   Role             : CONSUMER - calls marketplace-service (price/quote) and
#                      users-service (member tier) over HTTP via thin
#                      RestTemplate clients, and exposes an internal member-spend
#                      endpoint (GET /orders/internal/members/{id}/spend) that
#                      users-service consumes.
#
# Design notes:
#   * The module's Maven parent is `spring-boot-starter-parent`, resolved from
#     Maven Central via an empty <relativePath/> - NOT the sibling kitchensink
#     aggregator POM. The module therefore builds STANDALONE: only its own
#     pom.xml + src/ need to be present in the build context (no parent or
#     aggregator POM is required inside the image). orders-service has NO
#     compile-scope dependency on any sibling module (cross-domain boundary
#     rule), so no sibling sources are copied.
#   * `spring-boot-maven-plugin` repackages the module into a single executable
#     "fat" JAR (target/orders-service-<version>.jar, e.g.
#     orders-service-8.0.0.GA.jar). The version-agnostic wildcard copy
#     (orders-service-*.jar) keeps this Dockerfile independent of the artifact
#     version - the plugin renames the thin jar to `*.jar.original`, which does
#     not match the glob, so exactly one JAR is selected.
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

# Copy the POM first and pre-fetch dependencies in a separate, cacheable layer.
# As long as pom.xml is unchanged, Docker reuses this layer and skips the (slow)
# dependency download on subsequent source-only rebuilds. Kept deliberately
# resilient ("|| true"): dependency:go-offline cannot always resolve every
# plugin/profile artifact up front, and the authoritative package step below
# runs with full network access and fetches anything still missing - so this
# optimization never blocks the build.
COPY pom.xml .
RUN mvn -B -e -DskipTests dependency:go-offline || true

# Copy the sources and produce the executable fat JAR. Tests are skipped here
# (see header) - they run in CI where a Docker daemon is available.
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Stage 2: runtime -------------------------------------------------------
# Minimal JRE-only Alpine image (no Maven, no JDK, no build tooling) for a
# small, lean production runtime footprint.
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as a dedicated unprivileged user instead of root (defense-in-depth;
# trivially supported by the Alpine BusyBox addgroup/adduser). Created before
# the copy so the runtime process never executes as root.
RUN addgroup -S spring && adduser -S spring -G spring

# Copy only the repackaged executable JAR from the build stage and normalize its
# name to a stable app.jar; all build tooling and intermediate layers are left
# behind. The `orders-service-*.jar` glob tolerates the versioned artifact name
# (orders-service-8.0.0.GA.jar), so this image needs no edit when the module
# version changes.
COPY --from=build /build/target/orders-service-*.jar app.jar

# Drop privileges for the running container.
USER spring

# Documents the port the service listens on (matches server.port=8082 in
# application.properties). Publish it at runtime with `docker run -p 8082:8082`.
EXPOSE 8082

# Exec (JSON-array) form so the JVM runs as PID 1 and receives stop signals
# (SIGTERM/SIGINT) directly, enabling graceful container shutdown.
ENTRYPOINT ["java","-jar","app.jar"]

# -----------------------------------------------------------------------------
# Runtime configuration (supplied at "docker run", never baked into the image):
#   -e DB_USERNAME=<user>              datasource user (spring.datasource.username)
#   -e DB_PASSWORD=<pass>              datasource pass (spring.datasource.password)
#   -e SPRING_DATASOURCE_URL=...       override the JDBC URL; the default
#                                      jdbc:postgresql://localhost:5432/kitchensink
#                                      resolves "localhost" to the *container*, so a
#                                      reachable database host must be supplied, e.g.
#                                      jdbc:postgresql://db:5432/kitchensink
#   -e SERVICES_MARKETPLACE_BASE_URL=. marketplace-service base URL used by
#                                      MarketplaceClient (Contract 1 price/quote);
#                                      default http://localhost:8081/marketplace
#   -e SERVICES_USERS_BASE_URL=...     users-service base URL used by UsersClient
#                                      (Contract 2 member tier);
#                                      default http://localhost:8083/users
#
# Example:
#   docker run -p 8082:8082 \
#     -e DB_USERNAME=kitchensink -e DB_PASSWORD=changeme \
#     -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/kitchensink \
#     -e SERVICES_MARKETPLACE_BASE_URL=http://marketplace:8081/marketplace \
#     -e SERVICES_USERS_BASE_URL=http://users:8083/users \
#     orders-service
# -----------------------------------------------------------------------------
