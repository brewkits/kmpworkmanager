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
 *
 * @param iosTaskIds (iOS only) Additional task IDs for iOS BGTaskScheduler. Must match Info.plist.
 *                    Ignored on Android.
 */
expect fun kmpTaskManagerModule(iosTaskIds: Set<String> = emptySet()): org.koin.core.module.Module

/**
 * Common module definition that can be used directly if you have the scheduler instance.
 */
fun kmpTaskManagerCoreModule(scheduler: BackgroundTaskScheduler) = module {
    single<BackgroundTaskScheduler> { scheduler }
}
