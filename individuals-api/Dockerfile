# Packages a jar that was already built.
#
# Each module of this repository is an independent Gradle build - the folder
# above is a Git root, not a project - so nothing here can build a sibling.
# individuals-api needs person-client, which is resolved by coordinates from
# Nexus or the local Maven repository, and neither is reachable from inside a
# docker build.
#
# So the build happens first, on the machine:
#
#   cd person-client   && ./gradlew publishToMavenLocal   # or publish, to Nexus
#   cd ../individuals-api && ./gradlew bootJar
#   docker compose build individuals-api
#
# The build context is this module, and the only thing copied is the jar.

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

# Never run as root: a container escape should not land on a root shell.
RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app

COPY --chown=app:app build/libs/individuals-api.jar app.jar

# Documentation only - the published port is decided by docker-compose.yml.
EXPOSE 8081

# Exec form, so the JVM is PID 1 and receives SIGTERM directly. With the shell
# form the signal would go to /bin/sh and the JVM would be killed instead of
# shutting down cleanly.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
