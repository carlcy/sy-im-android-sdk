pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("com.android.library") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
    }
}

val useLocalSdk = providers.gradleProperty("useLocalSdk").orNull == "true"
val preferMavenLocal = providers.gradleProperty("preferMavenLocal").orNull == "true"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 仅 SDK 作者在 publishToMavenLocal 之后验证坐标时打开：-PpreferMavenLocal=true
        if (preferMavenLocal) {
            mavenLocal()
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SyImSDKExample"
include(":app")
if (useLocalSdk) {
    include(":sy-im-android-sdk")
    project(":sy-im-android-sdk").projectDir = file("..")
}
