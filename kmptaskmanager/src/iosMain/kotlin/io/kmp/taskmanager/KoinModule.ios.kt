package io.kmp.taskmanager

import io.kmp.taskmanager.background.data.NativeTaskScheduler
import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import org.koin.dsl.module

/**
 * iOS implementation of the Koin module.
 *
 * Usage:
 * ```kotlin
 * fun initKoinIos() {
 *     startKoin {
 *         modules(kmpTaskManagerModule())
 *     }
 * }
 * ```
 */
actual fun kmpTaskManagerModule() = module {
    single<BackgroundTaskScheduler> {
        NativeTaskScheduler()
    }
}
