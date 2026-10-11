import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.25.0"
    jacoco
    `maven-publish`
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
    // -parameters: @Query binds :email by the method parameter name
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.data:spring-data-envers")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.test {
    filter { includeTestsMatching("com.dezxxx.person.unit.*") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.dezxxx.person.integration.*") }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTest) }

// generated code and the bootstrap class say nothing about what we tested
val coveredClasses = { classes: FileCollection ->
    files(classes.files.map {
        fileTree(it) {
            exclude("com/dezxxx/person/api/**", "com/dezxxx/person/PersonServiceApplication.class")
        }
    })
}

tasks.named<JacocoReport>("jacocoTestReport") {
    // both suites: the repositories, Envers and the controller are exercised only by the integration tests
    dependsOn(tasks.test, integrationTest)
    executionData(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    classDirectories.setFrom(coveredClasses(classDirectories))
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    executionData(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    classDirectories.setFrom(coveredClasses(classDirectories))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check { dependsOn(tasks.named("jacocoTestCoverageVerification")) }

val contract = layout.projectDirectory.file("openapi/person-service.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

tasks.processResources {
    from(contract) { into("static/openapi") }
}

openApiValidate {
    inputSpec.set(contract.asFile.absolutePath)
    recommend.set(true)
}

tasks.named("check") { dependsOn("openApiValidate") }

openApiGenerate {
    generatorName.set("spring")
    inputSpec.set(contract.asFile.absolutePath)
    outputDir.set(generatedDir)

    apiPackage.set("com.dezxxx.person.api")
    modelPackage.set("com.dezxxx.person.api.model")
    modelNameSuffix.set("Dto")
    invokerPackage.set("com.dezxxx.person.api")

    globalProperties.set(mapOf("apis" to "", "models" to "", "supportingFiles" to "ApiUtil.java"))

    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "delegatePattern" to "true",
            "useSpringBoot4" to "true",
            "useJspecify" to "true",
            "useBeanValidation" to "true",
            "useTags" to "true",
            "openApiNullable" to "false",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "dateLibrary" to "java8"
        )
    )
}

tasks.named("openApiGenerate") { dependsOn("openApiValidate") }

val generatedClientDir = layout.buildDirectory.dir("generated/client")

val openApiGenerateClient = tasks.register<GenerateTask>("openApiGenerateClient") {
    dependsOn("openApiValidate")
    generatorName.set("spring")
    library.set("spring-http-interface")
    inputSpec.set(contract.asFile.absolutePath)
    outputDir.set(generatedClientDir)

    apiPackage.set("com.dezxxx.person.client.api")
    modelPackage.set("com.dezxxx.person.client.model")
    modelNameSuffix.set("Dto")
    invokerPackage.set("com.dezxxx.person.client")

    globalProperties.set(mapOf("apis" to "", "models" to ""))

    configOptions.set(
        mapOf(
            "reactive" to "true",
            "useSpringBoot4" to "true",
            "useJspecify" to "true",
            "useBeanValidation" to "true",
            "useTags" to "true",
            "openApiNullable" to "false",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "dateLibrary" to "java8"
        )
    )
}

sourceSets {
    main {
        // as a task output: compileJava then runs openApiGenerate first by itself
        java.srcDir(tasks.named("openApiGenerate").map { generatedDir.get().dir("src/main/java") })
    }
    create("client") {
        java.srcDir(openApiGenerateClient.map { generatedClientDir.get().dir("src/main/java") })
    }
}

dependencies {
    "clientImplementation"("org.springframework:spring-web")
    "clientImplementation"("org.springframework:spring-context")
    "clientImplementation"("io.projectreactor:reactor-core")
    "clientImplementation"("jakarta.validation:jakarta.validation-api")
    "clientImplementation"("com.fasterxml.jackson.core:jackson-annotations")
    "clientCompileOnly"("jakarta.annotation:jakarta.annotation-api")
}

val clientJar = tasks.register<Jar>("clientJar") {
    archiveBaseName.set("person-service-client")
    from(sourceSets["client"].output)
}

tasks.named("assemble") { dependsOn(clientJar) }

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("person-service.jar")
}

// io.spring.dependency-management hooks every pom with objects the configuration cache cannot store
tasks.withType<GenerateMavenPom>().configureEach {
    notCompatibleWithConfigurationCache("io.spring.dependency-management edits every generated pom")
}

// the client's own dependencies, with the versions Spring Boot picked, written into its pom
val clientDependencies = configurations["clientRuntimeClasspath"].incoming.resolutionResult.rootComponent.map { root ->
    root.dependencies
        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
        .mapNotNull { it.selected.moduleVersion }
        .map { Triple(it.group, it.name, it.version) }
}

publishing {
    publications {
        create<MavenPublication>("client") {
            val dependencyList = clientDependencies
            artifact(clientJar)
            groupId = project.group.toString()
            artifactId = "person-service-client"
            version = project.version.toString()
            pom {
                name.set("person-service-client")
                description.set("Generated DTOs and HTTP service client of person-service")
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    dependencyList.get().forEach { (group, name, version) ->
                        dependencies.appendNode("dependency").apply {
                            appendNode("groupId", group)
                            appendNode("artifactId", name)
                            appendNode("version", version)
                            appendNode("scope", "compile")
                        }
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "nexus"
            val releases = providers.gradleProperty("nexusReleasesUrl").get()
            val snapshots = providers.gradleProperty("nexusSnapshotsUrl").get()
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases)
            isAllowInsecureProtocol = providers.gradleProperty("nexusAllowInsecureProtocol").get().toBoolean()
            val user = providers.environmentVariable("NEXUS_USERNAME").orNull
            val pass = providers.environmentVariable("NEXUS_PASSWORD").orNull
            if (user != null && pass != null) {
                credentials {
                    username = user
                    password = pass
                }
                // Nexus answers an anonymous request with 403, and Gradle sends credentials only after a 401
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
}
