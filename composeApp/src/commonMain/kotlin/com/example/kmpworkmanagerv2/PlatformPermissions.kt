package com.example.kmpworkmanagerv2

import androidx.compose.runtime.Composable



// Lời hứa về một state manager để xử lý quyền Exact Alarm
@Composable
expect fun rememberExactAlarmPermissionState(): ExactAlarmPermissionState

// Data class chung để chứa trạng thái quyền
data class ExactAlarmPermissionState(
    val hasPermission: Boolean,
    val shouldShowRequest: Boolean,
    val requestPermission: () -> Unit
)