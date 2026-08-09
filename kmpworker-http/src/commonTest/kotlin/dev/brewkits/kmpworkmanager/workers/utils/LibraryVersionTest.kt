package dev.brewkits.kmpworkmanager.workers.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression net for a review finding: `HttpClientProvider`'s User-Agent header hardcoded
 * `"KmpWorkManager/2.3.4"` in both `HttpClientProvider.android.kt` and
 * `HttpClientProvider.ios.kt`, un-synced with the actual published version across 13
 * subsequent releases (still reading 2.3.4 at 3.3.1).
 *
 * `LIBRARY_VERSION` is generated at build time by `:kmpworker-http`'s
 * `generateLibraryVersion` Gradle task, reading `project.version` (VERSION_NAME) — see
 * `kmpworker-http/build.gradle.kts`. Merely being able to reference [LIBRARY_VERSION] from
 * this common test source set already proves the generated-source wiring compiles; the
 * assertions below pin the two properties that actually matter: it looks like a real
 * version (not an empty/placeholder string), and it is not the literal stale value that
 * caused this to be filed in the first place.
 */
class LibraryVersionTest {

    @Test
    fun `LIBRARY_VERSION looks like a semantic version not empty or a placeholder`() {
        assertTrue(
            Regex("""\d+\.\d+\.\d+.*""").matches(LIBRARY_VERSION),
            "expected a semver-shaped string, got: '$LIBRARY_VERSION'"
        )
    }

    @Test
    fun `LIBRARY_VERSION is not the stale hardcoded value this fix removed`() {
        assertFalse(
            LIBRARY_VERSION == "2.3.4",
            "LIBRARY_VERSION must track the real build version, not the literal that was " +
                "hardcoded in HttpClientProvider for 13 releases"
        )
    }
}
