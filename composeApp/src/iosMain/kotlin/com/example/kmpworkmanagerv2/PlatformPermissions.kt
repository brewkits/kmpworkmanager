package com.example.kmpworkmanagerv2

import androidx.compose.runtime.Composable
import platform.UserNotifications.UNUserNotificationCenter

@Composable
actual fun RequestNotificationPermission(onPermissionResult: (Boolean) -> Unit) {
    // Quyền đã được hỏi trong AppDelegate.swift, ở đây ta chỉ cần kiểm tra trạng thái
    UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
        onPermissionResult(settings?.authorizationStatus == 1L) // 1L = authorized
    }
}

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