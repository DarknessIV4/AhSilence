pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AhSilence"
include(":app")
include(":ancengine")

// Dynamically locate the Android SDK across operating systems and users without hardcoding
val localPropsFile = file("local.properties")
if (!localPropsFile.exists()) {
    val userHome = System.getProperty("user.home")
    val candidatePaths = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        "$userHome/AppData/Local/Android/Sdk",
        "$userHome/Library/Android/sdk",
        "$userHome/Android/Sdk"
    )
    val detectedSdk = candidatePaths.map { file(it) }.firstOrNull { it.exists() && it.isDirectory }
    if (detectedSdk != null) {
        localPropsFile.writeText("sdk.dir=${detectedSdk.absolutePath.replace("\\", "/")}\n")
    }
}
