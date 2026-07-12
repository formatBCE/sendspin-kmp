plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
}

// Coordinate used both by the composite build (dependency substitution from MusicAssistantClient)
// and, later, by Maven Central publishing.
allprojects {
    group = "com.sendspin"
    version = "0.1.0-SNAPSHOT"
}
