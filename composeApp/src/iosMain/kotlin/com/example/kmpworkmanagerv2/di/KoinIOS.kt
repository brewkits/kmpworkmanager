package com.example.kmpworkmanagerv2.di

import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class KoinIOS : KoinComponent {
    private val scheduler: BackgroundTaskScheduler by inject()
    private val pushHandler: PushNotificationHandler by inject()

    fun getScheduler(): BackgroundTaskScheduler = scheduler
    fun getPushHandler(): PushNotificationHandler = pushHandler
}
