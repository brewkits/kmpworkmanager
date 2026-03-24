package dev.brewkits.kmpworkmanager.sample

import android.app.Application
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.KmpWorkManagerConfig
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import dev.brewkits.kmpworkmanager.sample.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.debug.AndroidDebugSource
import dev.brewkits.kmpworkmanager.sample.debug.DebugSource
import dev.brewkits.kmpworkmanager.sample.di.initKoin
import dev.brewkits.kmpworkmanager.utils.Logger
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
    override suspend fun doWork(input: String?): WorkerResult = delegate.doWork(input)
}

/**
 * Worker factory for the Demo App.
 * Checks built-in workers first, then falls back to sample-specific worker names.
 */
class DemoWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        val builtinWorker = BuiltinWorkerRegistry.createWorker(workerClassName)
        if (builtinWorker != null) return WrapperWorker(builtinWorker)

        return when (workerClassName) {
            "SampleUploadWorker" -> WrapperWorker(dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker())
            "SampleDownloadWorker" -> WrapperWorker(dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker())
            "SampleSyncWorker" -> WrapperWorker(dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker())
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
            config = KmpWorkManagerConfig(logLevel = Logger.Level.DEBUG_LEVEL)
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
