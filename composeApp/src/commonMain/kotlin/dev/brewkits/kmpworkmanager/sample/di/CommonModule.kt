package dev.brewkits.kmpworkmanager.sample.di

import dev.brewkits.kmpworkmanager.sample.push.DefaultPushNotificationHandler
import dev.brewkits.kmpworkmanager.sample.push.PushNotificationHandler
import org.koin.dsl.module

/**
 * Koin module containing dependencies shared across all platforms.
 */
val commonModule = module {
    // Defines a single instance of PushNotificationHandler using the default implementation.
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }
}