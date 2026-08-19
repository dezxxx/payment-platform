// Shared conventions for every module in the monorepo.
//
// The root project builds nothing itself. It only owns the Gradle Wrapper,
// the version catalog (gradle/libs.versions.toml) and the conventions below,
// so no module has to repeat toolchain, encoding, Lombok or test wiring.

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.openapi.generator) apply false
}

val javaToolchain = providers.gradleProperty("javaToolchainVersion").get().toInt()

allprojects {
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(javaToolchain))
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

    // Lombok is compile-only + annotation processor in both source sets.
    dependencies {
        val lombok = rootProject.libs.lombok
        add("compileOnly", lombok)
        add("annotationProcessor", lombok)
        add("testCompileOnly", lombok)
        add("testAnnotationProcessor", lombok)
    }
}

// Type-safe access to the version catalog from inside subprojects { }.
val Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = extensions.getByName("libs") as org.gradle.accessors.dm.LibrariesForLibs
