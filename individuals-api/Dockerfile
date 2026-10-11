# context is the repo root; person-service-client comes from Nexus, so Nexus must be up

FROM eclipse-temurin:25-jdk AS build

# the build runs outside the compose network - Nexus via the host port
ARG NEXUS_PUBLIC_URL=http://host.docker.internal:8083/repository/maven-public/

WORKDIR /workspace

COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY individuals-api/ individuals-api/

WORKDIR /workspace/individuals-api

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -PnexusPublicUrl=${NEXUS_PUBLIC_URL}

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app

COPY --from=build --chown=app:app /workspace/individuals-api/build/libs/individuals-api.jar app.jar

EXPOSE 8081

# exec form: the JVM is PID 1 and gets SIGTERM for a clean shutdown
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
