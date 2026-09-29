# Builds individuals-api inside the image, then runs it on a bare JRE.
#
# Build context is the repository root (see docker-compose.yml): the module
# needs the shared version catalog in gradle/, which lives outside its folder.
#
# person-client is not built here - it is downloaded from Nexus by its
# coordinates, so Nexus must be running and hold it before this build starts
# (make up takes care of both). Nexus allows anonymous reads, so no password
# enters the image.

# --- build ---
FROM eclipse-temurin:25-jdk AS build

# The build runs outside the compose network, so Nexus is reached through the
# host's published port.
ARG NEXUS_PUBLIC_URL=http://host.docker.internal:8083/repository/maven-public/

WORKDIR /workspace

COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY individuals-api/ individuals-api/

WORKDIR /workspace/individuals-api

# Tests are not run here: make test and make it do that, the image only packages.
# The cache mount keeps downloaded dependencies between builds.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -PnexusPublicUrl=${NEXUS_PUBLIC_URL}

# --- runtime ---
FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

# never run as root
RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app

COPY --from=build --chown=app:app /workspace/individuals-api/build/libs/individuals-api.jar app.jar

# documentation only - the published port is set in docker-compose.yml
EXPOSE 8081

# exec form: the JVM is PID 1 and gets SIGTERM for a clean shutdown
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
