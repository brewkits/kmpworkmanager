package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the 3.4.0 App Group storage seam:
 * `KmpWorkManager.initialize(appGroupIdentifier = ...)`.
 *
 * **Do not touch `backgroundTaskScheduler` from this class** — see `V330KoinFreeInitTest`'s
 * class KDoc for why (constructing `NativeTaskScheduler` launches a long-lived migration job
 * that outlives the test method and corrupts unrelated tests' XML reporting). The App Group
 * container is resolved eagerly in `IosServiceRegistry`'s constructor regardless of which
 * service is later accessed, so these tests don't need to touch it either.
 *
 * **What we cannot test here**: the success path with a real App Group container — that
 * requires an actual App Group entitlement wired into the test target's provisioning, which
 * this unit-test harness does not have. `containerURLForSecurityApplicationGroupIdentifier`
 * reliably returns `nil` for any identifier in this environment, which is exactly the
 * fail-fast path these tests pin.
 */
class V340AppGroupInitTest {

    private class NoopWorker : IosWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
            WorkerResult.Success()
    }

    private class TestIosWorkerFactory : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker? =
            if (workerClassName == "NoopWorker") NoopWorker() else null
    }

    @BeforeTest
    fun setUp() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @AfterTest
    fun tearDown() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @Test
    fun `initialize with no appGroupIdentifier behaves exactly as before`() {
        // Default null must not attempt any App Group resolution at all.
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        assertTrue(KmpWorkManager.isInitialized())
    }

    @Test
    fun `initialize with an unconfigured appGroupIdentifier fails fast with an actionable message`() {
        // No App Group entitlement exists in this test target, so this must always resolve
        // to nil in this environment — pinning the fail-fast (not silent-fallback) contract.
        val error = assertFailsWith<IllegalArgumentException> {
            KmpWorkManager.initialize(
                workerFactory = TestIosWorkerFactory(),
                appGroupIdentifier = "group.dev.brewkits.kmpworkmanager.nonexistent"
            )
        }
        assertTrue(
            error.message?.contains("App Group") == true,
            "Message must name the actual problem, was: ${error.message}"
        )
        assertTrue(
            error.message?.contains("group.dev.brewkits.kmpworkmanager.nonexistent") == true,
            "Message must echo the identifier the caller passed, was: ${error.message}"
        )
    }

    @Test
    fun `a failed appGroupIdentifier init must not leave state behind`() {
        assertFailsWith<IllegalArgumentException> {
            KmpWorkManager.initialize(
                workerFactory = TestIosWorkerFactory(),
                appGroupIdentifier = "group.dev.brewkits.kmpworkmanager.nonexistent"
            )
        }
        assertFalse(
            KmpWorkManager.isInitialized(),
            "A rejected App Group init must not register a half-built registry"
        )

        // A subsequent normal initialize() must still work cleanly.
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        assertTrue(KmpWorkManager.isInitialized())
    }
}
