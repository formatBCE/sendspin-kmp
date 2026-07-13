plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.mavenPublish) apply false
}

// Maven coordinates. The Maven group (io.github.formatbce) is independent of the Kotlin package
// (com.sendspin.protocol) — consumers' imports are unaffected. Bump `version` for each release;
// Maven Central rejects -SNAPSHOT on the release endpoint, so releases use plain semver.
allprojects {
    group = "io.github.formatbce"
    version = "0.1.0"
}
