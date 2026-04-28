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
// stdlib 2.3.0 klib which is ABI-incompatible with the KGP 2.1.0 compiler and
// causes "Could not find kotlin-stdlib-2.3.0-commonMain-*.klib" build errors.
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

// Task to generate a full Maven Central distribution ZIP
tasks.register<Zip>("generateFullMavenZip") {
    group = "publishing"
    description = "Generates a ZIP file containing all Maven artifacts and checksums for manual upload"

    val versionName = (rootProject.findProperty("VERSION_NAME") as? String) ?: System.getenv("VERSION_NAME") ?: "0.0.0-SNAPSHOT"
    val stagingDir = layout.buildDirectory.dir("maven-central-staging")
    archiveFileName.set("kmpworkmanager-maven-central-$versionName.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(stagingDir)

    // Ensure all modules are published to local staging first
    dependsOn(":kmpworker:publishAllPublicationsToMavenCentralLocalRepository")
    dependsOn(":kmpworker-annotations:publishAllPublicationsToMavenCentralLocalRepository")
    dependsOn(":kmpworker-ksp:publishAllPublicationsToMavenCentralLocalRepository")
    
    // Checksums are handled by each module or a global step
    doLast {
        val stagingFile = stagingDir.get().asFile
        if (!stagingFile.exists()) return@doLast

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