// person-service builds on its own.
//
// The folder above is a Git root, not a Gradle project: every module here is
// an independent build, and modules reach each other only through artifacts
// published to Nexus - never through project(":...").
//
// The one thing shared is the version catalog, a plain file in that folder.

rootProject.name = "person-service"

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
        mavenLocal()
        mavenCentral()
        maven {
            name = "nexus"
            url = uri(providers.gradleProperty("nexusPublicUrl").get())
            isAllowInsecureProtocol =
                providers.gradleProperty("nexusAllowInsecureProtocol").get().toBoolean()
            val user = providers.environmentVariable("NEXUS_USERNAME").orNull
            val pass = providers.environmentVariable("NEXUS_PASSWORD").orNull
            if (user != null && pass != null) {
                credentials {
                    username = user
                    password = pass
                }
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
}
