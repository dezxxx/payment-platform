import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
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
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.test {
    filter { includeTestsMatching("com.dezxxx.transaction.unit.*") }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed integration tests."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.dezxxx.transaction.integration.*") }
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTest) }

val contract = layout.projectDirectory.file("openapi/transaction-service.yaml")
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
    // wipe the folder first: a renamed schema would otherwise leave its old class behind
    cleanupOutput.set(true)

    apiPackage.set("com.dezxxx.transaction.api")
    modelPackage.set("com.dezxxx.transaction.api.model")
    modelNameSuffix.set("Dto")
    invokerPackage.set("com.dezxxx.transaction.api")

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
    cleanupOutput.set(true)

    apiPackage.set("com.dezxxx.transaction.client.api")
    modelPackage.set("com.dezxxx.transaction.client.model")
    modelNameSuffix.set("Dto")
    invokerPackage.set("com.dezxxx.transaction.client")

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
        // the client models carry @DifferentWallets, so the validation package travels with them
        java.srcDir("src/main/java")
        java.include("com/dezxxx/transaction/client/**", "com/dezxxx/transaction/validation/**")
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
    archiveBaseName.set("transaction-service-client")
    from(sourceSets["client"].output)
}

tasks.named("assemble") { dependsOn(clientJar) }
