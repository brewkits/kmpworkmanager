plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

// Pin Kotlin stdlib to the version matching the Kotlin Gradle plugin.
// Without this, newer AndroidX transitive deps (e.g. lifecycle 2.9+) pull in a
// stdlib klib that is ABI-incompatible with the KGP compiler and causes
// "Could not find kotlin-stdlib-X-commonMain-*.klib" build errors.
val kotlinVersion = libs.versions.kotlin.get()
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                (requested.name.startsWith("kotlin-stdlib") || requested.name == "kotlin-test")
            ) {
                useVersion(kotlinVersion)
                because("Pin stdlib/test to Kotlin plugin version $kotlinVersion — avoids KGP ABI mismatch")
            }
        }
    }
}

// Task to clean root build directory
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// Point git at the committed .githooks/ directory so the commit-msg guard
// (rejects accidental Claude/Anthropic attribution) survives a fresh clone.
// The hook file is versioned; this task re-points core.hooksPath after clone.
val installGitHooks by tasks.registering(Exec::class) {
    group = "git hooks"
    description = "Set git core.hooksPath to the committed .githooks directory."
    onlyIf { rootProject.file(".git").exists() }
    commandLine("git", "config", "core.hooksPath", ".githooks")
}

// Wire hook installation into every module's build/check so the first
// `./gradlew build` on a fresh clone activates the hooks automatically.
subprojects {
    afterEvaluate {
        tasks.matching { it.name == "build" || it.name == "check" }
            .configureEach { dependsOn(installGitHooks) }
    }
}

// Wipes the Maven staging dir so a new bundle can never accidentally pick up
// artifacts from a previous run (e.g. the older version's `.module` files when
// you bump from 2.4.3 → 2.5.0 in the same workspace). Must run BEFORE the
// per-module `publishAllPublicationsToMavenCentralLocalRepository` tasks.
val cleanMavenStaging by tasks.registering(Delete::class) {
    group = "publishing"
    description = "Delete build/maven-central-staging and build/distributions before bundling."
    delete(layout.buildDirectory.dir("maven-central-staging"))
    delete(layout.buildDirectory.dir("distributions"))
}

// Force each module's publish task to run AFTER the clean. Without this Gradle
// is free to schedule the publish before the clean wipes the staging dir,
// which would zero out the freshly-published artifacts.
subprojects {
    afterEvaluate {
        tasks.matching { it.name == "publishAllPublicationsToMavenCentralLocalRepository" }
            .configureEach { mustRunAfter(cleanMavenStaging) }
    }
}

// Task to generate a full Maven Central distribution ZIP
tasks.register<Zip>("generateFullMavenZip") {
    group = "publishing"
    description = "Generates a ZIP file containing all Maven artifacts and checksums for manual upload"

    val versionName = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"
    val stagingDir = layout.buildDirectory.dir("maven-central-staging")
    archiveFileName.set("kmpworkmanager-maven-central-$versionName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(stagingDir)

    // Always start from an empty staging dir, then publish all 3 modules into it.
    dependsOn(cleanMavenStaging)
    dependsOn(":kmpworker:publishAllPublicationsToMavenCentralLocalRepository")
    dependsOn(":kmpworker-http:publishAllPublicationsToMavenCentralLocalRepository")
    dependsOn(":kmpworker-annotations:publishAllPublicationsToMavenCentralLocalRepository")
    dependsOn(":kmpworker-ksp:publishAllPublicationsToMavenCentralLocalRepository")
    
    // Checksums are handled by each module or a global step
    doFirst {
        val stagingFile = stagingDir.get().asFile
        if (!stagingFile.exists()) return@doFirst

        var checksumCount = 0
        stagingFile.walk().forEach { file ->
            if (file.isFile && !file.name.endsWith(".md5") && !file.name.endsWith(".sha1")
                && !file.name.endsWith(".sha256") && !file.name.endsWith(".sha512")
                && !file.name.endsWith(".asc")
            ) {
                // Generate MD5
                val md5File = java.io.File(file.parentFile, "${file.name}.md5")
                if (!md5File.exists()) {
                    val md5 = java.security.MessageDigest.getInstance("MD5")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    md5File.writeText(md5)
                    checksumCount++
                }

                // Generate SHA1
                val sha1File = java.io.File(file.parentFile, "${file.name}.sha1")
                if (!sha1File.exists()) {
                    val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    sha1File.writeText(sha1)
                    checksumCount++
                }

                // Generate SHA256
                val sha256File = java.io.File(file.parentFile, "${file.name}.sha256")
                if (!sha256File.exists()) {
                    val sha256 = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    sha256File.writeText(sha256)
                    checksumCount++
                }

                // Generate SHA512
                val sha512File = java.io.File(file.parentFile, "${file.name}.sha512")
                if (!sha512File.exists()) {
                    val sha512 = java.security.MessageDigest.getInstance("SHA-512")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    sha512File.writeText(sha512)
                    checksumCount++
                }
            }
        }
        logger.lifecycle("Generated $checksumCount checksum files. Full Maven ZIP generated at: ${archiveFile.get().asFile.absolutePath}")
    }
}