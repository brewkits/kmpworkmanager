import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("maven-publish")
    id("signing")
}

group = "dev.brewkits"
version = "2.1.0"

kotlin {
    androidTarget {
        publishLibraryVariants("release")

        mavenPublication {
            artifactId = "kmpworkmanager-koin"
        }

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
            baseName = "KMPWorkManagerKoin"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Core KMP WorkManager library
            api(project(":kmpworker"))
            // Koin for dependency injection
            implementation(libs.koin.core)
        }

        androidMain.dependencies {
            // Koin for Android
            implementation(libs.koin.android)
        }
    }
}

android {
    namespace = "dev.brewkits.kmpworkmanager.koin"
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
        withType<MavenPublication> {
            pom {
                name.set("KMP WorkManager Koin Extension")
                description.set("Koin dependency injection extension for KMP WorkManager")
                url.set("https://github.com/yourusername/kmpworkmanager")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("brewkits")
                        name.set("Brewkits Dev")
                        email.set("dev@brewkits.dev")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/yourusername/kmpworkmanager.git")
                    developerConnection.set("scm:git:ssh://github.com:yourusername/kmpworkmanager.git")
                    url.set("https://github.com/yourusername/kmpworkmanager")
                }
            }
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}
