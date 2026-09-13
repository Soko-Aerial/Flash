// Root of the vendored webrtc-kmp fork (ADR-034). Publication/signing/ktlint infrastructure
// from the upstream build was removed — this build is consumed via composite-build
// substitution, never published or linted as a standalone project.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}
