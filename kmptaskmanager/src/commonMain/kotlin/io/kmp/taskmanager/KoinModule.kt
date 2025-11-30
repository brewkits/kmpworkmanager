package io.kmp.taskmanager

import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import org.koin.dsl.module

/**
 * Koin dependency injection module for KMP TaskManager.
 *
 * Usage in your app:
 * ```kotlin
 * startKoin {
 *     modules(kmpTaskManagerModule(context)) // Android
 *     // or
 *     modules(kmpTaskManagerModule())        // iOS
 * }
 * ```
 */

/**
 * Creates a Koin module for KMP TaskManager with platform-specific scheduler.
 * Call this function with platform-specific parameters to get the configured module.
 */
expect fun kmpTaskManagerModule(): org.koin.core.module.Module

/**
 * Common module definition that can be used directly if you have the scheduler instance.
 */
fun kmpTaskManagerCoreModule(scheduler: BackgroundTaskScheduler) = module {
    single<BackgroundTaskScheduler> { scheduler }
}
