# transaction-service-events comes from Nexus, so Nexus must be up and the events published

FROM eclipse-temurin:25-jdk AS build

# the build runs outside the compose network - Nexus via the host port
ARG NEXUS_PUBLIC_URL=http://host.docker.internal:8083/repository/maven-public/

WORKDIR /workspace

COPY . .

# own cache id: images built in parallel must not share one Gradle home lock
RUN --mount=type=cache,id=fake-payment-provider-gradle,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -PnexusPublicUrl=${NEXUS_PUBLIC_URL}

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app

COPY --from=build --chown=app:app /workspace/build/libs/fake-payment-provider.jar app.jar

EXPOSE 8086

# exec form: the JVM is PID 1 and gets SIGTERM for a clean shutdown
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
