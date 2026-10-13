// ADR-128: the enterprise hub client (enrolment, directory, relay client, trust stores, sealing keys, hub session),
// extracted from :core:network. It depends on :core:hub-protocol, :core:security and :core:common, NEVER on :core:network.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApi()

    android {
        namespace = "com.transfer.flash.core.enterprise"
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

        // Creates `androidHostTest` and the `testAndroidHostTest` task. Without it the commonTest files stop running on the
        // Android host while the build still reports SUCCESS.
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
            api(project(":core:hub-protocol"))
            api(project(":core:security"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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
            artifactId = artifactId.replace("enterprise", "core-enterprise")
        }
    }
}
