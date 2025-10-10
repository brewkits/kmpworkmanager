package com.example.kmpworkmanagerv2

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSDictionary

/**
 * Actual function to display a local notification (within the app's process) on iOS.
 * This implementation uses NSNotificationCenter to post a notification, which can be
 * observed by other parts of the Swift/Objective-C or Kotlin code (e.g., to manually
 * show a system-style UNUserNotification or just update the UI).
 *
 * @param title The title of the notification.
 * @param body The body/content of the notification.
 */
actual fun showNotification(title: String, body: String) {
    // Create a dictionary (userInfo) to hold the notification data (title and body)
    val userInfo: Map<Any?, *> = mapOf("title" to title, "body" to body)
    // Post a custom NSNotification with a specific name ("showNotification") and the data
    // This mechanism is typically used for internal app communication on Apple platforms.
    NSNotificationCenter.defaultCenter.postNotificationName("showNotification", null, userInfo)
}