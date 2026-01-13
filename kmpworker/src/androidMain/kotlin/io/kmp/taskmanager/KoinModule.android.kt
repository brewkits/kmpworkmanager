package io.kmp.taskmanager

import android.content.Context
import io.kmp.taskmanager.background.data.NativeTaskScheduler
import io.kmp.taskmanager.background.domain.AndroidWorkerFactory
import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.background.domain.WorkerFactory
import org.koin.dsl.module

/**
 * Android implementation of the Koin module.
 *
 * v4.0.0+ Breaking Change: Now requires WorkerFactory parameter
 *
 * Usage:
 * ```kotlin
 * startKoin {
 *     androidContext(this@Application)
 *     modules(kmpTaskManagerModule(
 *         workerFactory = MyWorkerFactory()
 *     ))
 * }
 * ```
 *
 * @param workerFactory User-provided factory implementing AndroidWorkerFactory
 * @param iosTaskIds Ignored on Android (iOS-only parameter)
 */
actual fun kmpTaskManagerModule(
    workerFactory: WorkerFactory,
    iosTaskIds: Set<String>
) = module {
    single<BackgroundTaskScheduler> {
        val context = get<Context>()
        NativeTaskScheduler(context)
    }

    // Register the user's worker factory
    single<WorkerFactory> { workerFactory }
    single<AndroidWorkerFactory> {
        workerFactory as? AndroidWorkerFactory
            ?: error("WorkerFactory must implement AndroidWorkerFactory on Android")
    }
}
