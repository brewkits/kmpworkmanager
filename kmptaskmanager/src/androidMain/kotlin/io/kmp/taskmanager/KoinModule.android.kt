package io.kmp.taskmanager

import android.content.Context
import io.kmp.taskmanager.background.data.NativeTaskScheduler
import io.kmp.taskmanager.background.domain.BackgroundTaskScheduler
import org.koin.dsl.module

/**
 * Android implementation of the Koin module.
 *
 * Usage:
 * ```kotlin
 * startKoin {
 *     androidContext(this@Application)
 *     modules(kmpTaskManagerModule())
 * }
 * ```
 */
actual fun kmpTaskManagerModule() = module {
    single<BackgroundTaskScheduler> {
        val context = get<Context>()
        NativeTaskScheduler(context)
    }
}
