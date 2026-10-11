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
        // lets this module build before Nexus is up
        mavenLocal()
        mavenCentral()
        maven {
            name = "nexus"
            url = uri(providers.gradleProperty("nexusPublicUrl").get())
            isAllowInsecureProtocol =
                providers.gradleProperty("nexusAllowInsecureProtocol").get().toBoolean()
            // credentials only when both are set: an empty login fails the whole build
            val user = providers.environmentVariable("NEXUS_USERNAME").orNull
            val pass = providers.environmentVariable("NEXUS_PASSWORD").orNull
            if (user != null && pass != null) {
                credentials {
                    username = user
                    password = pass
                }
                // preemptive: Nexus answers an anonymous read with 403, and Gradle retries only after a 401
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
}
