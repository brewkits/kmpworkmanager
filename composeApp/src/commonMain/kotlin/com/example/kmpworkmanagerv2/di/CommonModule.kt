package com.example.kmpworkmanagerv2.di

import com.example.kmpworkmanagerv2.push.DefaultPushNotificationHandler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler
import org.koin.dsl.module

/**
 * Koin module containing dependencies shared across all platforms.
 */
val commonModule = module {
    // Defines a single instance of PushNotificationHandler using the default implementation.
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }
}