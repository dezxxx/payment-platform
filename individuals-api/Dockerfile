# Builds individuals-api on a JDK, runs it on a JRE.
# Context is the repo root (needs gradle/libs.versions.toml). person-client
# comes from Nexus - it must be up first (make up); anonymous read, no password.

# --- build ---
FROM eclipse-temurin:25-jdk AS build

# the build runs outside the compose network - Nexus via the host port
ARG NEXUS_PUBLIC_URL=http://host.docker.internal:8083/repository/maven-public/

WORKDIR /workspace

COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY individuals-api/ individuals-api/

WORKDIR /workspace/individuals-api

# no tests here (make test / make it); the cache keeps dependencies between builds
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
