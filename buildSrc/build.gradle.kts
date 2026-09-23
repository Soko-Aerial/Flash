// Build logic shared by :app and :desktop. Holds one task, ThirdPartyNoticesTask (audit C1, ADR-043).
// It lives here rather than in a build script because a task class declared inside a .kts script
// compiles as an inner class, which Gradle can't instantiate and the configuration cache can't store.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}
