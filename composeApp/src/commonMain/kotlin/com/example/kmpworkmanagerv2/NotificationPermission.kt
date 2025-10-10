package com.example.kmpworkmanagerv2

import androidx.compose.runtime.Composable

/**
 * Expected function declaration for managing notification permission state in a Compose context.
 * The actual implementation will be provided by platform-specific modules (e.g., Android, iOS).
 *
 * @param onPermissionResult A callback function invoked when the permission status is retrieved or updated.
 * @return NotificationPermissionState containing the current status and request function.
 */
@Composable
expect fun rememberNotificationPermissionState(onPermissionResult: (Boolean) -> Unit): NotificationPermissionState

/**
 * Data class representing the state of the notification permission.
 *
 * @property hasPermission True if the application has the necessary notification permission.
 * @property shouldShowRequest True if the permission request UI (e.g., a button) should be shown.
 * @property requestPermission A function to trigger the platform-specific permission request flow.
 */
data class NotificationPermissionState(
    val hasPermission: Boolean,
    val shouldShowRequest: Boolean,
    val requestPermission: () -> Unit
)