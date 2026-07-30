import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing. The keystore never lives in the repo: create
 * `keystore.properties` next to this file with
 *
 *     storeFile=/absolute/path/to/drawbridge-release.jks
 *     storePassword=...
 *     keyAlias=drawbridge
 *     keyPassword=...
 *
 * or set DRAWBRIDGE_KEYSTORE / DRAWBRIDGE_KEYSTORE_PASSWORD /
 * DRAWBRIDGE_KEY_ALIAS / DRAWBRIDGE_KEY_PASSWORD in the environment, which is
 * what CI uses.
 *
 * Updates must be signed with the same key as the installed app, so losing this
 * keystore strands every deployed device on its current version. Back it up
 * offline before the first install goes out.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    keystoreProperties.getProperty(propertyKey) ?: System.getenv(environmentKey)

android {
    namespace = "app.drawbridge.dpc"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.drawbridge.dpc"
        minSdk = 28
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The browser drawbridge allows and makes the default handler. The policy
        // document can override it per-device; this is the value used before the
        // first policy has been applied.
        buildConfigField("String", "ALLOWED_BROWSER_PACKAGE", "\"app.drawbridge.herald\"")
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "DRAWBRIDGE_KEYSTORE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "DRAWBRIDGE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DRAWBRIDGE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DRAWBRIDGE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // NOTE: no applicationIdSuffix here. Device Owner is bound to the exact
            // package name used at provisioning time, and the QR payload / adb
            // command must match, so debug and release share one applicationId.
            versionNameSuffix = "-debug"

            // DISALLOW_DEBUGGING_FEATURES switches off USB debugging the instant
            // it is applied, which disconnects adb and makes the device
            // untestable and un-reinstallable. Debug builds therefore skip that
            // one restriction; every other restriction still applies, and release
            // builds always enforce it.
            buildConfigField("boolean", "RETAIN_ADB_ACCESS", "true")
        }
        release {
            // Falls back to unsigned when no keystore is configured, so a
            // plain `assembleRelease` still works for inspection.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "RETAIN_ADB_ACCESS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":policy"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
