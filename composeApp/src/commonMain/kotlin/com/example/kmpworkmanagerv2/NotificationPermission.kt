package com.example.kmpworkmanagerv2

import androidx.compose.runtime.Composable

@Composable
expect fun rememberNotificationPermissionState(onPermissionResult: (Boolean) -> Unit): NotificationPermissionState

data class NotificationPermissionState(
    val hasPermission: Boolean,
    val shouldShowRequest: Boolean,
    val requestPermission: () -> Unit
)
