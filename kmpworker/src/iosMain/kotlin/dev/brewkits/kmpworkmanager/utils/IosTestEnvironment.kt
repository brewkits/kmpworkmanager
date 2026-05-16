@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brewkits.kmpworkmanager.utils

import platform.Foundation.NSProcessInfo

/**
 * Single source of truth for "are we running inside a test harness on iOS?".
 *
 * Why this exists — v2.5.0 QA review caught that the codebase had ~four copies of the
 * `processName.endsWith("test.kexe")` heuristic scattered across `IosFileCoordinator`,
 * `IosFileStorage`, and `NativeTaskScheduler`. That heuristic is fragile in two ways:
 *
 *  1. **Kotlin/Native naming convention changes** — JetBrains has rebranded build
 *     outputs before (e.g. `*.kexe` was preceded by other conventions in K/N 1.x). A
 *     future Kotlin 2.x rename would silently break every test that relies on the
 *     library's test-mode short-circuits, without a compile error.
 *  2. **Copy-paste drift** — multiple inline copies meant a bug fix in one copy (e.g.
 *     adding the `KMPWORKMANAGER_TEST_MODE` env var override) had to be replicated
 *     manually in the others, with no enforcement.
 *
 * This object collapses the four copies into one, layered detection cascade. Order
 * matters: cheapest + most explicit first; the fragile suffix check is the last
 * fallback. Callers should prefer explicit `isTestMode` constructor parameters where
 * the type allows; this detector is the implicit-default backstop.
 *
 * **Signals checked, in order:**
 *
 *  | Priority | Signal | Source |
 *  |---|---|---|
 *  | 1 | `KMPWORKMANAGER_TEST_MODE=1` env var | Library users' test runners (gradle.properties) |
 *  | 2 | `XCTestConfigurationFilePath` env var | Xcode + XCTest harness |
 *  | 3 | `XCInjectBundleInto` env var | XCTest harness (alt signal) |
 *  | 4 | `processName.endsWith("test.kexe")` | Kotlin/Native test runner (`./gradlew :…:iosSimulatorArm64Test`) |
 *
 * Any one signal → test environment. Cached at first access via `by lazy` because
 * environment variables and process name are immutable for the process lifetime.
 *
 * **For library consumers**: if you need to force the library into test mode from an
 * integration test, set the env var:
 * ```
 * environment.set("KMPWORKMANAGER_TEST_MODE", "1")
 * ```
 * in your test task's Gradle config. This is more robust than relying on the
 * library's own heuristics.
 */
internal object IosTestEnvironment {

    /**
     * `true` when any test-environment signal is present. Computed once per process and
     * cached.
     *
     * **Logical safety guarantee**: every signal source is immutable for the process
     * lifetime — env vars set before `main`, process name set at exec. Caching is
     * therefore correctness-preserving, not just an optimisation.
     */
    val isTestEnvironment: Boolean by lazy { detect() }

    private fun detect(): Boolean {
        val env = NSProcessInfo.processInfo.environment

        // 1. Explicit opt-in env var. The clearest signal — never overridden by other heuristics.
        val explicit = env["KMPWORKMANAGER_TEST_MODE"] as? String
        if (explicit == "1" || explicit?.equals("true", ignoreCase = true) == true) {
            return true
        }

        // 2. Xcode / XCTest harness signals. Set by Apple's test runner; reliable across
        // Xcode versions because they're part of the public XCTest contract.
        if (env.containsKey("XCTestConfigurationFilePath")) return true
        if (env.containsKey("XCInjectBundleInto")) return true

        // 3. Kotlin/Native test runner naming convention. Fragile but currently accurate
        // for `./gradlew :kmpworker:iosSimulatorArm64Test`. If JetBrains renames the
        // output binary in a future Kotlin release, callers should fall back to signal 1.
        if (NSProcessInfo.processInfo.processName.endsWith("test.kexe")) return true

        return false
    }
}
