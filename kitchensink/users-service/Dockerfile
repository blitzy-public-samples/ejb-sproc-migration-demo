# =============================================================================
#  users-service — Multi-Stage Container Image (Spring Boot 3.x, Java 17)
# =============================================================================
#
#  Builds the Net32 "users-service" microservice — the identity/member bounded
#  context that owns the `member` table and the nightly tier-recalculation job —
#  into a slim, JRE-only runtime image (AAP §0.4.1 infra table, §0.5.1 images).
#
#  ---------------------------------------------------------------------------
#  BUILD CONTEXT (IMPORTANT — read before building)
#  ---------------------------------------------------------------------------
#  users-service is a child module of the Maven multi-module aggregator rooted
#  at `kitchensink/pom.xml`. The child inherits Spring Boot dependency
#  management and the Java 17 baseline from that aggregator via
#  <relativePath>../pom.xml</relativePath>. Therefore this image MUST be built
#  with the build context set to the aggregator root (the `kitchensink/`
#  directory) so that BOTH the aggregator parent POM and this module's sources
#  are reachable by the COPY instructions below:
#
#      cd kitchensink
#      docker build -f users-service/Dockerfile -t users-service:latest .
#
#  Why this keeps the module independent of its siblings (cross-domain boundary
#  rule, AAP §0.7.2): the build invokes Maven with `-f users-service/pom.xml`,
#  which makes users-service the REACTOR ROOT. When a child POM is the reactor
#  root, Maven consults the aggregator ONLY for parent inheritance (its
#  dependencyManagement / properties) and DOES NOT process the aggregator's
#  <modules> list. As a result the marketplace-service and orders-service
#  modules are never required, downloaded, or built here — this image is built
#  purely from users-service sources plus the shared parent POM. Do NOT add a
#  COPY of any sibling service: that would break build-context independence and
#  violate the boundary rule.
# =============================================================================


# -----------------------------------------------------------------------------
# Stage 1 — build: compile and repackage the executable Spring Boot fat JAR.
# Uses the full Maven + JDK 17 toolchain image. Nothing from this stage (Maven,
# the local ~/.m2 cache, or the source tree) survives into the final image.
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

# All build work happens under /workspace. The relative layout below mirrors the
# aggregator root so that this module's <relativePath>../pom.xml</relativePath>
# resolves to /workspace/pom.xml.
WORKDIR /workspace

# 1) Copy the aggregator parent POM FIRST. This is the only file this module
#    needs from outside its own directory — it supplies Spring Boot dependency
#    management and the Java 17 baseline. Its <modules> list is intentionally
#    ignored because Maven is invoked with `-f users-service/pom.xml` below.
COPY pom.xml ./pom.xml

# 2) Copy the module POM next (before the sources) so the expensive dependency
#    resolution layer can be cached and only re-run when a POM actually changes.
COPY users-service/pom.xml ./users-service/pom.xml

# 3) Warm the local Maven repository with this module's dependencies and
#    plugins. Cached as its own layer and re-executed only when a POM above
#    changes — not when application sources change. Network access is required
#    here (this step is deliberately NOT run with -o), so the full Spring Boot
#    3.5.x dependency tree is fetched from Maven Central once and reused.
RUN mvn -q -B -f users-service/pom.xml dependency:go-offline

# 4) Copy the application sources last (the layer that changes most often), then
#    build the repackaged, self-executable Spring Boot JAR. Tests are skipped
#    here on purpose: the integration tests use Testcontainers and run in CI,
#    not inside the image build.
COPY users-service/src ./users-service/src
RUN mvn -q -B -f users-service/pom.xml clean package -DskipTests


# -----------------------------------------------------------------------------
# Stage 2 — runtime: minimal JRE-only image. No Maven, no build cache, and no
# source tree — only the packaged application JAR, keeping the final image slim.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

# Run as an unprivileged system user instead of root (defense-in-depth).
RUN addgroup -S spring && adduser -S -G spring spring

WORKDIR /app

# Copy ONLY the executable fat JAR from the build stage, under a stable name so
# the ENTRYPOINT is independent of the Maven finalName/version. The wildcard
# matches exactly the repackaged `users-service-<version>.jar`; the
# `*.jar.original` emitted by the Spring Boot plugin is NOT matched. Ownership is
# assigned to the unprivileged runtime user at copy time.
COPY --chown=spring:spring --from=build /workspace/users-service/target/*.jar /app/app.jar

USER spring

# users-service listens on 8083 (application.properties: server.port=8083).
EXPOSE 8083

# Container health probe against the Spring Boot Actuator health endpoint. The
# URL includes the `/users` context-path (server.servlet.context-path=/users)
# and uses BusyBox `wget` because the alpine JRE image ships no `curl`. A
# generous start-period gives the JVM and Spring context time to come up before
# failed probes are counted against the container.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O - http://localhost:8083/users/actuator/health || exit 1

# Launch the self-executable Spring Boot JAR. Exec form ensures the JVM runs as
# PID 1 and receives container stop signals directly for a clean shutdown.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
