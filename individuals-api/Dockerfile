# Two stages: build the jar, then ship only the jar.
#
# The build context is the repository root, not this directory - Gradle needs
# settings.gradle.kts, the wrapper and the version catalog, and it also has to
# build person-client first. docker-compose.yml sets the context accordingly.
#
# person-client is published into the builder's own local Maven repository and
# resolved from there, because inside the build there is no Nexus to reach.
# That mirrors what the wrapper does on a developer machine and keeps the rule
# intact: modules see each other as artifacts, never as sibling directories.

FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY . .

# The wrapper loses its executable bit on a Windows checkout.
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon --no-configuration-cache \
        :person-client:publishToMavenLocal \
        :individuals-api:bootJar

# ---------------------------------------------------------------------------

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

# Never run as root: a container escape should not land on a root shell.
RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app

COPY --from=build --chown=app:app /workspace/individuals-api/build/libs/*.jar app.jar

# Documentation only - the published port is decided by docker-compose.yml.
EXPOSE 8081

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. With the shell
# form the signal would go to /bin/sh and the JVM would be killed instead of
# shutting down cleanly.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
