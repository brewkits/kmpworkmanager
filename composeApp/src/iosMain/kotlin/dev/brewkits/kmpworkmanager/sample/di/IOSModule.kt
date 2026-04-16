package dev.brewkits.kmpworkmanager.sample.di

import dev.brewkits.kmpworkmanager.KmpWorkManagerConfig
import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.background.data.NativeTaskScheduler
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.kmpWorkerModule
import dev.brewkits.kmpworkmanager.persistence.ExecutionHistoryStore
import dev.brewkits.kmpworkmanager.persistence.IosExecutionHistoryStore
import dev.brewkits.kmpworkmanager.sample.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.sample.debug.DebugSource
import dev.brewkits.kmpworkmanager.sample.debug.IosDebugSource
import dev.brewkits.kmpworkmanager.sample.push.DefaultPushNotificationHandler
import dev.brewkits.kmpworkmanager.sample.push.PushNotificationHandler
import dev.brewkits.kmpworkmanager.utils.Logger
import org.koin.dsl.module

/**
 * Koin module for the iOS target.
 * Defines the platform-specific implementations of shared interfaces.
 */
val iosModule = module {
    // Include the library core module. 
    // This provides BackgroundTaskScheduler, ChainExecutor, and SingleTaskExecutor.
    // NativeTaskScheduler will now have the executors injected for simulator fallback.
    includes(kmpWorkerModule(
        workerFactory = IosWorkerFactory(),
        config = KmpWorkManagerConfig(logLevel = Logger.Level.DEBUG_LEVEL)
    ))

    // Single instance of the PushNotificationHandler using the default implementation
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }

    single<DebugSource> { IosDebugSource() }
}