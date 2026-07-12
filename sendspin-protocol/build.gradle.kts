import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
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
