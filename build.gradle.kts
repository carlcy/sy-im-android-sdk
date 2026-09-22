plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.sy.im.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "OPENIM_AVAILABLE", "true")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")

    // OpenIM Android SDK（Maven Central，2026-01 最新 android-sdk 3.8.3.5）
    // core-sdk 使用与官方 demo 同期的 patch 线；若链接失败可改 3.8.3-patch3
    implementation("io.openim:android-sdk:3.8.3.5@aar")
    implementation("io.openim:core-sdk:3.8.3-patch15@aar")
    implementation("com.google.code.gson:gson:2.10.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.sy.im"
                artifactId = "sy-im-android-sdk"
                version = "0.4.1"
            }
        }
    }
}
