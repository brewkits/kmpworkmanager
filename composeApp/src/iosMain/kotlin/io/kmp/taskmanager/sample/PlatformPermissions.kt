package io.kmp.taskmanager.sample

import androidx.compose.runtime.*
import io.kmp.taskmanager.sample.utils.Logger
import io.kmp.taskmanager.sample.utils.LogTags
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UserNotifications.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS implementation for Exact Alarm permission.
 * iOS does not have a dedicated "exact alarm" permission; scheduling relies on the general
 * Notification permission.
 *
 * Therefore, we treat this permission as always granted for logic purposes.
 *
 * @return ExactAlarmPermissionState indicating permission is always true and no request UI is needed.
 */
@Composable
actual fun rememberExactAlarmPermissionState(): ExactAlarmPermissionState {
    // iOS does not have a separate permission for "exact alarm"; it relies on general notification permission.
    // Thus, we always consider the permission granted and do not need to show a request UI.
    return ExactAlarmPermissionState(
        hasPermission = true, // Always true as there's no separate permission to check
        shouldShowRequest = false, // Never show a request dialog
        requestPermission = { /* Do nothing, as no specific permission request is needed */ }
    )
}

/**
 * iOS implementation for Notification permission.
 * Uses UNUserNotificationCenter to check and request authorization.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberNotificationPermissionState(onPermissionResult: (Boolean) -> Unit): NotificationPermissionState {
    var hasPermission by remember { mutableStateOf(false) }
    var shouldShowRequest by remember { mutableStateOf(false) }

    // Check current authorization status
    LaunchedEffect(Unit) {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            dispatch_async(dispatch_get_main_queue()) {
                val authStatus = settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined

                when (authStatus) {
                    UNAuthorizationStatusAuthorized,
                    UNAuthorizationStatusProvisional -> {
                        hasPermission = true
                        shouldShowRequest = false
                        Logger.i(LogTags.PERMISSION, "Notification permission already granted")
                    }
                    UNAuthorizationStatusDenied -> {
                        hasPermission = false
                        shouldShowRequest = true
                        Logger.w(LogTags.PERMISSION, "Notification permission denied by user")
                    }
                    UNAuthorizationStatusNotDetermined -> {
                        hasPermission = false
                        shouldShowRequest = true
                        Logger.d(LogTags.PERMISSION, "Notification permission not determined")
                    }
                    else -> {
                        hasPermission = false
                        shouldShowRequest = false
                        Logger.w(LogTags.PERMISSION, "Notification permission unknown status: $authStatus")
                    }
                }
            }
        }
    }

    return NotificationPermissionState(
        hasPermission = hasPermission,
        shouldShowRequest = shouldShowRequest,
        requestPermission = {
            Logger.i(LogTags.PERMISSION, "Requesting notification permission")

            // Request authorization with alert, sound, and badge
            UNUserNotificationCenter.currentNotificationCenter()
                .requestAuthorizationWithOptions(
                    options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
                ) { granted, error ->
                    dispatch_async(dispatch_get_main_queue()) {
                        hasPermission = granted
                        shouldShowRequest = !granted

                        // Invoke callback
                        onPermissionResult(granted)

                        if (error != null) {
                            Logger.e(LogTags.PERMISSION, "Error requesting notification permission: ${error.localizedDescription}")
                        } else if (granted) {
                            Logger.i(LogTags.PERMISSION, "Notification permission granted")
                        } else {
                            Logger.w(LogTags.PERMISSION, "Notification permission denied")
                        }
                    }
                }
        }
    )
}