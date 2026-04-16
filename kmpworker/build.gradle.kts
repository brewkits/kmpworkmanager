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

group = "dev.brewkits"
version = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"

kotlin {
    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release")

        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    // Support Maven Central requirements
    withSourcesJar()

    jvmToolchain(17)

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "KmpWorkerLibrary"
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
}

// Empty javadoc JAR required by Maven Central — registered once, shared across all publications.
val mavenCentralJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing {
        publications {
            // Configure all publications with common POM information
            withType<MavenPublication> {
                groupId = "dev.brewkits"
                artifactId = artifactId.replace("kmpworker", "kmpworkmanager")
                version = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"

                pom {
                    name.set("KMP WorkManager")
                    description.set("Kotlin Multiplatform background task scheduler for Android & iOS. Unified API for WorkManager (Android) and BGTaskScheduler (iOS) with progress tracking, task chains, and production-ready reliability.")
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
    publications {
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/brewkits/kmpworkmanager")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }

        maven {
            name = "MavenCentralLocal"
            url = uri(rootProject.layout.buildDirectory.dir("maven-central-staging"))
        }

        // Sonatype OSSRH (Maven Central)
        maven {
            name = "OSSRH"
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

            credentials {
                username = project.findProperty("ossrhUsername") as String? ?: ""
                password = project.findProperty("ossrhPassword") as String? ?: ""
            }
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
        val stagingDir = rootProject.layout.buildDirectory.dir("maven-central-staging").get().asFile
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
        val stagingDir = rootProject.layout.buildDirectory.dir("maven-central-staging").get().asFile
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

    // Only enable signing if credentials are available
    isRequired = signingKeyBase64 != null

    if (signingKeyBase64 != null) {
        val signingKey = String(Base64.getDecoder().decode(signingKeyBase64))
        useInMemoryPgpKeys(
            signingKey,
            signingPassword
        )
    }
    sign(publishing.publications)
}

// Workaround for Gradle implicit dependency false-positive between sign and publish tasks
// when using the signing plugin with KMP multi-target publications.
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}
