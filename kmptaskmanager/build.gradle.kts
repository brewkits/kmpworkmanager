import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinx.serialization)
    id("maven-publish")
}

group = "com.github.vietnguyentuan2019"
version = "2.1.0"

kotlin {
    androidTarget {
        publishLibraryVariants("release")

        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    jvmToolchain(17)

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "KMPTaskManager"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            // AndroidX WorkManager for native background tasks
            implementation(libs.androidx.work.runtime.ktx)
            // Coroutines support for Guava ListenableFuture
            implementation(libs.kotlinx.coroutines.guava)
            // Koin for Android
            implementation(libs.koin.android)
        }

        commonMain.dependencies {
            // Koin for dependency injection
            implementation(libs.koin.core)
            // Kotlinx Datetime for handling dates and times
            implementation(libs.kotlinx.datetime)
            // Kotlinx Serialization for JSON processing
            implementation(libs.kotlinx.serialization.json)
            // Kotlinx Coroutines
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "io.kmp.taskmanager"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

publishing {
    publications {
        // Configure all publications with common POM information
        withType<MavenPublication> {
            groupId = "com.github.vietnguyentuan2019"
            version = "2.1.0"

            pom {
                name.set("KMP TaskManager")
                description.set("A robust, cross-platform framework for scheduling and managing background tasks on Android and iOS using Kotlin Multiplatform")
                url.set("https://github.com/vietnguyentuan2019/KMPTaskManager")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("vietnguyentuan2019")
                        name.set("Nguyễn Tuấn Việt")
                        email.set("vietnguyentuan@gmail.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/vietnguyentuan2019/KMPTaskManager.git")
                    developerConnection.set("scm:git:ssh://github.com/vietnguyentuan2019/KMPTaskManager.git")
                    url.set("https://github.com/vietnguyentuan2019/KMPTaskManager")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/vietnguyentuan2019/KMPTaskManager")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
