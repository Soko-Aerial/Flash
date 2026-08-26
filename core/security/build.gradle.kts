plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.security"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "core-security"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:persistence"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
