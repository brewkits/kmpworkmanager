package com.example.kmpworkmanagerv2

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSDictionary

actual fun showNotification(title: String, body: String) {
    val userInfo: Map<Any?, *> = mapOf("title" to title, "body" to body)
    NSNotificationCenter.defaultCenter.postNotificationName("showNotification", null, userInfo)
}
