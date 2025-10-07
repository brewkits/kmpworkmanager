
package com.example.kmpworkmanagerv2.di

import com.example.kmpworkmanagerv2.background.data.NativeTaskScheduler
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.push.DefaultPushNotificationHandler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler
import org.koin.dsl.module

val iosModule = module {
    single<BackgroundTaskScheduler> { NativeTaskScheduler() }
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }
}
