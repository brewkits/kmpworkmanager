package dev.brewkits.kmpworkmanager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestGuardTest {

    @Test
    fun testLibraryManifestDoesNotExportSensitivePermissions() {
        // Run from kmpworker directory
        val manifestFile = File("src/androidMain/AndroidManifest.xml")
        
        // Fallback for when test is run from project root
        val actualFile = if (manifestFile.exists()) {
            manifestFile
        } else {
            File("kmpworker/src/androidMain/AndroidManifest.xml")
        }

        assertTrue(actualFile.exists(), "Could not find AndroidManifest.xml at ${actualFile.absolutePath}")

        val manifestContent = actualFile.readText()

        val sensitivePermissions = listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
            "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        )

        sensitivePermissions.forEach { permission ->
            assertFalse(
                manifestContent.contains(permission),
                "Library AndroidManifest.xml MUST NOT export sensitive permission: $permission. " +
                        "This must be opt-in for the consumer app to avoid Play Store rejection."
            )
        }
        
        // Also ensure we don't declare a hardcoded foreground service type that could trigger Play Store bots
        assertFalse(
            manifestContent.contains("android:foregroundServiceType="),
            "Library AndroidManifest.xml MUST NOT declare default foregroundServiceType. " +
                    "This must be declared by the consumer app."
        )
    }
}
