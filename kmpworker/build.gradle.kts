import java.util.Base64
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.detekt)
    id("maven-publish")
    id("signing")
}

group = "dev.brewkits"
version = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"

// Coverage floor for the JVM/Android side (Kover cannot instrument Kotlin/Native — iOS
// coverage is not part of this number, see docs/COVERAGE.md). Measured LINE coverage at
// the time this gate was last ratcheted was 65.72% — the bound is set a few points below
// that so normal iteration doesn't trip the gate, while still catching an actual regression.
kover {
    reports {
        verify {
            rule {
                minBound(62, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
            }
        }
    }
}

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
            // NotificationCompat for the foreground-service notification in KmpWorker /
            // KmpHeavyWorker. Declared explicitly — it used to arrive transitively via
            // koin-android, which no production code in androidMain actually imports.
            implementation(libs.androidx.core.ktx)
        }

        commonMain.dependencies {
            // Kotlinx Datetime for handling dates and times
            implementation(libs.kotlinx.datetime)
            // Kotlinx Serialization for JSON processing
            implementation(libs.kotlinx.serialization.json)
            // Kotlinx Coroutines
            implementation(libs.kotlinx.coroutines.core)
            // Atomic operations (v2.3.7 fix)
            implementation(libs.kotlinx.atomicfu)
            // Okio for cross-platform file I/O (storage engine + FileCompressionWorker)
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // Kotest for property-based testing (v2.3.2+)
            implementation(libs.kotest.property)
            implementation(libs.kotest.framework.engine)
            // FakeBackgroundTaskScheduler now lives in its own published module
            // (extracted so external consumers can depend on it too). Safe despite
            // kmpworker-testing's own `api(project(":kmpworker"))`: Gradle resolves this
            // as commonTest(kmpworker) -> commonMain(kmpworker-testing) -> commonMain(kmpworker),
            // which is a DAG, not a cycle back to commonTest(kmpworker).
            implementation(project(":kmpworker-testing"))
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
        // Let Robolectric load merged Android resources (e.g. the worker notification
        // strings used by KmpWorker.getForegroundInfo()) in JVM unit tests.
        unitTests.isIncludeAndroidResources = true
    }
}

// Robolectric's native SQLite runtime is keyed per-JVM to one @Config(sdk=...) level; this
// suite mixes several (28/30/33/34), and a reused JVM across classes throws a native
// UnsatisfiedLinkError. forkEvery = 1 forces one JVM per class to keep the native state pinned.
tasks.withType<Test>().configureEach {
    forkEvery = 1
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

// Empty javadoc JAR required by Maven Central — registered once, shared across all publications.
val mavenCentralJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing {
        publications.withType<MavenPublication> {
            artifactId = artifactId.replace("kmpworker", "kmpworkmanager")
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
}

publishing {
    repositories {
        maven {
            name = "MavenCentralLocal"
            url = uri(rootProject.layout.buildDirectory.dir("maven-central-staging"))
        }
    }
}

// Workaround for Gradle implicit dependency false-positive between sign and publish tasks
// when using the signing plugin with KMP multi-target publications.
tasks.withType<AbstractPublishToMaven>().configureEach {
    mustRunAfter(tasks.withType<Sign>())
}

// K/N test runner defaults to one worker per CPU core. Multiple workers emit test events
// concurrently, triggering a ConcurrentModificationException in Gradle's non-thread-safe
// TestOutputStore$Writer. SIMCTL_CHILD_ prefix passes the var into the simulator process.
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
