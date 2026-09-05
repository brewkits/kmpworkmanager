import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    alias(libs.plugins.detekt)
    alias(libs.plugins.binaryCompatibilityValidator)
    id("maven-publish")
    id("signing")
}

group = "dev.brewkits"
version = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"

kotlin {
    // Android target
    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }

    // Support Maven Central requirements
    withSourcesJar()

    // iOS targets
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "kmpworker-testing"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // FakeBackgroundTaskScheduler implements BackgroundTaskScheduler and returns
                // TaskChain/ExecutionRecord/etc. — those types are part of this module's own
                // public API surface, so the dependency is `api`, not `implementation`.
                api(project(":kmpworker"))
            }
        }
    }
}

android {
    namespace = "dev.brewkits.kmpworkmanager.testing"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

val mavenCentralJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing {
        publications {
            withType<MavenPublication> {
                pom {
                    name.set("KMP WorkManager Testing")
                    description.set("Test doubles for KMP WorkManager — FakeBackgroundTaskScheduler and friends.")
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

                artifact(mavenCentralJavadocJar)
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

tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}
