package com.example.kmpworkmanagerv2

import androidx.compose.runtime.Composable

/**
 * Expected function declaration for managing the 'Exact Alarm' permission state in a Compose context.
 * This is primarily relevant for Android 12+ where this permission is separate from basic notifications.
 * iOS implementation will likely default to true/granted as it uses local notifications.
 *
 * @return ExactAlarmPermissionState containing the current status and request function.
 */
@Composable
expect fun rememberExactAlarmPermissionState(): ExactAlarmPermissionState

/**
 * Data class representing the state of the 'Exact Alarm' permission.
 *
 * @property hasPermission True if the application has the necessary 'exact alarm' permission.
 * @property shouldShowRequest True if the permission request UI (e.g., a button) should be shown.
 * @property requestPermission A function to trigger the platform-specific permission request flow.
 */
data class ExactAlarmPermissionState(
    val hasPermission: Boolean,
    val shouldShowRequest: Boolean,
    val requestPermission: () -> Unit
)