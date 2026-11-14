plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes these from the release workflow.
val forgeVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val forgeVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.amandhakar.apkforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amandhakar.apkforge"
        minSdk = 26
        targetSdk = 35
        versionCode = forgeVersionCode
        versionName = forgeVersionName
    }

    signingConfigs {
        // Set by CI from repository secrets. Every release must use the same key.
        val keystore = System.getenv("KEYSTORE_FILE")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    // Google's APK signing library (Apache 2.0): signs and verifies APKs. Pure Java 8, no dependencies.
    implementation("com.android.tools.build:apksig:9.5.1")
    // smali / baksmali (BSD-3, Google's build, pure Java): read an app's code as smali and assemble edited smali back.
    // Only what the library calls needs to ship: the command-line parts (jcommander) and ANTLR's grammar *tool* do not.
    implementation("com.android.tools.smali:smali-dexlib2:3.0.10")
    implementation("com.android.tools.smali:smali-baksmali:3.0.10") { exclude(group = "org.jcommander") }
    implementation("com.android.tools.smali:smali:3.0.10") { exclude(group = "org.jcommander"); exclude(group = "org.antlr", module = "antlr") }
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
