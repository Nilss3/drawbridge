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
    namespace = "app.drawbridge.herald"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.drawbridge.herald"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // GeckoView ships native libraries for every ABI; a universal APK would be
    // several hundred MB. Split per ABI instead.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
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
            // No applicationIdSuffix: the policy names the one allowed browser by
            // package name, so a ".debug" build would be treated as a rogue
            // browser and silently uninstalled by drawbridge's app blocker the
            // moment the device is provisioned.
            versionNameSuffix = "-debug"
        }
        release {
            // Falls back to unsigned when no keystore is configured, so a
            // plain `assembleRelease` still works for inspection.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// GeckoView's omni AAR bundles libxul, which already contains the glean native
// library. The application-services libraries pulled in by browser-storage-sync
// depend on org.mozilla.telemetry:glean-native separately, so both providers
// claim the same Gradle capability. Always prefer GeckoView's copy.
configurations.configureEach {
    resolutionStrategy.capabilitiesResolution.withCapability("org.mozilla.telemetry:glean-native") {
        val fromGeckoView = candidates.firstOrNull { candidate ->
            val id = candidate.id
            id is org.gradle.api.artifacts.component.ModuleComponentIdentifier &&
                id.module.contains("geckoview")
        }
        if (fromGeckoView != null) {
            select(fromGeckoView)
            because("GeckoView's libxul already provides the glean native library")
        }
    }
}

dependencies {
    implementation(project(":policy"))

    // Engine + browser state
    implementation(libs.mozac.browser.engine.gecko)
    implementation(libs.mozac.browser.state)
    implementation(libs.mozac.browser.session.storage)
    implementation(libs.mozac.browser.storage.sync)
    implementation(libs.mozac.browser.domains)
    implementation(libs.mozac.browser.errorpages)
    implementation(libs.mozac.browser.icons)
    implementation(libs.mozac.browser.menu)
    implementation(libs.mozac.browser.menu2)
    implementation(libs.mozac.browser.tabstray)
    implementation(libs.mozac.browser.thumbnails)
    implementation(libs.mozac.browser.toolbar)

    // Concepts
    implementation(libs.mozac.concept.awesomebar)
    implementation(libs.mozac.concept.engine)
    implementation(libs.mozac.concept.fetch)
    implementation(libs.mozac.concept.menu)
    implementation(libs.mozac.concept.storage)
    implementation(libs.mozac.concept.toolbar)

    // Features
    implementation(libs.mozac.feature.app.links)
    implementation(libs.mozac.feature.awesomebar)
    implementation(libs.mozac.feature.contextmenu)
    implementation(libs.mozac.feature.downloads)
    implementation(libs.mozac.feature.findinpage)
    implementation(libs.mozac.feature.intent)
    implementation(libs.mozac.feature.logins)
    implementation(libs.mozac.feature.media)
    implementation(libs.mozac.feature.prompts)
    implementation(libs.mozac.feature.readerview)
    implementation(libs.mozac.feature.search)
    implementation(libs.mozac.feature.session)
    implementation(libs.mozac.feature.sitepermissions)
    implementation(libs.mozac.feature.tabs)
    implementation(libs.mozac.feature.toolbar)
    implementation(libs.mozac.feature.top.sites)
    implementation(libs.mozac.feature.webcompat)

    // Libs / support / ui
    implementation(libs.mozac.lib.dataprotect)
    implementation(libs.mozac.lib.fetch.httpurlconnection)
    implementation(libs.mozac.lib.publicsuffixlist)
    implementation(libs.mozac.lib.state)
    implementation(libs.mozac.service.sync.logins)
    implementation(libs.mozac.support.appservices)
    implementation(libs.mozac.support.base)
    implementation(libs.mozac.support.ktx)
    implementation(libs.mozac.support.utils)
    implementation(libs.mozac.support.webextensions)
    implementation(libs.mozac.ui.colors)
    implementation(libs.mozac.ui.icons)
    implementation(libs.mozac.ui.tabcounter)
    implementation(libs.mozac.ui.widgets)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
}
