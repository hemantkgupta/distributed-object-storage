# syntax=docker/dockerfile:1.6
#
# Multi-stage build for the obj-gateway-app Spring Boot executable jar.
# Produces a small runtime image that works on linux/amd64 and linux/arm64
# (eclipse-temurin and gradle images on Docker Hub publish manifests for both).

# ---------------------------------------------------------------------------
# Stage 1 — build the bootJar with Gradle.
# ---------------------------------------------------------------------------
FROM gradle:8.7-jdk17 AS builder

WORKDIR /workspace

# Copy the whole project. Only the root and obj-gateway-app have their own
# build.gradle; every other module is configured from the root build.gradle.
COPY . .

# Build the executable jar. Tests are skipped — the Dockerfile is a packaging
# step, not a CI gate.
RUN gradle :obj-gateway-app:bootJar -x test --no-daemon

# ---------------------------------------------------------------------------
# Stage 2 — minimal JRE runtime.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Storage shards land under ${user.dir}/data/storage-nodes/ — bind-mount the
# parent directory as a volume so shards survive container restarts and are
# inspectable from the host.
RUN mkdir -p /app/data/storage-nodes

COPY --from=builder /workspace/obj-gateway-app/build/libs/obj-gateway-app-0.1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

# Use exec form so the JVM is PID 1 and receives SIGTERM cleanly.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
