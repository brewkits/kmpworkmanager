package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Invariant tests for the Koin-free init path introduced in 3.3.0 (discussion #66).
 *
 * These assert *wiring behaviour*, not just that the replacement compiles. The old
 * `kmpWorkerModule()` was a Koin module whose `single { }` bindings guaranteed
 * single-instance-per-container semantics and — for the persistence stores — ran global
 * registration side effects on resolution. `IosServiceRegistry` has to reproduce that, and
 * the tests below are what stops a future refactor from quietly regressing it.
 *
 * **Do not touch `backgroundTaskScheduler` from this class.** Resolving it constructs
 * `NativeTaskScheduler`, whose `init` launches a `StorageMigration` job on a long-lived
 * `SupervisorJob() + IosDispatchers.IO` scope against the real Application Support
 * directory. That job outlives the test method and logs after it finishes, which makes
 * Gradle fail the whole task with "Could not write XML test results" for whichever
 * concurrency/stress classes happen to run next. Verified: adding a single
 * `backgroundTaskScheduler` access here breaks `IosStorageStressTest`,
 * `QA_PersistenceResilienceTest`, `QA_IosChainReplaceConcurrencyTest` and
 * `V250ReplaceChainMutexRaceTest`. The lazy-singleton semantics under test are identical
 * for every property in the registry, so asserting them on the cheap services proves the
 * same thing without the migration side effect.
 */
class V330KoinFreeInitTest {

    private class NoopWorker : IosWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
            WorkerResult.Success()
    }

    private class TestIosWorkerFactory : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker? =
            if (workerClassName == "NoopWorker") NoopWorker() else null
    }

    /** Deliberately not an [IosWorkerFactory] — used for the fail-fast test. */
    private class WrongPlatformFactory : WorkerFactory {
        override fun createWorker(workerClassName: String): Worker? = null
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
    fun `getInstance before initialize fails with an actionable message`() {
        val error = assertFailsWith<IllegalStateException> { KmpWorkManager.getInstance() }
        assertTrue(
            error.message?.contains("KmpWorkManager.initialize()") == true,
            "Message should tell the caller what to do, was: ${error.message}"
        )
    }

    @Test
    fun `initialize rejects a factory that is not an IosWorkerFactory`() {
        // Koin's module block did this with `require(workerFactory is IosWorkerFactory)` at
        // module-construction time. The check must stay eager, not deferred to first use.
        assertFailsWith<IllegalArgumentException> {
            KmpWorkManager.initialize(workerFactory = WrongPlatformFactory())
        }
        assertFalse(KmpWorkManager.isInitialized(), "A rejected init must not leave state behind")
    }

    @Test
    fun `services are singletons - repeated access returns the same instance`() {
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())

        val first = KmpWorkManager.getInstance()
        val second = KmpWorkManager.getInstance()

        // KmpWorkManagerInstance is a thin accessor and may differ; the services must not.
        assertSame(first.singleTaskExecutor, second.singleTaskExecutor)
        assertSame(first.eventStore, second.eventStore)
    }

    @Test
    fun `persistence stores are created eagerly and published to the runtime hooks`() {
        // REGRESSION GUARD: under the Koin module these were plain lazy `single { }` bindings
        // on iOS, and nothing in the library ever resolved them — so
        // KmpWorkManagerRuntime.executionHistoryStore stayed null and every execution record
        // was silently dropped (workers read it via `?.save(record)`).
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())

        assertNotNull(
            KmpWorkManagerRuntime.executionHistoryStore,
            "ExecutionHistoryStore must be registered during initialize(), not on first access"
        )
        // Not merely non-null: TaskEventManager must hold the *same* store the registry
        // hands out, or events would be persisted to a second, unrelated instance.
        assertSame(
            KmpWorkManager.getInstance().eventStore,
            TaskEventManager.currentStoreForTest(),
            "TaskEventManager must be wired to the registry's EventStore during initialize()"
        )
    }

    @Test
    fun `duplicate initialize is ignored and does not swap live instances`() {
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        val executorBefore = KmpWorkManager.getInstance().singleTaskExecutor

        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        val executorAfter = KmpWorkManager.getInstance().singleTaskExecutor

        // A second init must not hand a different instance to code that already captured one.
        assertSame(executorBefore, executorAfter)
    }

    @Test
    fun `re-initialize after shutdown re-points the global TaskEventManager`() {
        // TaskEventManager.initialize() is compare-and-set "first call wins", so if
        // shutdown() does not release the claim, the second initialize() silently keeps the
        // dead registry's store and every event is written through an instance the live
        // registry no longer owns.
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        val firstStore = KmpWorkManager.getInstance().eventStore

        KmpWorkManager.shutdown()
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        val secondStore = KmpWorkManager.getInstance().eventStore

        assertTrue(firstStore !== secondStore, "a fresh registry must build a fresh store")
        assertSame(
            secondStore,
            TaskEventManager.currentStoreForTest(),
            "TaskEventManager must follow the live registry across shutdown/re-initialize"
        )
        assertNotNull(
            KmpWorkManagerRuntime.executionHistoryStore,
            "ExecutionHistoryStore must be re-registered by the second initialize()"
        )
    }

    @Test
    fun `shutdown releases the global hooks so a torn-down registry stops receiving records`() {
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        assertNotNull(KmpWorkManagerRuntime.executionHistoryStore)

        KmpWorkManager.shutdown()

        assertNull(
            KmpWorkManagerRuntime.executionHistoryStore,
            "shutdown() must drop the history store, not leave a dead registry wired up"
        )
        assertNull(
            TaskEventManager.currentStoreForTest(),
            "shutdown() must release the TaskEventManager claim"
        )
    }

    @Test
    fun `shutdown clears state so a later initialize builds a fresh graph`() {
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        val before = KmpWorkManager.getInstance().singleTaskExecutor

        KmpWorkManager.shutdown()
        assertFalse(KmpWorkManager.isInitialized())

        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory())
        val after = KmpWorkManager.getInstance().singleTaskExecutor

        assertTrue(before !== after, "shutdown() must not leave the old graph reachable")
    }
}
