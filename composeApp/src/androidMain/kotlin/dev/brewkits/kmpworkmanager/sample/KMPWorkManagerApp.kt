package dev.brewkits.kmpworkmanager.sample

import android.app.Application
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.KmpWorkManagerConfig
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.debug.AndroidDebugSource
import dev.brewkits.kmpworkmanager.sample.debug.DebugSource
import dev.brewkits.kmpworkmanager.sample.di.initKoin
import dev.brewkits.kmpworkmanager.background.domain.TelemetryHook
import dev.brewkits.kmpworkmanager.utils.Logger
import dev.brewkits.kmpworkmanager.sample.background.workers.AnalyticsAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.BatchUploadAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.CleanupAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.DatabaseAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.HeavyProcessingAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.ImageProcessingAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.LocationSyncAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.NetworkRetryAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.SyncAndroidWorker
import dev.brewkits.kmpworkmanager.sample.background.workers.UploadAndroidWorker
import dev.brewkits.kmpworkmanager.workers.BuiltinWorkerRegistry
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.dsl.module

/**
 * Wraps a common [Worker] into an [AndroidWorker] to bridge the type gap.
 * Built-in workers implement the common [Worker] interface, while [AndroidWorkerFactory]
 * requires [AndroidWorker] implementations.
 */
private class WrapperWorker(private val delegate: Worker) : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult = delegate.doWork(input, env)
}

/**
 * Worker factory for the Demo App.
 * Checks built-in workers first, then falls back to sample-specific worker names.
 */
class DemoWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        // 1. Built-in library workers (HttpUploadWorker, HttpDownloadWorker, etc.)
        val builtinWorker = BuiltinWorkerRegistry.createWorker(workerClassName)
        if (builtinWorker != null) return WrapperWorker(builtinWorker)

        // 2. Sample app workers — mapped by FQN (as defined in WorkerTypes)
        return when (workerClassName) {
            "dev.brewkits.kmpworkmanager.sample.background.workers.SyncWorker" -> SyncAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.UploadWorker" -> UploadAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.HeavyProcessingWorker" -> HeavyProcessingAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.DatabaseWorker" -> DatabaseAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.NetworkRetryWorker" -> NetworkRetryAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.ImageProcessingWorker" -> ImageProcessingAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.LocationSyncWorker" -> LocationSyncAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.CleanupWorker" -> CleanupAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.BatchUploadWorker" -> BatchUploadAndroidWorker()
            "dev.brewkits.kmpworkmanager.sample.background.workers.AnalyticsWorker" -> AnalyticsAndroidWorker()
            else -> null
        }
    }
}

/**
 * The main Application class for Android.
 * Responsible for initializing the library and Koin.
 */
class KMPWorkManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize the library's isolated Koin context with a factory that can create
        // both built-in workers (HttpUploadWorker, HttpDownloadWorker, etc.) and sample workers.
        KmpWorkManager.initialize(
            context = this,
            workerFactory = DemoWorkerFactory(),
            config = KmpWorkManagerConfig(
                logLevel = Logger.Level.DEBUG_LEVEL,
                telemetryHook = object : TelemetryHook {
                    override fun onTaskStarted(event: TelemetryHook.TaskStartedEvent) {
                        Logger.d("DEMO_TELEMETRY", "▶ Task started: ${event.taskName} chain=${event.chainId} step=${event.stepIndex}")
                    }
                    override fun onTaskCompleted(event: TelemetryHook.TaskCompletedEvent) {
                        Logger.d("DEMO_TELEMETRY", "✅ Task completed: ${event.taskName} success=${event.success} duration=${event.durationMs}ms")
                    }
                    override fun onTaskFailed(event: TelemetryHook.TaskFailedEvent) {
                        Logger.w("DEMO_TELEMETRY", "❌ Task failed: ${event.taskName} error=${event.error} retry=${event.retryCount}")
                    }
                    override fun onChainCompleted(event: TelemetryHook.ChainCompletedEvent) {
                        Logger.d("DEMO_TELEMETRY", "🏁 Chain completed: ${event.chainId} steps=${event.totalSteps} duration=${event.durationMs}ms")
                    }
                    override fun onChainFailed(event: TelemetryHook.ChainFailedEvent) {
                        Logger.w("DEMO_TELEMETRY", "💥 Chain failed: ${event.chainId} step=${event.failedStep} willRetry=${event.willRetry}")
                    }
                    override fun onChainSkipped(event: TelemetryHook.ChainSkippedEvent) {
                        Logger.d("DEMO_TELEMETRY", "⏭ Chain skipped: ${event.chainId} reason=${event.reason}")
                    }
                }
            )
        )

        val androidModule = module {
            single<BackgroundTaskScheduler> { NativeTaskScheduler(androidContext()) }
            single<DebugSource> { AndroidDebugSource(androidContext()) }
            single<AndroidWorkerFactory> { DemoWorkerFactory() }
        }

        initKoin {
            androidLogger()
            androidContext(this@KMPWorkManagerApp)
            modules(androidModule)
        }
    }
}
