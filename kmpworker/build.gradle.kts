import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.security.MessageDigest
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinx.serialization)
    id("maven-publish")
    id("signing")
}

group = "io.brewkits"
version = "4.0.0"

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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
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
            groupId = "io.brewkits"
            version = "4.0.0"

            pom {
                name.set("KMP TaskManager")
                description.set("A robust, cross-platform framework for scheduling and managing background tasks on Android and iOS using Kotlin Multiplatform")
                url.set("https://github.com/brewkits/kmp_worker")

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
                    connection.set("scm:git:git://github.com/brewkits/kmp_worker.git")
                    developerConnection.set("scm:git:ssh://github.com/brewkits/kmp_worker.git")
                    url.set("https://github.com/brewkits/kmp_worker")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/brewkits/kmp_worker")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }

        maven {
            name = "MavenCentralLocal"
            url = uri(layout.buildDirectory.dir("maven-central-staging"))
        }
    }
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = true
}

// Task to generate MD5 and SHA1 checksums for Maven Central
tasks.register("generateChecksums") {
    group = "publishing"
    description = "Generate MD5 and SHA1 checksums for Maven Central artifacts"

    dependsOn("publishAllPublicationsToMavenCentralLocalRepository")

    doLast {
        val stagingDir = project.layout.buildDirectory.dir("maven-central-staging").get().asFile
        if (!stagingDir.exists()) {
            logger.warn("Staging directory does not exist: $stagingDir")
            return@doLast
        }

        var checksumCount = 0
        stagingDir.walk().forEach { file ->
            if (file.isFile && !file.name.endsWith(".md5") && !file.name.endsWith(".sha1")
                && !file.name.endsWith(".sha256") && !file.name.endsWith(".sha512")
                && !file.name.endsWith(".asc")) {

                // Generate MD5
                val md5File = File(file.parentFile, "${file.name}.md5")
                if (!md5File.exists()) {
                    val md5 = MessageDigest.getInstance("MD5")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    md5File.writeText(md5)
                    checksumCount++
                    logger.lifecycle("Generated MD5: ${md5File.relativeTo(stagingDir)}")
                }

                // Generate SHA1
                val sha1File = File(file.parentFile, "${file.name}.sha1")
                if (!sha1File.exists()) {
                    val sha1 = MessageDigest.getInstance("SHA-1")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    sha1File.writeText(sha1)
                    checksumCount++
                    logger.lifecycle("Generated SHA1: ${sha1File.relativeTo(stagingDir)}")
                }
            }
        }

        logger.lifecycle("Generated $checksumCount checksum files in $stagingDir")
    }
}

// Task to publish only Android artifacts (workaround for iOS compilation issues)
tasks.register("publishAndroidWithChecksums") {
    group = "publishing"
    description = "Publish only Android artifacts with checksums to Maven Central"

    dependsOn("publishAndroidReleasePublicationToMavenCentralLocalRepository")

    doLast {
        val stagingDir = project.layout.buildDirectory.dir("maven-central-staging").get().asFile
        if (!stagingDir.exists()) {
            logger.warn("Staging directory does not exist: $stagingDir")
            return@doLast
        }

        var checksumCount = 0
        stagingDir.walk().forEach { file ->
            if (file.isFile && !file.name.endsWith(".md5") && !file.name.endsWith(".sha1")
                && !file.name.endsWith(".sha256") && !file.name.endsWith(".sha512")
                && !file.name.endsWith(".asc")) {

                // Generate MD5
                val md5File = File(file.parentFile, "${file.name}.md5")
                if (!md5File.exists()) {
                    val md5 = MessageDigest.getInstance("MD5")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    md5File.writeText(md5)
                    checksumCount++
                    logger.lifecycle("Generated MD5: ${md5File.relativeTo(stagingDir)}")
                }

                // Generate SHA1
                val sha1File = File(file.parentFile, "${file.name}.sha1")
                if (!sha1File.exists()) {
                    val sha1 = MessageDigest.getInstance("SHA-1")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    sha1File.writeText(sha1)
                    checksumCount++
                    logger.lifecycle("Generated SHA1: ${sha1File.relativeTo(stagingDir)}")
                }
            }
        }

        logger.lifecycle("Generated $checksumCount checksum files for Android artifacts in $stagingDir")
    }
}

signing {
    val signingKeyBase64 = project.findProperty("signing.key") as String?
    val signingPassword = project.findProperty("signing.password") as String? ?: ""

    if (signingKeyBase64 != null) {
        val signingKey = String(Base64.getDecoder().decode(signingKeyBase64))
        useInMemoryPgpKeys(
            signingKey,
            signingPassword
        )
    }
    sign(publishing.publications)
}
