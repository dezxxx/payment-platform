plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

java {
    toolchain {
        languageVersion.set(
            JavaLanguageVersion.of(providers.gradleProperty("javaToolchainVersion").get().toInt())
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
}

// the serializer and Schema Registry come from one Confluent release
val confluentVersion = "8.3.2"

val transactionEvents = providers.gradleProperty("transactionEventsGroup").get() + ":" +
        providers.gradleProperty("transactionEventsArtifact").get() + ":" +
        providers.gradleProperty("transactionEventsVersion").get()

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // health and /actuator/prometheus over HTTP; the work itself is Kafka only
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("io.confluent:kafka-avro-serializer:$confluentVersion")
    implementation(transactionEvents)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.test {
    filter { includeTestsMatching("com.dezxxx.provider.unit.*") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.dezxxx.provider.integration.*") }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTest) }

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("fake-payment-provider.jar")
}
