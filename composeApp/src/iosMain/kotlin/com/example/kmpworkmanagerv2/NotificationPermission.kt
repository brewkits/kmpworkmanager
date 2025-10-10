package com.example.kmpworkmanagerv2

import androidx.compose.runtime.*
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNAuthorizationStatusAuthorized

/**
 * Actual implementation for iOS to manage notification permission state.
 * It uses UNUserNotificationCenter to check and request the permission.
 *
 * @param onPermissionResult A callback function invoked when the permission status is retrieved or updated.
 * @return NotificationPermissionState object containing current permission status and request function.
 */
@Composable
actual fun rememberNotificationPermissionState(onPermissionResult: (Boolean) -> Unit): NotificationPermissionState {
    // State to hold whether the app currently has notification permission
    var hasPermission by remember { mutableStateOf(false) }
    // State to hold whether the permission request dialog should be shown (true if permission is denied/not determined)
    var shouldShowRequest by remember { mutableStateOf(false) }

    // Use DisposableEffect to check the initial permission status when the composable enters the composition
    DisposableEffect(Unit) {
        // Get the current notification settings asynchronously
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            // Check if the authorization status is 'Authorized'
            val permissionState = settings?.authorizationStatus == UNAuthorizationStatusAuthorized
            hasPermission = permissionState
            shouldShowRequest = !permissionState // If not authorized, we should show the request UI
            onPermissionResult(permissionState) // Notify the caller of the initial state
        }
        // No cleanup needed
        onDispose { }
    }

    return NotificationPermissionState(
        hasPermission = hasPermission,
        shouldShowRequest = shouldShowRequest,
        requestPermission = {
            // Request authorization for the desired notification options (Alert, Sound, Badge)
            // (1UL shl 0) is UNAuthorizationOptionBadge
            // (1UL shl 1) is UNAuthorizationOptionSound
            // (1UL shl 2) is UNAuthorizationOptionAlert
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                options = (1UL shl 0) or (1UL shl 1) or (1UL shl 2)
            ) { isGranted, _ ->
                // Update states and notify caller after the request dialog is dismissed
                hasPermission = isGranted
                shouldShowRequest = !isGranted
                onPermissionResult(isGranted)
            }
        }
    )
}