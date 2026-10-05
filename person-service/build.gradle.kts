import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    // imports the Spring Boot BOM: dependencies below take their versions from it
    id("io.spring.dependency-management") version "1.1.7"
    // 7.20+ is the first to know useSpringBoot4
    id("org.openapi.generator") version "7.25.0"
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
    // -parameters: Spring reads method parameter names at runtime
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
    // since Flyway 10 PostgreSQL support is a separate module
    implementation("org.flywaydb:flyway-database-postgresql")
    // brings Hibernate Envers (writes history) and adds repositories that read it
    implementation("org.springframework.data:spring-data-envers")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Gradle 9 no longer adds the JUnit launcher by itself
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val contract = layout.projectDirectory.file("openapi/person-service.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

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
    invokerPackage.set("com.dezxxx.person.api")

    // only interfaces and models: no pom.xml, README or sample application
    globalProperties.set(mapOf("apis" to "", "models" to "", "supportingFiles" to "ApiUtil.java"))

    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "delegatePattern" to "true",
            "useSpringBoot4" to "true",
            // @Nullable from JSpecify, not the one Spring 7 deprecated
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

// person-service-client for individuals-api: HTTP Service Clients, a separate artifact
val generatedClientDir = layout.buildDirectory.dir("generated/client")

val openApiGenerateClient = tasks.register<GenerateTask>("openApiGenerateClient") {
    dependsOn("openApiValidate")
    generatorName.set("spring")
    library.set("spring-http-interface")
    inputSpec.set(contract.asFile.absolutePath)
    outputDir.set(generatedClientDir)

    apiPackage.set("com.dezxxx.person.client.api")
    modelPackage.set("com.dezxxx.person.client.model")
    invokerPackage.set("com.dezxxx.person.client")

    globalProperties.set(mapOf("apis" to "", "models" to ""))

    configOptions.set(
        mapOf(
            // individuals-api is WebFlux: calls return Mono and never block
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
    // compiled apart from the service, so it never lands in person-service.jar
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
