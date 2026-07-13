import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    // expect/actual classes are still Beta (KT-61573); suppress the per-declaration warnings.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Android target — required so the consumer's androidMain can resolve this dependency
    // (a plain jvm() variant has incompatible platform attributes for an androidTarget consumer).
    android {
        namespace = "com.sendspin.protocol"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // jvm() exists purely for fast unit tests (jvmTest, CIO echo server); not consumed by the app.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.serialization.json) // brings kotlinx-serialization-json
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)            // client engine for transport tests
            implementation(libs.ktor.server.cio)            // embedded echo WebSocket server
            implementation(libs.ktor.server.websockets)
            implementation(libs.mockk)                      // AudioBuffer test mocks the ClockSync estimate
        }
    }
}

// Maven Central (Central Portal) publishing. Coordinates/POM below; credentials + GPG key come from
// Gradle properties / env at publish time (see .github/workflows/publish.yml and PUBLISHING.md).
// vanniktech auto-configures publications for every KMP target (android/jvm/iosArm64/iosSimulatorArm64)
// plus the root module and sources/javadoc jars.
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(group.toString(), "sendspin-protocol", version.toString())

    pom {
        name.set("SendSpin Protocol (KMP)")
        description.set(
            "Kotlin Multiplatform client for the SendSpin audio-streaming protocol (player@v1): " +
                "clock sync, timestamp-scheduled audio buffering, and a carrier-agnostic transport.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/formatBCE/sendspin-kmp")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("formatBCE")
                name.set("formatBCE")
                url.set("https://github.com/formatBCE")
            }
        }
        scm {
            url.set("https://github.com/formatBCE/sendspin-kmp")
            connection.set("scm:git:git://github.com/formatBCE/sendspin-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/formatBCE/sendspin-kmp.git")
        }
    }
}
