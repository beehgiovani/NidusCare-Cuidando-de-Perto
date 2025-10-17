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
        google() // This is important
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MedControl"
include(":app")
