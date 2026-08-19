// person-service
//
// In module 1 this module ships no code. It carries the two artifacts the next
// module builds on:
//   - openapi/person-service.yaml   the final contract of the user service
//   - src/main/resources/db/migration  Flyway migrations for the domain schema
//
// The contract here is what person-client generates from, so validating it is
// part of every build.

plugins {
    alias(libs.plugins.openapi.generator)
}

openApiValidate {
    inputSpec.set(layout.projectDirectory.file("openapi/person-service.yaml").asFile.absolutePath)
    recommend.set(true)
}

tasks.named("check") { dependsOn("openApiValidate") }
