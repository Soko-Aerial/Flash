plugins {
    kotlin("jvm")
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
    implementation("com.google.zxing:core:3.5.3")

    // 5. Test suite
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}
