pluginManagement {
    buildscript {
        repositories { google(); mavenCentral() }
        // Kotlin 2.4 metadata requires R8 9.1.29 or newer.
        dependencies { classpath("com.android.tools:r8:9.1.57") }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "GreenhouseMonitor"
include(":app")
