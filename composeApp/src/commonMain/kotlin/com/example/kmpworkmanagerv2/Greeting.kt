package com.example.kmpworkmanagerv2

/**
 * A simple class to demonstrate platform-specific greetings.
 */
class Greeting {
    // Get the platform-specific implementation.
    private val platform = getPlatform()

    /**
     * Returns a greeting message that includes the platform name.
     */
    fun greet(): String {
        return "Hello, ${platform.name}!"
    }
}