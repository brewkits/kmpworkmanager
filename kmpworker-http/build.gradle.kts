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

    withSourcesJar()

    jvmToolchain(17)

    // iOS targets. NOTE: unlike :kmpworker this module does NOT declare a framework
    // binary — built-in HTTP workers ship as a klib and are linked into the consumer's
    // own framework (e.g. composeApp), so iOS apps never need to link two frameworks.
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.all {
            freeCompilerArgs += listOf("-Xoverride-konan-properties=min_ios_version=15.0")
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
            // Ktor Client - OkHttp engine for Android
            implementation(libs.ktor.client.okhttp)
        }

        commonMain.dependencies {
            // Core engine + Worker contracts, configs, SecurityValidator (api: types are
            // exposed on built-in worker constructors and return values).
            api(project(":kmpworker"))
            // Kotlinx Coroutines
            implementation(libs.kotlinx.coroutines.core)
            // Kotlinx Serialization for JSON processing
            implementation(libs.kotlinx.serialization.json)
            // Atomic operations (parallel workers' progress/failure counters)
            implementation(libs.kotlinx.atomicfu)
            // Okio for cross-platform file I/O (upload/download streaming, checksums)
            implementation(libs.okio)
            // Ktor Client for HTTP operations
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.encoding)
            implementation(libs.ktor.client.logging)
        }

        iosMain.dependencies {
            // Ktor Client - Darwin engine for iOS
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotest.property)
            implementation(libs.kotest.framework.engine)
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.core)
                implementation(libs.robolectric)
            }
        }
    }
}

android {
    namespace = "dev.brewkits.kmpworkmanager.http"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

afterEvaluate {
    publishing {
        publications.withType<MavenPublication> {
            artifactId = artifactId.replace("kmpworker-http", "kmpworkmanager-http")
            artifact(mavenCentralJavadocJar)

            pom {
                name.set("KMP WorkManager — HTTP Workers")
                description.set("Ktor-based built-in HTTP workers for KMP WorkManager (download, upload, request, sync, parallel).")
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
}

publishing {
    repositories {
        maven {
            name = "MavenCentralLocal"
            url = uri(rootProject.layout.buildDirectory.dir("maven-central-staging"))
        }
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_KOTLIN_TEST_WORKERS", "1")
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
