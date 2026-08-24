// individuals-api
//
// External entry layer of the platform: registration, login, refresh-token and
// current-user. It owns no domain data - it orchestrates person-service and
// Keycloak.
//
// Contract-first: server API interfaces are generated from
// openapi/individuals-api.yaml and implemented by controllers. The
// person-service contract arrives as a published artifact, never as
// project(":person-client").

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
}

val contract = layout.projectDirectory.file("openapi/individuals-api.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

openApiValidate {
    inputSpec.set(contract.asFile.absolutePath)
    recommend.set(true)
}

openApiGenerate {
    generatorName.set("spring")
    library.set("spring-boot")

    inputSpec.set(contract.asFile.absolutePath)
    outputDir.set(generatedDir.map { it.asFile.absolutePath })

    apiPackage.set("com.dezxxx.individuals.api")
    modelPackage.set("com.dezxxx.individuals.api.model")
    invokerPackage.set("com.dezxxx.individuals.api")

    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)

    // Generate APIs and models only. Supporting files would add a pom.xml,
    // a README and a build wrapper - none of which belong in a Gradle build,
    // and the spring-http-interface library ships no pom template at all.
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to ""
        )
    )

    configOptions.set(
        mapOf(
            // Generate interfaces only - controllers implement them, so the
            // contract cannot drift away from the code.
            "interfaceOnly"         to "true",
            // WebFlux: every generated operation returns Mono<ResponseEntity<..>>.
            "reactive"              to "true",
            "useSpringBoot3"        to "true",
            "useJakartaEe"          to "true",
            "openApiNullable"       to "false",
            "useTags"               to "true",
            "skipDefaultInterface"  to "true",
            "documentationProvider" to "none",
            "annotationLibrary"     to "none",
            "serializationLibrary"  to "jackson",
            "dateLibrary"           to "java8",
            "useBeanValidation"     to "true",
            "performBeanValidation" to "true",
            "unhandledException"    to "true"
        )
    )
}

tasks.named("openApiGenerate") { dependsOn("openApiValidate") }

sourceSets {
    main {
        // Wired in as a task output rather than a bare directory, so every
        // consumer (compileJava, sourcesJar, ...) inherits the dependency on
        // openApiGenerate instead of each needing its own dependsOn.
        java.srcDir(tasks.named("openApiGenerate").map { generatedDir.get().dir("src/main/java") })
    }
}

dependencies {
    // --- Web / security / validation ---
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.web)

    // --- Observability: actuator + Prometheus metrics + OTLP traces ---
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)

    // --- Swagger UI served over the contract-first spec ---
    implementation(libs.springdoc.openapi.starter.webflux.ui)

    // --- Generated server interfaces need these at compile time ---
    implementation(libs.swagger.annotations)
    compileOnly(libs.jakarta.annotation.api)

    // --- person-service contract, consumed from Nexus by coordinates ---
    implementation(
        "${providers.gradleProperty("personClientGroup").get()}:" +
            "${providers.gradleProperty("personClientArtifact").get()}:" +
            providers.gradleProperty("personClientVersion").get()
    )

    // --- Test ---
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.keycloak)
    // Gradle 9 no longer puts the JUnit Platform launcher on the test runtime
    // classpath by itself, and without it the test JVM cannot start at all.
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Unit tests and integration tests are separated by package, so each can be
// run on its own in CI.
tasks.test {
    useJUnitPlatform()
    filter { includeTestsMatching("com.dezxxx.individuals.unit.*") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.dezxxx.individuals.integration.*") }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTest) }

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("individuals-api.jar")
}
