package dev.brewkits.kmpworkmanager

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import org.koin.dsl.module

/**
 * Koin dependency injection module for KMP WorkManager.
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
 * Creates a Koin module for KMP WorkManager with platform-specific scheduler and worker factory.
 *
 * v4.0.0+ Breaking Change: Now requires WorkerFactory parameter
 * v2.2.2+ New: Optional config parameter for logging configuration
 *
 * @param workerFactory User-provided factory for creating worker instances
 * @param config Configuration for logging and other settings. Default: KmpWorkManagerConfig()
 * @param iosTaskIds (iOS only) Additional task IDs for iOS BGTaskScheduler. Ignored on Android.
 */
expect fun kmpWorkerModule(
    workerFactory: WorkerFactory,
    config: KmpWorkManagerConfig = KmpWorkManagerConfig(),
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
