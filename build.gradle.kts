plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

// JitPack 坐标：com.github.carlcy:sy-im-android-sdk:<git tag>
// 默认版本取 VERSION 文件并加上 v 前缀，与 tag（例如 v0.5.0）一致。
// JitPack / 本地发布可覆盖：-Pgroup=... -Pversion=...
val requestedGroup = providers.gradleProperty("group").orNull
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
val requestedVersion = providers.gradleProperty("version").orNull
    ?.takeIf { it.isNotBlank() && it != "unspecified" }
val fileVersion = project.file("VERSION").readText().trim().removePrefix("v")
val publishGroup = requestedGroup ?: "com.github.carlcy"
val publishVersion = requestedVersion ?: "v$fileVersion"

group = publishGroup
version = publishVersion

android {
    namespace = "com.sy.im.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("boolean", "OPENIM_AVAILABLE", "true")
        buildConfigField("String", "SDK_VERSION", "\"$fileVersion\"")
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

    // 不要加 @aar。`implementation("…@aar")` 会在 POM 里写成排除全部传递依赖（group=* artifact=*），
    // 客户工程就拿不到 OpenIM。不带 @aar 时，Gradle 按 Maven Central 上 packaging=aar 解析，
    // 并用 api 写入 compile 范围，客户只需依赖本库。
    api("io.openim:android-sdk:3.8.3.5")
    api("io.openim:core-sdk:3.8.3-patch15")
    api("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 在 JVM 单测中是桩，解析测试需要真实实现。
    testImplementation("org.json:json:20240303")
}

tasks.matching { it.name.endsWith("UnitTest") }.configureEach {
    dependsOn("generatePomFileForReleasePublication")
}

val signingKey = providers.gradleProperty("signingKey").orNull ?: System.getenv("SIGNING_KEY")
val signingPassword = providers.gradleProperty("signingPassword").orNull ?: System.getenv("SIGNING_PASSWORD")

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = publishGroup
                artifactId = "sy-im-android-sdk"
                version = publishVersion

                pom {
                    name.set("SY IM Android SDK")
                    description.set(
                        "SY 即时通信 Android SDK。封装 OpenIM（io.openim:android-sdk / core-sdk），" +
                            "客户通过 Maven 坐标引入；OpenIM 与 Gson 由本 POM 传递。",
                    )
                    url.set("https://github.com/carlcy/sy-im-android-sdk")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://github.com/carlcy/sy-im-android-sdk/blob/main/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("carlcy")
                            name.set("SY")
                            organization.set("shengyuchenyao")
                            organizationUrl.set("https://www.shengyuchenyao.cn")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/carlcy/sy-im-android-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/carlcy/sy-im-android-sdk.git")
                        url.set("https://github.com/carlcy/sy-im-android-sdk")
                    }
                }
            }
        }
        repositories {
            // Maven Central（Central Portal / OSSRH）。仅当维护者配置了账号才用于上传。
            // JitPack 走 publishToMavenLocal，不需要这里的凭据。
            maven {
                name = "ossrh"
                val snapshot = publishVersion.endsWith("SNAPSHOT")
                url = uri(
                    if (snapshot) {
                        "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    } else {
                        "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                    },
                )
                credentials {
                    username = providers.gradleProperty("ossrhUsername").orNull
                        ?: System.getenv("OSSRH_USERNAME")
                    password = providers.gradleProperty("ossrhPassword").orNull
                        ?: System.getenv("OSSRH_PASSWORD")
                }
            }
        }
    }

    if (!signingKey.isNullOrBlank()) {
        signing {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }
}
