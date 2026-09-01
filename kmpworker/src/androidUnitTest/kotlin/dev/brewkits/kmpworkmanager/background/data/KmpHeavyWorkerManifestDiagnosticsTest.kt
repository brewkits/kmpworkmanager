package dev.brewkits.kmpworkmanager.background.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertTrue

/**
 * Coverage for #82's revised scope: [KmpHeavyWorker] reading the app's actual merged
 * `android:foregroundServiceType` manifest declaration for diagnostics.
 *
 * Background: pre-fix, `KmpHeavyWorker`'s `setForeground()` exception handler named only
 * what the worker itself declared via [KmpHeavyWorker.foregroundServiceType] — a developer
 * debugging a mismatch had to separately go read the manifest to compare. Below API 34
 * (`ForegroundServiceTypeException` is API 34+), a mismatch doesn't throw at ALL, so nothing
 * surfaced it. See `docs/ROADMAP.md` "Android FGS-type diagnosability".
 *
 * `SystemForegroundService` (from `androidx.work`) is always present in a real merged
 * manifest — apps depending on `work-runtime` get it automatically — but Robolectric's
 * `ShadowPackageManager` doesn't parse the dependency AAR's manifest into its package
 * registry, so each test registers it explicitly via `addOrUpdateService` to simulate
 * exactly what a real device sees.
 */
@RunWith(RobolectricTestRunner::class)
class KmpHeavyWorkerManifestDiagnosticsTest {

    private object NoopAndroidFactory : AndroidWorkerFactory {
        override fun createWorker(workerClassName: String): AndroidWorker? = SuccessWorker
    }

    private object SuccessWorker : AndroidWorker {
        override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
            WorkerResult.Success("ok")
    }

    private class TestFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker = KmpHeavyWorker(appContext, workerParameters, NoopAndroidFactory)
    }

    private fun registerSystemForegroundService(context: Context, foregroundServiceType: Int) {
        val info = ServiceInfo().apply {
            packageName = context.packageName
            name = "androidx.work.impl.foreground.SystemForegroundService"
        }
        // ServiceInfo.foregroundServiceType has only a public getter on the compile-time SDK
        // stub — there's no public setter/field to assign directly. The Robolectric-
        // instrumented runtime class backs it with a public field named `mForegroundServiceType`
        // (not `foregroundServiceType`), so it's set via reflection here, matching what a real
        // manifest merge into a `ServiceInfo` would ultimately populate.
        val field = ServiceInfo::class.java.getDeclaredField("mForegroundServiceType")
        field.isAccessible = true
        field.setInt(info, foregroundServiceType)
        Shadows.shadowOf(context.packageManager).addOrUpdateService(info)
    }

    private fun buildWorker(context: Context): KmpHeavyWorker =
        TestListenableWorkerBuilder<KmpHeavyWorker>(context)
            .setWorkerFactory(TestFactory())
            // BaseKmpWorker.doWorkInternal() reads workerClassName from inputData and
            // returns Result.failure() immediately if it's absent — unrelated to anything
            // this test covers, but required for doWork() to reach the FGS code at all.
            .setInputData(Data.Builder().putString("workerClassName", "SuccessWorker").build())
            .build()

    @Config(sdk = [30]) // API 30: within 29-33, no ForegroundServiceTypeException exists
    @Test
    fun api29to33_mismatchedManifestType_logsProactiveWarning() {
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Worker declares dataSync (KmpHeavyWorker's default) but the manifest says camera.
        registerSystemForegroundService(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        val worker = buildWorker(context)

        runBlocking { worker.doWork() }

        val warned = ShadowLog.getLogs().any {
            it.msg.contains("foregroundServiceType mismatch") && it.msg.contains("does NOT throw")
        }
        assertTrue(warned, "expected a proactive mismatch warning on API 29-33 where nothing else surfaces this")
    }

    @Config(sdk = [30])
    @Test
    fun api29to33_matchingManifestType_noWarningLogged() {
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        registerSystemForegroundService(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val worker = buildWorker(context)

        runBlocking { worker.doWork() }

        val warned = ShadowLog.getLogs().any { it.msg.contains("foregroundServiceType mismatch") }
        assertTrue(!warned, "a matching manifest declaration must not trigger the mismatch warning")
    }

    @Config(sdk = [30])
    @Test
    fun api29to33_multiTypeManifestContainingWorkersType_noFalsePositive() {
        // docs/ANDROID_FGS_GUIDE.md's own recommended pattern for a shared <service>: OR
        // several types together, e.g. android:foregroundServiceType="dataSync|camera", so
        // one service backs workers of different declared types. The manifest value here is
        // NOT equal to the worker's single declared type, but DOES contain it as a bit — this
        // must NOT warn. This is the exact false-positive an equality check would produce.
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val multiType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        registerSystemForegroundService(context, multiType)
        val worker = buildWorker(context) // declares DATA_SYNC (KmpHeavyWorker's default)

        runBlocking { worker.doWork() }

        val warned = ShadowLog.getLogs().any { it.msg.contains("foregroundServiceType mismatch") }
        assertTrue(!warned, "worker's declared type IS one of the manifest's OR'd bits — must not warn")
    }

    @Config(sdk = [30])
    @Test
    fun api29to33_manifestDeclaresNoType_noFalsePositive() {
        // A manifest that never sets android:foregroundServiceType reads back as 0
        // (FOREGROUND_SERVICE_TYPE_NONE / the "manifest" default) — common for apps that
        // predate this feature or haven't adopted it. That's a real gap worth fixing, but
        // it isn't a "worker declared X, manifest declared Y" MISMATCH — the diagnostic
        // must not conflate "not configured" with "configured wrong."
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        registerSystemForegroundService(context, 0)
        val worker = buildWorker(context)

        runBlocking { worker.doWork() }

        val warned = ShadowLog.getLogs().any { it.msg.contains("foregroundServiceType mismatch") }
        assertTrue(!warned, "manifest type=0 (unset) must not be reported as a mismatch")
    }

    @Config(sdk = [34]) // API 34+: mismatch throws ForegroundServiceTypeException — no proactive warning needed
    @Test
    fun api34Plus_mismatchedManifestType_noProactiveWarning_relyOnException() {
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        registerSystemForegroundService(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        val worker = buildWorker(context)

        runBlocking { worker.doWork() }

        // The proactive warning is scoped to API 29-33 only — API 34+ has its own throw path
        // (covered by the pre-existing setForeground() catch block), so this must NOT also
        // fire the same warning; that would be a redundant/confusing double-signal.
        val warned = ShadowLog.getLogs().any { it.msg.contains("foregroundServiceType mismatch") }
        assertTrue(!warned, "API 34+ has its own exception path — the proactive warning must not duplicate it")
    }

    @Config(sdk = [30])
    @Test
    fun manifestReadFailure_neverThrows_workerStillProceeds() {
        // Deliberately do NOT register SystemForegroundService — simulates a
        // NameNotFoundException from getServiceInfo(). Must degrade to "unknown" (null),
        // never propagate as a worker failure.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = buildWorker(context)

        val result = runBlocking { worker.doWork() }

        // ListenableWorker.Result has no public equals-friendly subtype check via kotlin.test
        // assertIs is avoided to keep this test independent of androidx.work internals beyond
        // what's already used elsewhere in this suite — string form is asserted instead.
        assertTrue(
            result.toString().contains("Success", ignoreCase = true),
            "a manifest read failure must not fail the worker — got: $result"
        )
    }

    @Config(sdk = [28]) // Below API 29: ServiceInfo.foregroundServiceType doesn't exist yet
    @Test
    fun belowApi29_neverAttemptsManifestRead_doesNotCrash_noWarning() {
        ShadowLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // No SystemForegroundService registered — if the below-Q guard were missing, calling
        // getServiceInfo() here would hit the same NameNotFoundException path exercised by
        // manifestReadFailure_neverThrows_workerStillProceeds above. This test instead pins
        // that the guard skips the attempt entirely on this API level.
        val worker = buildWorker(context)

        val result = runBlocking { worker.doWork() }

        assertTrue(result.toString().contains("Success", ignoreCase = true))
        val warned = ShadowLog.getLogs().any { it.msg.contains("foregroundServiceType mismatch") }
        assertTrue(!warned, "the proactive warning is scoped to API 29+ (ServiceInfo.foregroundServiceType doesn't exist below it)")
    }
}
