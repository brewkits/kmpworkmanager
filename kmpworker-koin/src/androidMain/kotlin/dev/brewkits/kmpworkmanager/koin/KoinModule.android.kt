package dev.brewkits.kmpworkmanager.koin

import android.content.Context
import dev.brewkits.kmpworkmanager.WorkerManagerInitializer
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import org.koin.dsl.module

/**
 * Android implementation of the Koin module for KMP WorkManager.
 *
 * This module uses WorkerManagerInitializer to set up the scheduler,
 * maintaining backward compatibility with v2.0.0 while using the new DI-agnostic core.
 *
 * Usage:
 * ```kotlin
 * startKoin {
 *     androidContext(this@Application)
 *     modules(kmpWorkerModule(
 *         workerFactory = MyWorkerFactory()
 *     ))
 * }
 * ```
 *
 * @param workerFactory User-provided factory implementing AndroidWorkerFactory
 * @param iosTaskIds Ignored on Android (iOS-only parameter)
 * @since 2.1.0
 */
actual fun kmpWorkerModule(
    workerFactory: WorkerFactory,
    iosTaskIds: Set<String>
) = module {
    single<BackgroundTaskScheduler> {
        val context = get<Context>()

        // Use WorkerManagerInitializer to set up everything
        WorkerManagerInitializer.initialize(
            workerFactory = workerFactory,
            context = context
        )
    }

    // Register the user's worker factory for direct injection if needed
    single<WorkerFactory> { workerFactory }
    single<AndroidWorkerFactory> {
        workerFactory as? AndroidWorkerFactory
            ?: error("WorkerFactory must implement AndroidWorkerFactory on Android")
    }
}
