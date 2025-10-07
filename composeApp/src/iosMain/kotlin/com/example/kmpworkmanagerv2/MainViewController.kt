package com.example.kmpworkmanagerv2

import androidx.compose.ui.window.ComposeUIViewController
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler

fun MainViewController(scheduler: BackgroundTaskScheduler, pushHandler: PushNotificationHandler) = ComposeUIViewController { App(scheduler, pushHandler) }