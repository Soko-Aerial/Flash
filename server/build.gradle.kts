plugins {
    kotlin("jvm")
    // SEC-15: the admin JSON API uses kotlinx.serialization DTOs (no regex / hand-built JSON).
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.transfer.flash.server.MainKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=com.transfer.flash.core.common.annotation.FlashInternalApi"
        )
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    // 1. Shared core logic from monorepo (headless, zero-UI)
    implementation(project(":core:common"))
    implementation(project(":core:hub-protocol"))
    implementation(project(":core:security"))

    // 2. Ktor server engine & plugins
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)

    // 3. Persistence & Connection Pool for store-and-forward mailbox
    implementation(libs.hikaricp)
    implementation(libs.sqlite.jdbc.crypt)

    // 4. Logging & Utilities
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // tlsMode=self: generates the self-signed TLS certificate (X.509 builder). Server-side only; BSD-style MIT licence.
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pkix)
    implementation("com.google.zxing:core:3.5.3")

    // 5. Test suite
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)

    // Contract test (RelayHandshakeContractTest): the REAL client transport against the REAL server handshake.
    // testImplementation ONLY: the server never depends on a client module at runtime (ADR-107).
    testImplementation(project(":core:network"))
    testImplementation(project(":core:enterprise"))
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.websockets)
    // The e2e contract tests (`server/src/test/.../e2e`) open real sockets with the same engine the apps use (CIO); the
    // test-host artifact on the classpath would otherwise pick an engine without WebSocket support.
    testImplementation(libs.ktor.client.cio)
}
