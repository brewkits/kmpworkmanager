package com.example.kmpworkmanagerv2.push

/**
 * Interface defining the necessary methods for handling push notification events
 * across different platforms (Android/iOS).
 */
interface PushNotificationHandler {
    /**
     * Sends the device token (FCM or APNs token) to your backend server for targeting.
     * @param token The device token received from FCM or APNs.
     */
    fun sendTokenToServer(token: String)

    /**
     * Processes the data (payload) received from a push notification.
     * This method typically contains the common business logic for push handling.
     * @param payload The data map received in the push notification.
     */
    fun handlePushPayload(payload: Map<String, String>)
}