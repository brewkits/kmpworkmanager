package com.example.kmpworkmanagerv2.push

class DefaultPushNotificationHandler : PushNotificationHandler {
    override fun sendTokenToServer(token: String) {
        println(" KMP_PUSH: Received token. Would send to server: $token")
        // Trong dự án thật, bạn sẽ gọi API service ở đây.
    }

    override fun handlePushPayload(payload: Map<String, String>) {
        println(" KMP_PUSH: Received payload. Processing in common code...")
        payload.forEach { (key, value) ->
            println("  - $key: $value")
        }
        // Xử lý logic nghiệp vụ chung ở đây, ví dụ: cập nhật DB, refresh UI...
    }
}