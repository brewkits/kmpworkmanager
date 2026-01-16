package dev.brewkits.kmpworkmanager.koin

import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.WorkerFactory
import org.koin.dsl.module

/**
 * Koin dependency injection extension for KMP WorkManager.
 *
 * This extension module provides Koin integration for KMP WorkManager v2.1.0+.
 * The core library is now DI-agnostic, and Koin support is provided through this optional extension.
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
 *
 * @since 2.1.0
 */

/**
 * Creates a Koin module for KMP WorkManager with platform-specific scheduler and worker factory.
 *
 * This module:
 * - Initializes WorkerManagerConfig with your WorkerFactory
 * - Creates and provides BackgroundTaskScheduler
 * - Sets up event persistence (EventStore)
 *
 * @param workerFactory User-provided factory for creating worker instances
 * @param iosTaskIds (iOS only) Additional task IDs for iOS BGTaskScheduler. Ignored on Android.
 */
expect fun kmpWorkerModule(
    workerFactory: WorkerFactory,
    iosTaskIds: Set<String> = emptySet()
): org.koin.core.module.Module

/**
 * Common module definition for direct use (advanced usage).
 *
 * This is used internally by platform-specific implementations.
 * Most users should use kmpWorkerModule() instead.
 */
fun kmpWorkerCoreModule(
    scheduler: BackgroundTaskScheduler,
    workerFactory: WorkerFactory
) = module {
    single<BackgroundTaskScheduler> { scheduler }
    single<WorkerFactory> { workerFactory }
}
