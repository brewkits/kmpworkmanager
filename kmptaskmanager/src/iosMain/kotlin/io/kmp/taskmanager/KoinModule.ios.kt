package io.kmp.taskmanager

import io.kmp.taskmanager.background.data.NativeTaskScheduler
import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import org.koin.dsl.module

/**
 * iOS implementation of the Koin module.
 *
 * Usage:
 * ```kotlin
 * // Default usage (with default task IDs)
 * fun initKoinIos() {
 *     startKoin {
 *         modules(kmpTaskManagerModule())
 *     }
 * }
 *
 * // With custom task IDs
 * fun initKoinIos() {
 *     startKoin {
 *         modules(kmpTaskManagerModule(
 *             iosTaskIds = setOf("my-sync-task", "my-upload-task")
 *         ))
 *     }
 * }
 * ```
 *
 * @param iosTaskIds Additional iOS task IDs that must match Info.plist BGTaskSchedulerPermittedIdentifiers
 */
actual fun kmpTaskManagerModule(iosTaskIds: Set<String>) = module {
    single<BackgroundTaskScheduler> {
        NativeTaskScheduler(additionalPermittedTaskIds = iosTaskIds)
    }
}
