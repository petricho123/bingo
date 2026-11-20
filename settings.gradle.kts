pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android(\\..*)?"); includeGroupByRegex("androidx(\\..*)?"); includeGroup("com.google.testing.platform") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("com\\.android(\\..*)?"); includeGroupByRegex("androidx(\\..*)?"); includeGroup("com.google.testing.platform"); includeGroup("com.google.android.material") } }
        mavenCentral()
    }
}
rootProject.name = "TodoBingo"
include(":app")
