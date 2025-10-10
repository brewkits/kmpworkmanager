package com.example.kmpworkmanagerv2.di

import com.example.kmpworkmanagerv2.background.data.NativeTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.push.DefaultPushNotificationHandler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler
import org.koin.dsl.module

/**
 * Koin module for the iOS target.
 * Defines the platform-specific implementations of shared interfaces.
 */
val iosModule = module {
    // Single instance of the BackgroundTaskScheduler using the iOS-specific implementation
    single<BackgroundTaskScheduler> { NativeTaskScheduler() }
    // Single instance of the PushNotificationHandler using the default implementation (if no specific iOS logic is needed here)
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }
}