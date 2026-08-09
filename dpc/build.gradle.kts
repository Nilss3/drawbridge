import java.security.MessageDigest
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

/**
 * Optional development back door: a second key that unlocks any device running
 * this build. Set `emergencyKey` in `keystore.properties`, or
 * `DRAWBRIDGE_EMERGENCY_KEY` in the environment; leave it out and the build has
 * no second key at all.
 *
 * Only the SHA-256 goes into the APK, so the build cannot be taken apart to
 * recover the key — but it is the same key on every device it is installed on
 * and it never rotates, so this must not be in a build anybody else receives.
 * See ParentKey.matchesEmergencyKey.
 *
 * The key is normalised here exactly as ParentKey normalises typed input:
 * uppercase, Crockford aliases folded, anything else dropped. The build fails
 * rather than compiling in a hash of something mistyped.
 */
val emergencyKeyHash: String = run {
    val raw = keystoreProperties.getProperty("emergencyKey")
        ?: System.getenv("DRAWBRIDGE_EMERGENCY_KEY")
        ?: return@run ""

    val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val normalised = raw.uppercase()
        .map { if (it == 'O') '0' else if (it == 'I' || it == 'L') '1' else it }
        .filter { it in alphabet }
        .joinToString("")

    require(normalised.length == 20) {
        "emergencyKey must be 20 Crockford base32 characters; got ${normalised.length} after " +
            "normalising. Generate one the way the app does rather than inventing it by hand."
    }

    MessageDigest.getInstance("SHA-256")
        .digest(normalised.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

android {
    namespace = "app.drawbridge.dpc"
    compileSdk = 36

    defaultConfig {
        /**
         * Overridable only so the Play Protect investigation can build a probe:
         * the same APK, same signing key, same everything, under a different
         * package name. Four rounds of manifest and session changes were all
         * refused while herald installed from the same installer, so the open
         * question is whether the verdict is attached to what drawbridge *is*
         * rather than to anything it declares. See docs/handoff.md.
         *
         *     ./gradlew :dpc:assembleRelease -PdpcApplicationId=app.drawbridge.probe
         *
         * **That build overwrites `dpc/build/outputs/apk/release/dpc-release.apk`
         * with the probe.** Copy it out under its own name and do not run
         * `tools/stage-release.sh` until the real DPC has been rebuilt, or the
         * probe will be staged as the release.
         *
         * A product flavour would have been the tidier Gradle answer and is
         * deliberately not used: it renames every APK output, which is exactly
         * what `stage-release.sh` exists to keep stable.
         */
        applicationId = providers.gradleProperty("dpcApplicationId")
            .getOrElse("app.drawbridge.dpc")
        minSdk = 28

        /**
         * herald asks for this permission by its literal name, so the real build
         * must keep it exactly as it is — this is a placeholder only so that a
         * probe under a different package name does not collide with the
         * installed drawbridge. Two packages cannot declare the same permission
         * unless they share a signing certificate, and even sharing one, the
         * *installed* owner wins: a probe redeclaring it fails with
         * INSTALL_FAILED_DUPLICATE_PERMISSION before Play Protect is ever asked.
         *
         * Which on a phone with no adb would have been indistinguishable from
         * being blocked. Caught on the emulator on 2026-08-10, before publishing.
         */
        manifestPlaceholders["readSelectionPermission"] =
            providers.gradleProperty("dpcApplicationId")
                .map { "$it.permission.READ_SELECTION" }
                .getOrElse("app.drawbridge.permission.READ_SELECTION")

        // Defaults to the resource reference, so the real build is unchanged and
        // stays translated. A probe takes a literal instead: without it the
        // phone shows two apps called "drawbridge" and a Play Protect
        // notification that cannot be attributed to either — and a second app
        // wearing the first one's name and icon is its own PHA signal, which is
        // the last thing an experiment about PHA signals needs.
        manifestPlaceholders["appLabel"] =
            providers.gradleProperty("dpcAppLabel").getOrElse("@string/app_name")

        // Same story, one collision later: a ContentProvider authority is
        // globally unique on a device, so a probe reusing drawbridge's fails
        // with INSTALL_FAILED_CONFLICTING_PROVIDER. herald looks this authority
        // up by its literal name, so the default must not move.
        manifestPlaceholders["selectionAuthority"] =
            providers.gradleProperty("dpcApplicationId")
                .map { "$it.selection" }
                .getOrElse("app.drawbridge.dpc.selection")
        targetSdk = 36
        versionCode = 15
        versionName = "0.2.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The browser drawbridge allows and makes the default handler. The policy
        // document can override it per-device; this is the value used before the
        // first policy has been applied.
        buildConfigField("String", "ALLOWED_BROWSER_PACKAGE", "\"app.drawbridge.herald\"")

        // Empty in any build that does not configure one, which compiles the
        // second-key check down to a constant-false.
        buildConfigField("String", "EMERGENCY_KEY_SHA256", "\"$emergencyKeyHash\"")
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
