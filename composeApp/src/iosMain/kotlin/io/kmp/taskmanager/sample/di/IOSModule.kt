package io.kmp.taskmanager.sample.di

import io.kmp.taskmanager.sample.background.data.ChainExecutor
import io.kmp.taskmanager.sample.background.data.IosWorkerFactory
import io.kmp.taskmanager.sample.background.data.NativeTaskScheduler
import io.kmp.taskmanager.sample.background.data.SingleTaskExecutor
import io.kmp.taskmanager.sample.background.domain.BackgroundTaskScheduler
import io.kmp.taskmanager.sample.debug.DebugSource
import io.kmp.taskmanager.sample.debug.IosDebugSource
import io.kmp.taskmanager.sample.push.DefaultPushNotificationHandler
import io.kmp.taskmanager.sample.push.PushNotificationHandler
import org.koin.dsl.module

/**
 * Koin module for the iOS target.
 * Defines the platform-specific implementations of shared interfaces.
 */
val iosModule = module {
    // Single instance of the BackgroundTaskScheduler using the iOS-specific implementation
    single<BackgroundTaskScheduler> { NativeTaskScheduler() }
    single<DebugSource> { IosDebugSource() }
    // Single instance of the PushNotificationHandler using the default implementation (if no specific iOS logic is needed here)
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }

    // Factory for creating iOS-specific workers
    factory { IosWorkerFactory() }

    // Single instance of the ChainExecutor for handling task chains on iOS
    single { ChainExecutor(get()) }

    // Single instance of the SingleTaskExecutor for handling individual tasks on iOS
    single { SingleTaskExecutor(get()) }
}