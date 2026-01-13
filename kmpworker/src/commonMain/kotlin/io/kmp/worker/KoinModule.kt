package io.kmp.worker

import io.kmp.worker.background.domain.BackgroundTaskScheduler
import io.kmp.worker.background.domain.WorkerFactory
import org.koin.dsl.module

/**
 * Koin dependency injection module for KMP Worker.
 *
 * v4.0.0+ Breaking Change: Now requires WorkerFactory parameter
 *
 * Usage in your app:
 * ```kotlin
 * startKoin {
 *     androidContext(this@Application)  // Android only
 *     modules(kmpWorkerModule(
 *         workerFactory = MyWorkerFactory()
 *     ))
 * }
 * ```
 */

/**
 * Creates a Koin module for KMP Worker with platform-specific scheduler and worker factory.
 *
 * v4.0.0+ Breaking Change: Now requires WorkerFactory parameter
 *
 * @param workerFactory User-provided factory for creating worker instances
 * @param iosTaskIds (iOS only) Additional task IDs for iOS BGTaskScheduler. Ignored on Android.
 */
expect fun kmpWorkerModule(
    workerFactory: WorkerFactory,
    iosTaskIds: Set<String> = emptySet()
): org.koin.core.module.Module

/**
 * Common module definition for direct use (advanced usage)
 */
fun kmpWorkerCoreModule(
    scheduler: BackgroundTaskScheduler,
    workerFactory: WorkerFactory
) = module {
    single<BackgroundTaskScheduler> { scheduler }
    single<WorkerFactory> { workerFactory }
}
