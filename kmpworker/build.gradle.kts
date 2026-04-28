import java.util.Base64
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinx.serialization)
    id("maven-publish")
    id("signing")
}

group = "dev.brewkits"
version = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"

kotlin {
    androidTarget {
        publishLibraryVariants("release")
    }

    // Support Maven Central requirements
    withSourcesJar()

    jvmToolchain(17)

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.all {
            freeCompilerArgs += listOf("-Xoverride-konan-properties=min_ios_version=15.0")
        }
        iosTarget.binaries.framework {
            baseName = "KMPWorkManager"
            isStatic = true
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
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
            // Ktor Client - OkHttp engine for Android
            implementation(libs.ktor.client.okhttp)
        }

        commonMain.dependencies {
            // Koin for dependency injection
            implementation(libs.koin.core)
            // Kotlinx Datetime for handling dates and times
            implementation(libs.kotlinx.datetime)
            // Kotlinx Serialization for JSON processing
            implementation(libs.kotlinx.serialization.json)
            // Kotlinx Coroutines
            implementation(libs.kotlinx.coroutines.core)
            // Atomic operations (v2.3.7 fix)
            implementation(libs.kotlinx.atomicfu)
            // Ktor Client for HTTP operations (built-in workers)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // Ktor plugins for HttpClient optimization (v2.4.0+)
            implementation(libs.ktor.client.encoding)
            implementation(libs.ktor.client.logging)
            // Okio for cross-platform file I/O
            implementation(libs.okio)
        }

        iosMain.dependencies {
            // Ktor Client - Darwin engine for iOS
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            // Kotest for property-based testing (v2.3.2+)
            implementation(libs.kotest.property)
            implementation(libs.kotest.framework.engine)
        }

        androidInstrumentedTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit.common)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.work.testing)
            implementation(libs.kotlinx.coroutines.test)
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.core)
                implementation(libs.robolectric)
                implementation(libs.androidx.work.testing)
            }
        }
    }
}

android {
    namespace = "dev.brewkits.kmpworkmanager"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Empty javadoc JAR required by Maven Central — registered once, shared across all publications.
val mavenCentralJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication> {
        artifact(mavenCentralJavadocJar)

        pom {
            name.set("KMP WorkManager")
            description.set("Kotlin Multiplatform library for background task scheduling on Android and iOS.")
            url.set("https://github.com/brewkits/kmpworkmanager")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("brewkits")
                    name.set("Brewkits Team")
                    email.set("vietnguyentuan@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/brewkits/kmpworkmanager.git")
                developerConnection.set("scm:git:ssh://github.com/brewkits/kmpworkmanager.git")
                url.set("https://github.com/brewkits/kmpworkmanager")
            }
        }
    }
}

// Workaround for Gradle implicit dependency false-positive between sign and publish tasks
// when using the signing plugin with KMP multi-target publications.
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}

signing {
    val signingKeyBase64 = project.findProperty("signing.key") as String?
    val signingPassword = project.findProperty("signing.password") as String? ?: ""
    isRequired = signingKeyBase64 != null
    if (signingKeyBase64 != null) {
        val signingKey = String(Base64.getDecoder().decode(signingKeyBase64))
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}
