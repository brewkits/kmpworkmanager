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