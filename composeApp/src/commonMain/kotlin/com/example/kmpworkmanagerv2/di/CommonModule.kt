package com.example.kmpworkmanagerv2.di

import com.example.kmpworkmanagerv2.push.DefaultPushNotificationHandler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler
import org.koin.dsl.module

val commonModule = module {
    single<PushNotificationHandler> { DefaultPushNotificationHandler() }
}
