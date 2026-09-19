// Root of the payment platform monorepo.
//
// The root ships no code - it exists to provide one Gradle Wrapper, one
// version catalog and one set of shared conventions for every module.
//
// Modules are included so a single wrapper builds them all, but they must NOT
// depend on each other via project(":..."). Cross-module contracts always
// travel through artifacts published to Nexus (see gradle.properties).

rootProject.name = "payment-platform"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Lets the monorepo build before Nexus is up:
        //   ./gradlew :person-client:publishToMavenLocal
        mavenLocal()
        mavenCentral()
        maven {
            name = "nexus"
            url = uri(providers.gradleProperty("nexusPublicUrl").get())
            isAllowInsecureProtocol =
                providers.gradleProperty("nexusAllowInsecureProtocol").get().toBoolean()
            // Credentials are attached only when both variables are present.
            // An always-on credentials { } block makes Gradle fail the whole
            // resolution with "Username must not be null!" on any machine that
            // has no Nexus login, instead of just skipping the repository.
            val user = providers.environmentVariable("NEXUS_USERNAME").orNull
            val pass = providers.environmentVariable("NEXUS_PASSWORD").orNull
            if (user != null && pass != null) {
                credentials {
                    username = user
                    password = pass
                }
                // Preemptive, for the same reason as in person-client: Nexus
                // answers an unauthenticated read with 403, and Gradle only
                // retries after a 401.
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
}

// --- Module 1 ---
include("person-client")
include("individuals-api")
include("person-service")

// --- Reserved for the next modules of the course ---
// include("transaction-service")
// include("payment-service")
// include("webhook-collector-service")
// include("notification-service")
