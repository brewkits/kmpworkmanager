package com.example.kmpworkmanagerv2.push

/**
 * A fake implementation of PushNotificationHandler for use in previews or unit tests.
 * All methods are no-ops (do nothing).
 */
class FakePushNotificationHandler : PushNotificationHandler {
    /**
     * No-op implementation for sending token to server.
     */
    override fun sendTokenToServer(token: String) {}

    /**
     * No-op implementation for handling push payload.
     */
    override fun handlePushPayload(payload: Map<String, String>) {}
}