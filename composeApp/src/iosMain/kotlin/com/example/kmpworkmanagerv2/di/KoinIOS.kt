package com.example.kmpworkmanagerv2.di

import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A helper class that inherits from KoinComponent to allow easy access
 * to dependencies injected via Koin from the Swift/Objective-C side of the iOS application.
 */
class KoinIOS : KoinComponent {
    // Inject the BackgroundTaskScheduler dependency
    private val scheduler: BackgroundTaskScheduler by inject()
    // Inject the PushNotificationHandler dependency
    private val pushHandler: PushNotificationHandler by inject()

    /**
     * Provides access to the injected BackgroundTaskScheduler instance.
     */
    fun getScheduler(): BackgroundTaskScheduler = scheduler

    /**
     * Provides access to the injected PushNotificationHandler instance.
     */
    fun getPushHandler(): PushNotificationHandler = pushHandler
}