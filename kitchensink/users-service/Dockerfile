# =============================================================================
# users-service - multi-stage Docker build
# -----------------------------------------------------------------------------
# Containerizes the users-service Spring Boot 3.x microservice (bounded context:
# member registration + loyalty-tier management). The service listens on port
# 8083 under context-path /users; its health probe is
#     GET /users/actuator/health  ->  {"status":"UP"}
#
# This is one of three sibling service images produced by decomposing the legacy
# kitchensink JBoss EAP monolith into independently deployable Spring Boot JARs.
#
# Build context: the kitchensink/users-service/ directory, e.g.
#     docker build -t users-service kitchensink/users-service/
# The COPY instructions below therefore reference pom.xml and src relative to
# that folder. The module's Maven parent is spring-boot-starter-parent declared
# with <relativePath/>, so the module builds standalone inside the image - Maven
# resolves the parent and every dependency from Maven Central; no sibling modules
# or local aggregator POM are required in the build context.
# =============================================================================

# ---- Stage 1: build (Maven + JDK 17) ----------------------------------------
# Compiles the sources and repackages an executable Spring Boot fat JAR.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the POM first so dependency resolution is cached in its own layer,
# independent of source changes: while pom.xml is unchanged, Docker reuses the
# cached dependency layer and only re-runs the package step when sources change.
COPY pom.xml .

# Pre-fetch dependencies into a cached layer (layer-caching optimization).
# Kept deliberately resilient ("|| true"): dependency:go-offline cannot always
# resolve every plugin/profile artifact up front, and the authoritative package
# step below runs with full network access and fetches anything still missing.
# This optimization therefore never blocks the build.
RUN mvn -B -e -DskipTests dependency:go-offline || true

# Copy the application sources.
COPY src ./src

# Build the executable fat JAR. Tests are intentionally skipped here: the
# Testcontainers integration tests (*IT) require a Docker daemon, which is NOT
# available inside an image build. Per the migration plan, test execution lives
# in CI ("mvn -B clean verify" on a Docker-enabled runner), never in this image.
RUN mvn -B clean package -DskipTests

# ---- Stage 2: runtime (slim JRE 17) -----------------------------------------
# Ships only the JRE plus the repackaged JAR for a small, fast-starting image.
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Run as a dedicated non-root user (defense-in-depth; trivially supported by the
# Alpine BusyBox addgroup/adduser). The JAR is copied world-readable, so the
# unprivileged user can execute "java -jar" without further ownership changes.
RUN addgroup -S app && adduser -S app -G app

# Copy the repackaged executable JAR from the build stage and normalize its name
# to a stable app.jar. The wildcard tolerates the versioned artifact name
# (users-service-8.0.0.GA.jar), so this image needs no edit when the module
# version changes.
COPY --from=build /build/target/*.jar app.jar

# Documented service port (matches server.port=8083 in application.properties).
EXPOSE 8083

USER app

# Exec form (JSON array) so the JVM is PID 1 and receives SIGTERM directly for
# graceful container shutdown.
ENTRYPOINT ["java","-jar","app.jar"]

# -----------------------------------------------------------------------------
# Runtime configuration (supplied at "docker run", never baked into the image):
#   -e DB_USERNAME=<user>            datasource user  (spring.datasource.username)
#   -e DB_PASSWORD=<pass>            datasource pass  (spring.datasource.password)
#   -e SPRING_DATASOURCE_URL=...     override the JDBC URL; the default
#                                    jdbc:postgresql://localhost:5432/kitchensink
#                                    resolves "localhost" to the *container*, so a
#                                    reachable database host must be supplied, e.g.
#                                    jdbc:postgresql://db:5432/kitchensink
#   -e SERVICES_ORDERS_BASE_URL=...  orders-service base URL used by the nightly
#                                    tier recalculation (Contract 3 member-spend
#                                    calls); default http://localhost:8082/orders
#
# Example:
#   docker run -p 8083:8083 \
#     -e DB_USERNAME=kitchensink -e DB_PASSWORD=changeme \
#     -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/kitchensink \
#     users-service
# -----------------------------------------------------------------------------
