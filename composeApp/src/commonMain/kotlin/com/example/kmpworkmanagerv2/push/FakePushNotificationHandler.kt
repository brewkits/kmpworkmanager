package com.example.kmpworkmanagerv2.push

class FakePushNotificationHandler : PushNotificationHandler {
    override fun sendTokenToServer(token: String) {}

    override fun handlePushPayload(payload: Map<String, String>) {}
}
