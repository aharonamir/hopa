plugins {
    id("hopa.kotlin-jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.hopa.backend.MainKt")
}

sourceSets {
    test {
        // The contracts are the test data: the same fixtures CI replays in Python.
        resources.srcDir(file("../../spec"))
    }
}

dependencies {
    implementation(project(":backend"))
    implementation(project(":inference"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
        // A comparison failure must say what it compared: the short format
        // prints only "ComparisonFailure at Assert.java:117".
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<JavaExec>("exitRun") {
    group = "verification"
    description = "Run N gateway round-trips against a deployed instance."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hopa.backend.tools.ExitRunKt")
}
