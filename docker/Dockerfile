# Multi-stage build. Context is the repository root (see compose.yaml `build.context: ..`).

# ---------- build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Dependency resolution is copied and run before `src`, so editing a Java file does not
# invalidate the layer that downloads ~200 MB of Gradle dependencies.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src src
# Tests run in CI, not here: a container build that runs Testcontainers would need a Docker
# socket inside the build, which is a complication with no payoff.
RUN ./gradlew --no-daemon bootJar -x test

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime

# curl is used by the compose healthcheck; the JRE base image ships without it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# why a non-root user: a gateway terminates untrusted input from every tenant. If a
# deserialisation bug ever gives someone code execution, it should not be as root.
RUN useradd --system --create-home --uid 10001 gateway
USER gateway
WORKDIR /app

COPY --from=build --chown=gateway:gateway /workspace/build/libs/*.jar app.jar

# MaxRAMPercentage, not -Xmx: the JVM then tracks the container's cgroup limit, so changing the
# compose memory limit does not require also changing a hardcoded heap size.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
