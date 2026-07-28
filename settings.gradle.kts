pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mozilla's Maven: GeckoView + Android Components prebuilt AARs.
        maven("https://maven.mozilla.org/maven2")
    }
}

rootProject.name = "drawbridge"

include(":policy")
include(":herald")
include(":dpc")
