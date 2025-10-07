package com.example.kmpworkmanagerv2.push

interface PushNotificationHandler {
    /**
     * Gửi device token lên server của bạn.
     * @param token Device token từ FCM hoặc APNs.
     */
    fun sendTokenToServer(token: String)

    /**
     * Xử lý dữ liệu (payload) nhận được từ push notification.
     * @param payload Dữ liệu dạng Map<String, String>.
     */
    fun handlePushPayload(payload: Map<String, String>)
}