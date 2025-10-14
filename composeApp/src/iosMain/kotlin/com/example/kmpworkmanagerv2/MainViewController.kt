package com.example.kmpworkmanagerv2

import androidx.compose.ui.window.ComposeUIViewController
import com.example.kmpworkmanagerv2.background.domain.BackgroundTaskScheduler
import com.example.kmpworkmanagerv2.push.PushNotificationHandler

/**
 * The main entry point for the iOS application, creating the root ComposeUIViewController.
 * It injects necessary dependencies (scheduler and push handler) into the shared 'App' composable.
 *
 * @param scheduler The platform-specific BackgroundTaskScheduler implementation.
 * @param pushHandler The platform-specific PushNotificationHandler implementation.
 * @return A ComposeUIViewController instance to be used as the root view controller.
 */
fun MainViewController(scheduler: BackgroundTaskScheduler, pushHandler: PushNotificationHandler): platform.UIKit.UIViewController {
    return ComposeUIViewController {
        App(scheduler = scheduler, pushHandler = pushHandler)
    }
}