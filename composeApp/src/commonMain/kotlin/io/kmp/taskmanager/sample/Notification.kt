package io.kmp.taskmanager.sample

/**
 * Expected function declaration to display a simple local notification.
 * The actual implementation will use platform-specific APIs (e.g., NotificationManager on Android, UNUserNotificationCenter on iOS).
 *
 * @param title The title of the notification.
 * @param body The body/content of the notification.
 */
expect fun showNotification(title: String, body: String)