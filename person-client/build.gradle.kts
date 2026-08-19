// person-client
//
// Publishable artifact that carries the person-service contract:
//   - models (DTO) generated from OpenAPI
//   - @HttpExchange client interfaces (Spring HTTP Service Clients)
//   - the shared error model
//
// No DTO is ever written by hand. person-service.yaml is the single source of
// truth; this module only turns it into a jar and pushes it to Nexus so that
// individuals-api (and later modules) consume it by Maven coordinates.

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.openapi.generator)
}

val contract = rootProject.layout.projectDirectory
    .file("person-service/openapi/person-service.yaml")

val generatedDir = layout.buildDirectory.dir("generated/openapi")

openApiValidate {
    inputSpec.set(contract.asFile.absolutePath)
    recommend.set(true)
}

openApiGenerate {
    generatorName.set("spring")
    library.set("spring-http-interface")

    inputSpec.set(contract.asFile.absolutePath)
    outputDir.set(generatedDir.map { it.asFile.absolutePath })

    apiPackage.set("com.dezxxx.person.client.api")
    modelPackage.set("com.dezxxx.person.client.model")
    invokerPackage.set("com.dezxxx.person.client")

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
            "useJakartaEe"      to "true",
            // Consumed by a WebFlux service - the client returns Mono, not the
            // raw type, so no call ever blocks the event loop.
            "reactive"          to "true",
            "openApiNullable"   to "false",
            "useSpringBoot3"    to "true",
            "serializationLibrary" to "jackson",
            "dateLibrary"       to "java8",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "useBeanValidation" to "true",
            "performBeanValidation" to "true",
            "unhandledException" to "true"
        )
    )
}

// Generation must always run before compilation, and must always be preceded
// by validation - a broken contract fails the build, not the runtime.
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
    api(libs.spring.web)
    // Generated signatures are Mono-typed, so Reactor is part of the API.
    api(libs.reactor.core)
    // DateTimeFormat on generated date-time properties lives in spring-context.
    api(libs.spring.context)
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)
    api(libs.jakarta.validation.api)
    compileOnly(libs.jakarta.annotation.api)
    api(libs.swagger.annotations)
}

// The Boot BOM is used only to align transitive versions - person-client is a
// plain library, not a Boot application.
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = providers.gradleProperty("personClientGroup").get()
            artifactId = providers.gradleProperty("personClientArtifact").get()
            version = providers.gradleProperty("personClientVersion").get()

            pom {
                name.set("person-client")
                description.set("Generated DTOs and HTTP client contracts for person-service")
            }
        }
    }
    repositories {
        maven {
            name = "nexus"
            val releases = providers.gradleProperty("nexusReleasesUrl").get()
            val snapshots = providers.gradleProperty("nexusSnapshotsUrl").get()
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases)
            isAllowInsecureProtocol =
                providers.gradleProperty("nexusAllowInsecureProtocol").get().toBoolean()
            val user = providers.environmentVariable("NEXUS_USERNAME").orNull
            val pass = providers.environmentVariable("NEXUS_PASSWORD").orNull
            if (user != null && pass != null) {
                credentials {
                    username = user
                    password = pass
                }
            }
        }
    }
}
