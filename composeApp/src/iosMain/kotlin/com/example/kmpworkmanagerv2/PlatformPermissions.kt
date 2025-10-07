package com.example.kmpworkmanagerv2

import androidx.compose.runtime.Composable
import platform.UserNotifications.UNUserNotificationCenter



@Composable
actual fun rememberExactAlarmPermissionState(): ExactAlarmPermissionState {
    // iOS không có quyền riêng cho "exact alarm", nó dùng quyền notification chung.
    // Vì vậy, ta luôn coi là đã có quyền và không cần hiển thị nút yêu cầu.
    return ExactAlarmPermissionState(
        hasPermission = true,
        shouldShowRequest = false,
        requestPermission = { /* Không làm gì cả */ }
    )
}