FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY . .

# own cache id: images built in parallel must not share one Gradle home lock
RUN --mount=type=cache,id=person-service-gradle,target=/root/.gradle \
    ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime

WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin app
USER app

COPY --from=build --chown=app:app /workspace/build/libs/person-service.jar app.jar

EXPOSE 8082

# exec form: the JVM is PID 1 and gets SIGTERM for a clean shutdown
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
