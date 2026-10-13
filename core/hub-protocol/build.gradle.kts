// ADR-107: :core:hub-protocol is the single definition of the Flash enterprise-hub wire contract: certificate types,
// the revocation model, the relay frames and codec, the REST DTOs and the paths/limits both ends reference.
// It is pure Kotlin Multiplatform (android + jvm) and depends on :core:common plus kotlinx-serialization-json ONLY:
// no Ktor, no coroutines, no Room, and no :core:security (that module depends on this one). Signature checks go
// through the EcdsaVerifier seam so this module never touches java.security or FlashCrypto.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

kotlin {
    explicitApi()

    android {
        namespace = "com.transfer.flash.core.hubprotocol"
        compileSdk = 35
        minSdk = 24

        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        withHostTest { }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // The certificate and DTO types are @Serializable and the codecs expose kotlinx.serialization.json.Json
            // configuration, so the JSON artifact is `api` for consumers that build on the DTOs.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("hub-protocol", "core-hub-protocol")
        }
    }
}
