plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("thirdPartyNotices") {
            id = "flash.third-party-notices"
            implementationClass = "com.transfer.flash.buildlogic.ThirdPartyNoticesPlugin"
        }
    }
}
