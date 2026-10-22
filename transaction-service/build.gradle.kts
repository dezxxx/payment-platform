import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.25.0"
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
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
}

// its BOM 5.5.3 points at a parent pom that was never published, so every module names the version
val shardingsphereVersion = "5.5.3"

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // no Flyway starter: Spring Boot would migrate the ShardingSphere DataSource, each shard is migrated on its own
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    runtimeOnly("org.apache.shardingsphere:shardingsphere-jdbc:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-sharding-core:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-broadcast-core:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-parser-sql-engine-postgresql:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-database-connector-postgresql:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-infra-data-source-pool-hikari:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-infra-url-classpath:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-standalone-mode-repository-memory:$shardingsphereVersion")
    runtimeOnly("org.apache.shardingsphere:shardingsphere-authority-simple:$shardingsphereVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
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

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("transaction-service.jar")
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
            artifactId = "transaction-service-client"
            version = project.version.toString()
            pom {
                name.set("transaction-service-client")
                description.set("Generated DTOs and HTTP service client of transaction-service")
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
