package io.kmp.taskmanager.sample.di

import io.kmp.taskmanager.sample.push.DefaultPushNotificationHandler
import io.kmp.taskmanager.sample.push.PushNotificationHandler
import org.koin.dsl.module

/**
 * Koin module containing dependencies shared across all platforms.
 */
val commonModule = module {
    // Defines a single instance of PushNotificationHandler using the default implementation.
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }
}