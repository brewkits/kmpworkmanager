package dev.brewkits.kmpworkmanager

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Opt-in gate for tests that are correct but too sensitive to machine load to run everywhere.
 *
 * **Why this exists instead of `@Ignore`.** `@Ignore` removes a test from the suite
 * permanently and silently: nothing reports it, nothing can turn it back on, and the coverage
 * it represented simply stops existing — which is how `WorkerProcessorTest`'s 23 tests sat
 * unrun for several releases. A gated test still compiles against the current API (so it
 * cannot rot), announces itself when it skips, and one environment variable brings it back.
 * `QueueScaleStressTest`'s KDoc proposed exactly this policy; this is the implementation.
 *
 * Run them with:
 * ```
 * KMP_RUN_STRESS_TESTS=1 ./gradlew :kmpworker:iosSimulatorArm64Test
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal object StressTests {

    val enabled: Boolean by lazy {
        val raw = getenv("KMP_RUN_STRESS_TESTS")?.toKString()?.lowercase()
        raw == "1" || raw == "true" || raw == "yes"
    }

    /**
     * Returns `true` when the caller should bail out, printing why so a skip is visible in the
     * test log rather than passing as a silent success.
     */
    fun skip(testName: String): Boolean {
        if (enabled) return false
        println("⏭️  STRESS TEST SKIPPED: $testName — set KMP_RUN_STRESS_TESTS=1 to run it")
        return true
    }
}
