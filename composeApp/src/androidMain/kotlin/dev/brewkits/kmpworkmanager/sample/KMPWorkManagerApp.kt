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
import dev.brewkits.kmpworkmanager.workers.builtins.FileCompressionWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpDownloadWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpRequestWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpSyncWorker
import dev.brewkits.kmpworkmanager.workers.builtins.HttpUploadWorker
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.dsl.module

/**
 * Wraps a common [Worker] into an [AndroidWorker] to bridge the type gap.
 * This is necessary because built-in workers implement the common [Worker] interface,
 * while [AndroidWorkerFactory] requires [AndroidWorker] implementations.
 */
class WrapperWorker(private val delegate: Worker) : AndroidWorker {
    override suspend fun doWork(input: String?): WorkerResult {
        return delegate.doWork(input)
    }
}

/**
 * Custom WorkerFactory for the Demo App.
 * It knows how to create both built-in workers from the library and custom workers specific to this sample app.
 */
class DemoWorkerFactory : AndroidWorkerFactory {
    override fun createWorker(workerClassName: String): AndroidWorker? {
        // 1. First, check our library's built-in registry
        val builtinWorker = BuiltinWorkerRegistry.createWorker(workerClassName)
        if (builtinWorker != null) {
            return WrapperWorker(builtinWorker)
        }

        // 2. If not found, check for sample-app-specific custom workers
        // Since the sample workers don't exist as separate classes in this demo,
        // we map the sample names to the built-in workers wrapped in our adapter.
        return when (workerClassName) {
            "SampleUploadWorker" -> WrapperWorker(HttpUploadWorker())
            "SampleDownloadWorker" -> WrapperWorker(HttpDownloadWorker())
            "SampleSyncWorker" -> WrapperWorker(HttpSyncWorker())
            "SampleHeavyWorker" -> WrapperWorker(HttpRequestWorker()) // Map heavy worker to http request for demo
            else -> null // Return null to allow fallback or throw if strictly required
        }
    }
}

/**
 * The main Application class for Android.
 * Responsible for initializing Koin and providing the Android-specific implementations.
 */
class KMPWorkManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize the library's isolated Koin context.
        // This is CRITICAL for the library's internal components (like KmpWorker) to function.
        // It needs our factory to instantiate workers.
        KmpWorkManager.initialize(
            context = this,
            workerFactory = DemoWorkerFactory(),
            config = KmpWorkManagerConfig(
                logLevel = Logger.Level.DEBUG_LEVEL
            )
        )

        val androidModule = module {
            // Provide the BackgroundTaskScheduler implementation
            single<BackgroundTaskScheduler> { NativeTaskScheduler(androidContext()) }
            // Provide the DebugSource implementation for Android
            single<DebugSource> { AndroidDebugSource(androidContext()) }
            // CRITICAL FIX: Provide the missing AndroidWorkerFactory implementation
            single<AndroidWorkerFactory> { DemoWorkerFactory() }
        }

        initKoin {
            androidLogger()
            androidContext(this@KMPWorkManagerApp)
            modules(androidModule)
        }
    }
}
