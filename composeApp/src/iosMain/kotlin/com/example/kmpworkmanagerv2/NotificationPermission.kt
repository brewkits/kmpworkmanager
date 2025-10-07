package com.example.kmpworkmanagerv2

import androidx.compose.runtime.*
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNAuthorizationStatusAuthorized

@Composable
actual fun rememberNotificationPermissionState(onPermissionResult: (Boolean) -> Unit): NotificationPermissionState {
    var hasPermission by remember { mutableStateOf(false) }
    var shouldShowRequest by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val permissionState = settings?.authorizationStatus == UNAuthorizationStatusAuthorized
            hasPermission = permissionState
            shouldShowRequest = !permissionState
            onPermissionResult(permissionState)
        }
        onDispose { }
    }

    return NotificationPermissionState(
        hasPermission = hasPermission,
        shouldShowRequest = shouldShowRequest,
        requestPermission = {
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                options = (1UL shl 0) or (1UL shl 1) or (1UL shl 2)
            ) { isGranted, _ ->
                hasPermission = isGranted
                shouldShowRequest = !isGranted
                onPermissionResult(isGranted)
            }
        }
    )
}
