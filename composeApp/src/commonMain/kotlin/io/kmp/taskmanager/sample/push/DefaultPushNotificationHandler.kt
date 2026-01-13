package io.kmp.taskmanager.sample.push

/**
 * Default implementation of PushNotificationHandler containing common logic.
 * This class handles logging and placeholders for actual server/business logic.
 */
class DefaultPushNotificationHandler : PushNotificationHandler {
    /**
     * Placeholder implementation for sending the device token to the server.
     */
    override fun sendTokenToServer(token: String) {
        println(" KMP_PUSH: Received token. Would send to server: $token")
        // In a real project, you would call an API service here to send the token.
    }

    /**
     * Handles and processes the push notification payload.
     * This is where shared business logic for push data should reside.
     */
    override fun handlePushPayload(payload: Map<String, String>) {
        println(" KMP_PUSH: Received payload. Processing in common code...")
        payload.forEach { (key, value) ->
            println("  - $key: $value")
        }
        // Handle common business logic here, e.g., update DB, refresh UI components, etc.
    }
}