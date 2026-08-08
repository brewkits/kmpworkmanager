package dev.brewkits.kmpworkmanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.TaskEventManager
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression net for the Koin → `AndroidServiceRegistry` swap in 3.3.0 (discussion #66).
 *
 * The deprecated `KmpWorker(appContext, workerParams)` constructor used to resolve its
 * factory via `KmpWorkManagerKoin.getKoin().get()` and relied on Koin throwing
 * [IllegalStateException] when the container was absent, catching it to rethrow an
 * actionable message. `KmpWorkManagerAndroid.requireRegistry()` has to keep throwing the
 * same exception type or that catch silently stops matching and WorkManager surfaces a bare
 * NPE instead of the "call initialize() first" guidance.
 */
@RunWith(RobolectricTestRunner::class)
class V330AndroidRegistryTest {

    private class NoopWorker : AndroidWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
            WorkerResult.Success()
    }

    private class TestAndroidWorkerFactory : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? =
            if (workerClassName == "NoopWorker") NoopWorker() else null
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @After
    fun tearDown() {
        KmpWorkManager.shutdown()
        TaskEventManager.resetForTest()
        KmpWorkManagerRuntime.reset()
    }

    @Test
    fun `requireRegistry throws IllegalStateException with actionable message when not initialized`() {
        // The deprecated worker constructors catch exactly IllegalStateException.
        val error = assertFailsWith<IllegalStateException> {
            KmpWorkManagerAndroid.requireRegistry()
        }
        assertTrue(
            error.message?.contains("KmpWorkManager.initialize()") == true,
            "Message must tell the caller what to do, was: ${error.message}"
        )
    }

    @Test
    fun `registry exposes the factory the caller passed to initialize`() {
        val factory = TestAndroidWorkerFactory()
        KmpWorkManager.initialize(context = context, workerFactory = factory)

        assertSame(factory, KmpWorkManagerAndroid.requireRegistry().androidWorkerFactory)
    }

    @Test
    fun `persistence stores are created eagerly and published to the runtime hooks`() {
        KmpWorkManager.initialize(context = context, workerFactory = TestAndroidWorkerFactory())

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
    fun `services are singletons - repeated access returns the same instance`() {
        KmpWorkManager.initialize(context = context, workerFactory = TestAndroidWorkerFactory())

        val first = KmpWorkManager.getInstance()
        val second = KmpWorkManager.getInstance()

        assertSame(first.backgroundTaskScheduler, second.backgroundTaskScheduler)
        assertSame(first.eventStore, second.eventStore)
    }
}
