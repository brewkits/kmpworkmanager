package dev.brewkits.kmpworkmanager.sample.di

import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.KmpWorkManagerConfig
import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import dev.brewkits.kmpworkmanager.background.data.DynamicTaskDispatcher
import dev.brewkits.kmpworkmanager.background.data.SingleTaskExecutor
import dev.brewkits.kmpworkmanager.background.domain.BackgroundTaskScheduler
import dev.brewkits.kmpworkmanager.sample.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.sample.debug.DebugSource
import dev.brewkits.kmpworkmanager.sample.debug.IosDebugSource
import dev.brewkits.kmpworkmanager.sample.push.DefaultPushNotificationHandler
import dev.brewkits.kmpworkmanager.sample.push.PushNotificationHandler
import dev.brewkits.kmpworkmanager.utils.Logger
import org.koin.dsl.module

/**
 * Koin module for the iOS target.
 *
 * The library itself no longer ships a Koin module — it is DI-agnostic as of 3.3.0.
 * This is the recommended integration pattern for apps that *do* use Koin: initialize
 * KmpWorkManager once, then expose its services as ordinary `single { }` bindings.
 */
val iosModule = module {
    // Initialize the library. Runs at module-construction time, i.e. the same point in
    // startup where `includes(kmpWorkerModule(...))` used to run.
    KmpWorkManager.initialize(
        workerFactory = IosWorkerFactory(),
        config = KmpWorkManagerConfig(logLevel = Logger.Level.DEBUG_LEVEL)
    )

    single<BackgroundTaskScheduler> { KmpWorkManager.getInstance().backgroundTaskScheduler }
    single<SingleTaskExecutor> { KmpWorkManager.getInstance().singleTaskExecutor }
    single<ChainExecutor> { KmpWorkManager.getInstance().chainExecutor }
    single<DynamicTaskDispatcher> { KmpWorkManager.getInstance().dynamicTaskDispatcher }

    // Single instance of the PushNotificationHandler using the default implementation
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }

    single<DebugSource> { IosDebugSource() }
}
