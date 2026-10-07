plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
    jacoco
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
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

    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to ""
        )
    )

    configOptions.set(
        mapOf(
            "interfaceOnly"         to "true",
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
        // a task output, not a bare directory: every consumer then runs openApiGenerate first
        java.srcDir(tasks.named("openApiGenerate").map { generatedDir.get().dir("src/main/java") })
    }
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.webclient)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.web)

    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.reactor.core.micrometer)

    implementation(libs.springdoc.openapi.starter.webflux.ui)

    implementation(libs.swagger.annotations)
    compileOnly(libs.jakarta.annotation.api)

    implementation(
        "${providers.gradleProperty("personClientGroup").get()}:" +
            "${providers.gradleProperty("personClientArtifact").get()}:" +
            providers.gradleProperty("personClientVersion").get()
    )

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.keycloak)
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.database.postgresql)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

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

    // person-service's migration scripts, located by Gradle rather than guessed by the test
    systemProperty(
        "person.migrations.dir",
        layout.projectDirectory
            .dir("../person-service/src/main/resources/db/migration").asFile.absolutePath
    )

    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTest) }

/**
 * Generated code is left out of the coverage number.
 *
 * `com.dezxxx.individuals.api` is written by the OpenAPI generator - models
 * with getters, setters and equals. Counting them would move the percentage a
 * long way while saying nothing about whether anything we wrote is tested, and
 * nobody would ever write a test for a generated setter.
 */
val coveredClasses = { classes: FileCollection ->
    files(classes.files.map { fileTree(it) { exclude("com/dezxxx/individuals/api/**") } })
}

tasks.named<JacocoReport>("jacocoTestReport") {
    // both suites: the gateways are exercised only by the integration tests
    dependsOn(tasks.test, integrationTest)
    executionData(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    classDirectories.setFrom(coveredClasses(classDirectories))

    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

/**
 * Acceptance criterion 11: the main scenario, at 80% or better, on the key
 * services. The rule names that package and no other on purpose - a repository
 * wide average would hide a bare `UserService` behind a well covered
 * `config`, which is the opposite of what the criterion is for.
 *
 * Measured at 100% when the rule was added, so the threshold is a floor that
 * catches a regression, not a target still being chased.
 */
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    executionData(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    classDirectories.setFrom(coveredClasses(classDirectories))

    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("com.dezxxx.individuals.service")
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn(tasks.named("jacocoTestCoverageVerification")) }

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("individuals-api.jar")
}

tasks.processResources {
    from(contract) { into("static/openapi") }
}
