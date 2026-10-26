rootProject.name = "transaction-service"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    repositories {
        mavenCentral()
        // the Avro serializer for Schema Registry is published only here
        maven("https://packages.confluent.io/maven/") {
            content { includeGroup("io.confluent") }
        }
    }
}
