// individuals-api builds on its own.
//
// The folder above is a Git root, not a Gradle project: every module here is
// an independent build, and modules reach each other only through artifacts
// published to Nexus - never through project(":...").
//
// person-client is resolved from mavenLocal or Nexus by its coordinates, which
// is why both repositories are listed below.
//
// The one thing shared is the version catalog, a plain file in that folder.

rootProject.name = "individuals-api"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }

    repositories {
        // Lets this module build before Nexus is up:
        //   cd ../person-client && ./gradlew publishToMavenLocal
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
                // Preemptive: Nexus answers an unauthenticated read with 403,
                // and Gradle only retries after a 401.
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
}
