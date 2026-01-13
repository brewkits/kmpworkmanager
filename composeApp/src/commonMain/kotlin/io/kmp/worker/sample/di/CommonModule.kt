package io.kmp.worker.sample.di

import io.kmp.worker.sample.push.DefaultPushNotificationHandler
import io.kmp.worker.sample.push.PushNotificationHandler
import org.koin.dsl.module

/**
 * Koin module containing dependencies shared across all platforms.
 */
val commonModule = module {
    // Defines a single instance of PushNotificationHandler using the default implementation.
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }
}