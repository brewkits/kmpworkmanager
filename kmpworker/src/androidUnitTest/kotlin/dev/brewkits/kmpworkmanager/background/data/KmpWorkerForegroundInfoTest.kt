package dev.brewkits.kmpworkmanager.background.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CI-level (Robolectric, no emulator) regression net for the API < 31 expedited crash.
 *
 * The scheduler marks immediate non-heavy tasks `setExpedited(...)`. On API < 31 an
 * expedited request runs as a foreground service, so WorkManager calls
 * [KmpWorker.getForegroundInfo]. If the override is missing (as it was after the
 * BaseKmpWorker extraction in 1d8135b), the default [androidx.work.CoroutineWorker]
 * implementation throws `IllegalStateException("Not implemented")` and the worker crashes.
 *
 * The instrumented [dev.brewkits.kmpworkmanager.KmpWorkerForegroundInfoCompatTest] also
 * covers this, but only runs on a device/emulator. This unit test runs in plain
 * `testDebugUnitTest` so the regression can never slip through CI again. Pinned to
 * `sdk = 30` (Android 11, API < 31) — the exact range the bug affected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class KmpWorkerForegroundInfoTest {

    private object NoopAndroidFactory : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? = null
    }

    /** Factory that builds the real KmpWorker via its DI constructor (no Koin needed). */
    private class TestFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker = KmpWorker(appContext, workerParameters, NoopAndroidFactory)
    }

    @Test
    fun getForegroundInfo_returnsValidNotification_doesNotThrowNotImplemented() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = TestListenableWorkerBuilder<KmpWorker>(context)
            .setWorkerFactory(TestFactory())
            .build()

        // The crux: the default CoroutineWorker.getForegroundInfo() throws
        // IllegalStateException("Not implemented"). Reaching a non-null ForegroundInfo
        // proves KmpWorker overrides it.
        val info = runBlocking { worker.getForegroundInfo() }

        assertNotNull(info, "KmpWorker must override getForegroundInfo() (regression: API < 31 expedited crash)")
        assertNotNull(info.notification, "ForegroundInfo must carry a fallback notification")
        assertTrue(info.notificationId != 0, "notificationId must be set")
    }
}
